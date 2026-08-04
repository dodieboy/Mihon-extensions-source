package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.UserAgentType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.random.Random

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {

    private val nhLang: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }

    final override val baseUrl = "https://nhentai.net"
    private val apiBaseUrl = "https://nhentai.net/api/v2"

    override val supportsLatest = true

    private val json: Json by lazy { Json { ignoreUnknownKeys = true } }

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(4)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .setRandomUserAgent(UserAgentType.MOBILE)
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
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (nhLang.isBlank()) "$baseUrl/?page=$page" else "$baseUrl/language/$nhLang/?page=$page"
        return parseGalleryList(client.get(url))
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (nhLang.isBlank()) "$baseUrl/search/?q=\"\"&sort=popular&page=$page" else "$baseUrl/language/$nhLang/popular?page=$page"
        return parseGalleryList(client.get(url))
    }

    private fun parseGalleryList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("#content .container:not(.index-popular) .gallery:not(.blacklisted)").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.select("a").attr("href"))
                title = element.select("a > div").text().replace("\"", "").let {
                    if (displayFullTitle) it.trim() else it.shortenTitle()
                }
                thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
                    if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
                }
            }
        }
        val hasNextPage = document.select("#content > section.pagination > a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    private fun combineQuery(filters: FilterList): String = filters.filterIsInstance<Filter.Text>()
        .mapNotNull { it.state.takeIf { it.isNotBlank() } }
        .joinToString(" ")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            return searchMangaById(id, page)
        }

        if (query.toIntOrNull() != null) {
            return searchMangaById(query, page)
        }

        val filterList = if (filters.isEmpty()) getFilterList(data = null) else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage = filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$apiBaseUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("page", offsetPage.toString())
                .build()
            return parseSearchApiResponse(client.get(url), page)
        }

        val url = "$apiBaseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
            .addQueryParameter("page", offsetPage.toString())

        filterList.firstInstanceOrNull<SortFilter>()?.let { f ->
            url.addQueryParameter("sort", f.toUriPart())
        }

        return parseSearchApiResponse(client.get(url.build()), page)
    }

    private suspend fun searchMangaById(id: String, page: Int): MangasPage {
        val response = client.get("$apiBaseUrl/galleries/$id")
        val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
        val manga = mangaDetailsParse(gallery).apply { setUrlWithoutDomain("/g/$id/") }
        return MangasPage(listOf(manga), false)
    }

    private suspend fun parseSearchApiResponse(response: Response, page: Int): MangasPage {
        if (response.request.url.toString().contains("/login/")) {
            val document = response.asJsoup()
            if (document.select(".fa-sign-in").isNotEmpty()) {
                throw Exception("Log in via WebView or add your API key in Settings to view favorites")
            }
        }

        val searchResponse = json.decodeFromString<PaginatedResponse<GalleryListItem>>(response.body.string())
        val mangas = searchResponse.result.filter { !it.blacklisted }.map { galleryListItemToSManga(it) }
        val hasNextPage = searchResponse.result.isNotEmpty() && searchResponse.num_pages > page

        return MangasPage(mangas, hasNextPage)
    }

    private fun galleryListItemToSManga(gallery: GalleryListItem): SManga = SManga.create().apply {
        setUrlWithoutDomain("/g/${gallery.id}/")
        title = if (displayFullTitle) {
            gallery.english_title
        } else {
            gallery.english_title.shortenTitle()
        }
        thumbnail_url = "https://t${Random.nextInt(1, 5)}.nhentai.net/${gallery.thumbnail}"
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val galleryId = url.pathSegments.lastOrNull() ?: url.toString().extractGalleryId()
        val response = client.get("$apiBaseUrl/galleries/$galleryId")
        val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
        return mangaDetailsParse(gallery).apply { setUrlWithoutDomain("/g/$galleryId/") }
    }

    private fun mangaDetailsParse(gallery: GalleryDetailResponse): SManga = SManga.create().apply {
        title = if (displayFullTitle) {
            gallery.title.english ?: gallery.title.japanese ?: gallery.title.pretty!!.shortenTitle()
        } else {
            gallery.title.pretty ?: (gallery.title.english ?: gallery.title.japanese)!!.shortenTitle()
        }
        thumbnail_url = "https://t${Random.nextInt(1, 5)}.nhentai.net/${gallery.cover.thumbnail?.path ?: gallery.cover.path}"
        status = SManga.COMPLETED

        val artists = gallery.tags.filter { it.type == "artist" }.map { it.name }
        val groups = gallery.tags.filter { it.type == "group" }.map { it.name }

        author = artists.joinToString(", ").takeIf { it.isNotBlank() }
        artist = groups.joinToString(", ").takeIf { it.isNotBlank() }

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

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val galleryId = chapter.url.extractGalleryId()
        val response = client.get("$apiBaseUrl/galleries/$galleryId")
        val gallery = json.decodeFromString<GalleryDetailResponse>(response.body.string())
        return pageListParse(gallery)
    }

    private fun pageListParse(gallery: GalleryDetailResponse): List<Page> = gallery.pages.mapIndexed { i, pageInfo ->
        val path = pageInfo.path

        Page(
            index = i,
            imageUrl = "https://i${Random.nextInt(1, 5)}.nhentai.net/$path",
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) mangaDetailsParse(fetchGalleryDetails(manga)) else manga
        val updatedChapters = if (fetchChapters) {
            val gallery = fetchGalleryDetails(manga)
            listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    scanlator = gallery.tags.filter { it.type == "group" }.map { it.name }
                        .joinToString(", ").takeIf { it.isNotBlank() }
                    date_upload = gallery.upload_date * 1000
                    setUrlWithoutDomain(manga.url)
                },
            )
        } else {
            chapters
        }
        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    private suspend fun fetchGalleryDetails(manga: SManga): GalleryDetailResponse {
        val galleryId = manga.url.extractGalleryId()
        val response = client.get("$apiBaseUrl/galleries/$galleryId")
        return json.decodeFromString(response.body.string())
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
        private const val API_KEY_PREF = "API Key"
    }
}
