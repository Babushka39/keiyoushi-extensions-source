package eu.kanade.tachiyomi.extension.all.akuma

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

private val json: Json by injectLazy()

@Serializable
data class GalleryDto(
    val id: Int,
    val title: TitleDto,
    val cover: ImageDto,
    val tags: List<TagDto>,
    @SerialName("num_pages") val numPages: Int,
    @SerialName("upload_date") val uploadDate: Long,
    @SerialName("num_favorites") val numFavorites: Int,
    val pages: List<PageDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        title = this@GalleryDto.title.pretty.ifEmpty { this@GalleryDto.title.english }
        thumbnail_url = "https://t3.nhentai.net/${cover.path}"

        val tagsByType = tags.groupBy { it.type }

        artist = tagsByType["artist"]?.joinToString { it.name } ?: ""
        author = tagsByType["group"]?.joinToString { it.name } ?: artist

        genre = tagsByType["tag"]?.joinToString { it.name } ?: ""

        description = buildString {
            append("Pages: $numPages\n")
            tagsByType["parody"]?.let { append("Parodies: ${it.joinToString { p -> p.name }}\n") }
            tagsByType["character"]?.let { append("Characters: ${it.joinToString { c -> c.name }}\n") }
            append("Favorites: $numFavorites\n")
        }

        status = SManga.UNKNOWN
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }
}

@Serializable
data class PageDto(
    val number: Int,
    val path: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class TitleDto(
    val english: String = "",
    val japanese: String = "",
    val pretty: String = "",
)

@Serializable
data class TagDto(
    val id: Int,
    val type: String, // "tag", "artist", "group", "parody", "character", "language", "category"
    val name: String,
    val slug: String,
    val url: String,
    val count: Int = 0,
)

@Serializable
data class ImageDto(
    val path: String,
    val width: Int,
    val height: Int,
)
