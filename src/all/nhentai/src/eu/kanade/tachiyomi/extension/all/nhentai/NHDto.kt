package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
class GalleryDetailResponse(
    val id: Int,
    val media_id: String,
    val title: GalleryTitle,
    val cover: CoverInfo,
    val pages: List<PageInfo>,
    val tags: List<TagResponse>,
    val num_pages: Int,
    val num_favorites: Long,
    val upload_date: Long,
)

@Serializable
class GalleryTitle(
    var english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class CoverInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val thumbnail: ThumbnailInfo? = null,
)

@Serializable
class ThumbnailInfo(
    val path: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
class PageInfo(
    val path: String,
    val width: Int,
    val height: Int,
    // API returns extension as part of path, not as separate field
    // Need to parse from path
)

@Serializable
class TagResponse(
    val id: Int,
    val type: String,
    val name: String,
    val slug: String,
    val count: Int,
    val is_blacklisted: Boolean? = null,
)

@Serializable
class SearchResponse(
    val result: List<GalleryResult>,
    val num_pages: Int,
    val total: Int,
    val per_page: Int,
)

@Serializable
class GalleryResult(
    val id: Int,
    val media_id: String,
    val title: GalleryTitle,
    val cover: CoverInfo,
    val tags: List<TagResponse>,
    val num_pages: Int,
    val num_favorites: Long,
    val upload_date: Long,
)
