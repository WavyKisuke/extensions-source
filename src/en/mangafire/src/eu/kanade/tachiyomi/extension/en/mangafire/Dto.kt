package eu.kanade.tachiyomi.extension.en.mangafire

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
class ApiResponse<T>(val items: List<T> = emptyList(), val meta: ApiMeta? = null)

@Serializable
class ApiMeta(val lastPage: Int = 1, val hasNext: Boolean = false)

@Serializable
class TagResponse(val data: List<TagDto> = emptyList())

@Serializable
class TagDto(val id: Int, val type: String)

@Serializable
class MangaDto(
    private val hid: String,
    private val slug: String? = null,
    private val title: String,
    private val poster: PosterDto? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/title/$hid${slug?.let { "-$it" } ?: ""}"
        title = this@MangaDto.title
        thumbnail_url = poster?.large ?: poster?.medium ?: poster?.small
        initialized = false
    }
}

@Serializable
class MangaDetailsResponse(val data: MangaDetailsDto)

@Serializable
class MangaDetailsDto(
    private val hid: String,
    private val slug: String? = null,
    private val title: String,
    private val type: String? = null,
    private val status: String? = null,
    private val poster: PosterDto? = null,
    private val synopsisHtml: String? = null,
    private val authors: List<EntityDto>? = null,
    private val artists: List<EntityDto>? = null,
    private val genres: List<EntityDto>? = null,
    private val themes: List<EntityDto>? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/title/$hid${slug?.let { "-$it" } ?: ""}"
        title = this@MangaDetailsDto.title
        thumbnail_url = poster?.large ?: poster?.medium ?: poster?.small
        author = authors.orEmpty().joinToString { it.title }
        artist = artists.orEmpty().joinToString { it.title }
        description = synopsisHtml?.let { Jsoup.parseBodyFragment(it).text() }.orEmpty()
        genre = buildList {
            type?.replaceFirstChar { it.uppercase() }?.let { add(it) }
            genres.orEmpty().forEach { add(it.title) }
            themes.orEmpty().forEach { add(it.title) }
        }.joinToString()
        status = when (this@MangaDetailsDto.status?.lowercase()) {
            "releasing" -> SManga.ONGOING
            "finished", "completed" -> SManga.COMPLETED
            "on_hiatus" -> SManga.ON_HIATUS
            "discontinued" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        // Do not force the initialized flag here. Tachimanga's lib1.6 runtime
        // can reject a detail object at this point, which prevents chapters
        // from being displayed even though the chapter API itself works.
    }
}

@Serializable
class PosterDto(val small: String? = null, val medium: String? = null, val large: String? = null)

@Serializable
class EntityDto(val title: String)

@Serializable
class VolumeDto(
    private val id: Int,
    private val number: Int,
    private val name: String? = null,
    private val chapterCount: Int,
    val language: String,
) {
    fun toSChapter(mangaUrl: String): SChapter = SChapter.create().apply {
        url = "$mangaUrl/volume/$id"
        chapter_number = number.toFloat()
        name = buildString {
            append("Vol. $number")
            if (!this@VolumeDto.name.isNullOrBlank()) {
                append(" - ")
                append(this@VolumeDto.name)
            }
        }
        scanlator = "$chapterCount chapters"
    }
}

@Serializable
class ChapterDto(
    private val id: Int,
    private val number: Float,
    private val name: String? = null,
    private val createdAt: Long? = null,
    val type: String? = null,
) {
    fun toSChapter(mangaUrl: String, langCode: String): SChapter = SChapter.create().apply {
        url = "$mangaUrl/$id-chapter-${number.toString().removeSuffix(".0")}-$langCode"
        when {
            name.isNullOrBlank() -> number to "Ch. ${number.toString().removeSuffix(".0")}"
            else -> when (val extracted = chapterRegex.find(name)?.value?.toFloatOrNull()) {
                null -> number to "Ch. ${number.toString().removeSuffix(".0")} - $name"
                else -> extracted to name
            }
        }.let { (num, label) ->
            chapter_number = num
            this.name = label
        }
        scanlator = type ?: "Unknown"
        date_upload = createdAt?.times(1000L) ?: 0L
    }

    companion object {
        private val chapterRegex = """(?<=\b(?:ch(?:\.|apter)?|ep(?:\.|isode)?)\s?)\d+(?:\.\d+)?""".toRegex(RegexOption.IGNORE_CASE)
    }
}

@Serializable
class PagesResponse(val data: ChapterDataDto)

@Serializable
class ChapterDataDto(val pages: List<PageDto>)

@Serializable
class PageDto(val url: String)
