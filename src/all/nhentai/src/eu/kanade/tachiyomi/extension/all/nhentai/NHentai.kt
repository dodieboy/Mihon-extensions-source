package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit
import kotlin.random.Random

open class NHentai(
    override val lang: String,
    private val nhLang: String,
) : ParsedHttpSource(),
    ConfigurableSource {

    final override val baseUrl = "https://nhentai.net"
    private val apiBaseUrl = "https://nhentai.net/api/v2"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val name = "NHentai"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(4)
            .connectTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent(
            filterInclude = listOf("chrome"),
        )
        .add("Authorization", preferences.getString(API_KEY_PREF, "")?.let { "Key $it" } ?: "")

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    private fun String.extractGalleryId(): String = if (this.startsWith("/g/")) {
        this.removePrefix("/g/").removeSuffix("/").substringBefore("/")
    } else {
        this.removePrefix("/").substringBefore("/")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = API_KEY_PREF
            title = "API Key"
            summary = "Optional: Enter your nhentai API key for accessing favorites and profile features"
            setDefaultValue("")
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    override fun latestUpdatesRequest(page: Int) = GET(if (nhLang.isBlank()) "$baseUrl/?page=$page" else "$baseUrl/language/$nhLang/?page=$page", headers)

    override fun latestUpdatesSelector() = "#content .container:not(.index-popular) .gallery:not(.blacklisted)"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a > div").text().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        }
    }

    override fun latestUpdatesNextPageSelector() = "#content > section.pagination > a.next"

    override fun popularMangaRequest(page: Int) = GET(if (nhLang.isBlank()) "$baseUrl/search/?q=\"\"&sort=popular&page=$page" else "$baseUrl/language/$nhLang/popular?page=$page", headers)

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = when {
        query.startsWith(PREFIX_ID_SEARCH) -> {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        }

        query.toIntOrNull() != null -> {
            client.newCall(searchMangaByIdRequest(query))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, query) }
        }

        else -> super.fetchSearchManga(page, query, filters)
    }.flatMap { mangasPage ->
        if (mangasPage.mangas.isEmpty() && mangasPage.hasNextPage) {
            fetchSearchManga(page + 1, query, filters)
        } else {
            Observable.just(mangasPage)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$baseUrl/favorites/".toHttpUrl().newBuilder()
                .addQueryParameter("page", offsetPage.toString())

            return GET(url.build(), headers)
        } else {
            val url = "$apiBaseUrl/search".toHttpUrl().newBuilder()
                // Blank query (Multi + sort by popular month/week/day) shows a 404 page
                // Searching for `""` is a hacky way to return everything without any filtering
                .addQueryParameter("query", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", offsetPage.toString())

            filterList.findInstance<SortFilter>()?.let { f ->
                url.addQueryParameter("sort", f.toUriPart())
            }

            return GET(url.build(), headers)
        }
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
                    val y = !(filter.name == "Pages" || filter.name == "Uploaded")
                    if (tag.startsWith("-")) append("-")
                    append(filter.name, ':')
                    if (y) append('"')
                    append(tag.removePrefix("-"))
                    if (y) append('"')
                    append(" ")
                }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$apiBaseUrl/galleries/$id", headers)

    private fun galleryListItemToSManga(gallery: GalleryListItem): SManga = SManga.create().apply {
        setUrlWithoutDomain("/g/${gallery.id}/")
        title = if (displayFullTitle) {
            gallery.english_title
        } else {
            gallery.english_title.shortenTitle()
        }
        thumbnail_url = "https://t${Random.nextInt(1, 5)}.nhentai.net/${gallery.thumbnail}"
    }

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
        val details = mangaDetailsParse(gallery)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains("/login/")) {
            val document = response.asJsoup()
            if (document.select(".fa-sign-in").isNotEmpty()) {
                throw Exception("Log in via WebView or add your API key in Settings to view favorites")
            }
            return super.searchMangaParse(response)
        }

        // Parse v2 search API response
        val searchResponse = json.decodeFromString<PaginatedResponse<GalleryListItem>>(response.body.string())
        val mangas = searchResponse.result.filter { !it.blacklisted }.map { galleryListItemToSManga(it) }
        val hasNextPage = searchResponse.result.isNotEmpty() && searchResponse.num_pages > 1

        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val galleryId = manga.url.extractGalleryId()
        return client.newCall(GET("$apiBaseUrl/galleries/$galleryId", headers))
            .asObservableSuccess()
            .map { response ->
                val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
                mangaDetailsParse(gallery)
            }
    }

    private fun mangaDetailsParse(gallery: GalleryDetailResponse): SManga = SManga.create().apply {
        title = if (displayFullTitle) {
            gallery.title.english ?: gallery.title.japanese ?: gallery.title.pretty!!
        } else {
            gallery.title.pretty ?: (gallery.title.english ?: gallery.title.japanese)!!.shortenTitle()
        }
        thumbnail_url = "https://t${Random.nextInt(1, 5)}.nhentai.net/${gallery.cover.thumbnail?.path ?: gallery.cover.path}"
        status = SManga.COMPLETED

        // Extract artists and groups from tags
        val artists = gallery.tags.filter { it.type == "artist" }.map { it.name }
        val groups = gallery.tags.filter { it.type == "group" }.map { it.name }

        artist = artists.joinToString(", ").takeIf { it.isNotBlank() }
        author = groups.joinToString(", ").takeIf { it.isNotBlank() } ?: artist

        // Build description from API response
        description = buildString {
            appendLine("Full English and Japanese titles:")
            appendLine(gallery.title.english ?: gallery.title.japanese ?: gallery.title.pretty ?: "")
            appendLine(gallery.title.japanese ?: "")
            appendLine()
            appendLine("Pages: ${gallery.num_pages}")
            appendLine("Favorited by: ${gallery.num_favorites}")
            appendLine()
            append(getApiTagDescription(gallery.tags))
        }
        genre = getApiTags(gallery.tags)
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun getApiTags(tags: List<TagResponse>): String = tags.filter { it.type == "tag" || it.type == "category" }
        .joinToString(", ") { it.name }

    private fun getApiTagDescription(tags: List<TagResponse>): String {
        val tagMap = tags.groupBy { it.type }

        return buildString {
            tagMap.forEach { (type, tagList) ->
                if (tagList.isNotEmpty()) {
                    appendLine("${type.replaceFirstChar { it.uppercase() }}: ${tagList.joinToString(", ") { it.name }}")
                }
            }
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val galleryId = manga.url.extractGalleryId()
        return client.newCall(GET("$apiBaseUrl/galleries/$galleryId", headers))
            .asObservableSuccess()
            .map { response ->
                val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
                chapterListParse(gallery, manga.url)
            }
    }

    private fun chapterListParse(gallery: GalleryDetailResponse, mangaUrl: String): List<SChapter> {
        // Extract groups from tags
        val groups = gallery.tags.filter { it.type == "group" }.map { it.name }
        val artists = gallery.tags.filter { it.type == "artist" }.map { it.name }
        val scanlator = groups.ifEmpty { artists }.joinToString(", ")

        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                this.scanlator = scanlator
                date_upload = gallery.upload_date * 1000
                setUrlWithoutDomain(mangaUrl)
            },
        )
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    override fun chapterListSelector() = throw UnsupportedOperationException()

    override fun mangaDetailsParse(document: Document) = throw UnsupportedOperationException()

    override fun pageListParse(document: Document) = throw UnsupportedOperationException()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val galleryId = chapter.url.extractGalleryId()
        return client.newCall(GET("$apiBaseUrl/galleries/$galleryId", headers))
            .asObservableSuccess()
            .map { response ->
                val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
                pageListParse(gallery)
            }
    }

    private fun pageListParse(gallery: GalleryDetailResponse): List<Page> = gallery.pages.mapIndexed { i, pageInfo ->
        val path = pageInfo.path

        Page(
            index = i,
            imageUrl = "https://i${Random.nextInt(1, 5)}.nhentai.net/$path",
        )
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("Tags")
    class CategoryFilter : AdvSearchEntryFilter("Categories")
    class GroupFilter : AdvSearchEntryFilter("Groups")
    class ArtistFilter : AdvSearchEntryFilter("Artists")
    class ParodyFilter : AdvSearchEntryFilter("Parodies")
    class CharactersFilter : AdvSearchEntryFilter("Characters")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Recent", "date"),
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Month", "popular-month"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
            ),
        )

    private inline fun <reified T> String.parseAs(): T {
        val data = Regex("""\\u([0-9A-Fa-f]{4})""").replace(this) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return json.decodeFromString(
            data,
        )
    }
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
        private const val API_KEY_PREF = "API Key"
    }
}
