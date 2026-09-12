package eu.kanade.tachiyomi.extension.en.mangafire

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.Locale

@Source
abstract class MangaFire : KeiSource() {
    private val api = "$baseUrl/api"

    override suspend fun getPopularManga(page: Int): MangasPage = titles(page, "views_7d")
    override suspend fun getLatestUpdates(page: Int): MangasPage = titles(page, "chapter_updated_at")

    private suspend fun titles(page: Int, order: String, query: String = ""): MangasPage {
        val url = "$api/titles".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "30")
            .addQueryParameter("language", lang)
            .addQueryParameter("order[$order]", "desc")
            .apply { if (query.isNotBlank()) addQueryParameter("keyword", query) }
            .build()
        return client.get(url).use { response ->
            val root = JSONObject(response.body.string())
            val items = root.optJSONArray("items") ?: return@use MangasPage(emptyList(), false)
            val mangas = (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                SManga.create().apply {
                    title = item.optString("title")
                    url = normalizeUrl(item.optString("url"))
                    thumbnail_url = item.optJSONObject("poster")?.optString("medium")
                }
            }
            MangasPage(mangas, root.optJSONObject("meta")?.optBoolean("hasNext") == true)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        titles(page, "relevance", query)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? =
        manga(extractId(url.encodedPath))?.first

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = extractId(manga.url) ?: return SMangaUpdate(manga, chapters)
        val result = manga(id) ?: return SMangaUpdate(manga, chapters)
        return SMangaUpdate(
            manga = if (fetchDetails) result.first else manga,
            chapters = if (fetchChapters) result.second else chapters,
        )
    }

    private suspend fun manga(id: String?): Pair<SManga, List<SChapter>>? {
        if (id.isNullOrBlank()) return null
        val response = client.get("$api/titles/$id")
        val root = response.use { JSONObject(it.body.string()) }
        val data = root.optJSONObject("data") ?: root
        val manga = SManga.create().apply {
            title = data.optString("title")
            url = "/title/$id"
            thumbnail_url = data.optJSONObject("poster")?.optString("large")
            description = data.optString("synopsisHtml").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            author = names(data.optJSONArray("authors"))
            artist = names(data.optJSONArray("artists"))
            genre = names(data.optJSONArray("genres"))
            status = when (data.optString("status").lowercase(Locale.ROOT)) {
                "finished", "completed" -> SManga.COMPLETED
                "on_hiatus" -> SManga.ON_HIATUS
                "discontinued" -> SManga.CANCELLED
                "releasing" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
        return manga to chapters(id)
    }

    private suspend fun chapters(id: String): List<SChapter> {
        val result = mutableListOf<SChapter>()
        var page = 1
        do {
            val url = "$api/titles/$id/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("language", lang)
                .addQueryParameter("sort", "number")
                .addQueryParameter("order", "desc")
                .addQueryParameter("limit", "200")
                .addQueryParameter("page", page.toString())
                .build()
            val response = client.get(url)
            val (items, next) = response.use {
                val root = JSONObject(it.body.string())
                val array = root.optJSONArray("items") ?: return@use emptyList<JSONObject>() to false
                (0 until array.length()).mapNotNull { i -> array.optJSONObject(i) } to
                    (root.optJSONObject("meta")?.optBoolean("hasNext") == true)
            }
            items.forEach { item ->
                val chapterId = item.optString("id").takeIf(String::isNotBlank) ?: return@forEach
                val number = item.optDouble("number", 0.0)
                val numberText = number.toString().removeSuffix(".0")
                val webUrl = item.optString("url").removePrefix(baseUrl)
                result += SChapter.create().apply {
                    url = "$chapterId|$webUrl"
                    name = listOfNotNull("Chapter $numberText".takeIf { number != 0.0 }, item.optString("name").takeIf(String::isNotBlank)).joinToString(": ")
                    chapter_number = number.toFloat()
                    date_upload = item.optLong("createdAt").let { if (it > 0) it * 1000 else 0 }
                    scanlator = item.optString("type").takeIf { it == "official" }
                }
            }
            page++
            if (!next) break
        } while (true)
        return result
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.substringBefore('|')
        if (id.isBlank()) return emptyList()
        return client.get("$api/chapters/$id").use { response ->
            val root = JSONObject(response.body.string())
            val pages = (root.optJSONObject("data") ?: root).optJSONArray("pages") ?: return@use emptyList()
            (0 until pages.length()).mapNotNull { i ->
                val url = pages.optJSONObject(i)?.optString("url")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                Page(i, imageUrl = url)
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val url = chapter.url.substringAfter('|', "")
        return if (url.startsWith("http")) url else "$baseUrl$url"
    }

    private fun normalizeUrl(url: String): String = when {
        url.startsWith("http") -> url.removePrefix(baseUrl)
        url.startsWith("/") -> url
        else -> "/$url"
    }

    private fun extractId(url: String): String? = url.substringAfterLast('/').substringBefore('-').takeIf(String::isNotBlank)

    private fun names(array: org.json.JSONArray?): String =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it)?.optString("title")?.takeIf(String::isNotBlank) }.joinToString()
}
