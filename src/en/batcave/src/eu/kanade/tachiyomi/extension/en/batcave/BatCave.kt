package eu.kanade.tachiyomi.extension.en.batcave

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.FormBody
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter

@Source
abstract class BatCave : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(BatCaveGuard(baseUrl).interceptor())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/comix/" else "$baseUrl/comix/page/$page/"
        return parseCards(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return parseLatest(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val url = if (page == 1) "$baseUrl/search/$encoded/" else "$baseUrl/search/$encoded/page/$page/"
            return parseCards(client.get(url).asJsoup())
        }

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull() ?: SortFilter()
        val path = buildFilterPath(filters)
        val pagePath = if (page > 1) "page/$page/" else ""
        val url = "$baseUrl/ComicList/$path$pagePath"
        val body = FormBody.Builder()
            .add("dlenewssortby", sort.key())
            .add("dledirection", sort.direction())
            .build()
        return parseCards(client.post(url, body).asJsoup())
    }

    private fun buildFilterPath(filters: FilterList): String {
        val parts = mutableListOf<String>()
        filters.filterIsInstance<YearFilter>().firstOrNull()?.appendTo(parts)
        filters.filterIsInstance<PublisherFilter>().firstOrNull()?.appendTo(parts)
        filters.filterIsInstance<GenreFilter>().firstOrNull()?.appendTo(parts)
        return if (parts.isEmpty()) "y[from]=1929/y[to]=2099/" else parts.joinToString("")
    }

    private fun parseCards(doc: Document): MangasPage {
        val items = doc.select("#dle-content .readed, #content-load .readed").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val title = card.selectFirst(".readed__title, h2, h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = card.selectFirst("img[data-src], img[src]")?.let { image ->
                    image.absUrl(if (image.hasAttr("data-src")) "data-src" else "src")
                }
            }
        }.distinctBy { it.url }
        return MangasPage(items, hasNext(doc))
    }

    private fun parseLatest(doc: Document): MangasPage {
        val items = doc.select("#content-load .latest, .latest.grid-item").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val title = card.selectFirst(".latest__title, h2, h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = card.selectFirst("img[data-src], img[src]")?.let { image ->
                    image.absUrl(if (image.hasAttr("data-src")) "data-src" else "src")
                }
            }
        }.distinctBy { it.url }
        return MangasPage(items, hasNext(doc))
    }

    private fun hasNext(doc: Document): Boolean = doc.select(".pagination a[href], .pagination__pages a[href]").any {
        it.text().trim().equals("Next", true) || it.text().trim() == "›"
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? =
        parseDetails(client.get(url).asJsoup())

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = if (fetchDetails) parseDetails(doc) else null,
            chapters = if (fetchChapters) parseChapters(doc) else null,
        )
    }

    private fun parseDetails(doc: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("header.page__header h1, h1")?.text()?.trim().orEmpty()
        thumbnail_url = doc.selectFirst("div.page__poster img, .page__poster img")?.let { image ->
            image.absUrl(if (image.hasAttr("data-src")) "data-src" else "src")
        }
        author = info(doc, "Writer")
        artist = info(doc, "Artist")
        status = when (info(doc, "Release type")?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = doc.select("div.page__tags a[href]").joinToString { it.text().trim() }
        description = buildString {
            info(doc, "Publisher")?.let { append("Publisher: ").append(it).append('\n') }
            info(doc, "Year")?.let { append("Year: ").append(it).append('\n') }
            doc.selectFirst("div.page__text, .page__description")?.text()?.trim()?.let { append('\n').append(it) }
        }.trim()
    }

    private fun info(doc: Document, label: String): String? =
        doc.select(".page__list > li").firstOrNull { it.text().startsWith(label, true) }
            ?.selectFirst("a")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun parseChapters(doc: Document): List<SChapter> {
        val script = doc.select("script").firstOrNull { it.data().contains("chapters") && it.data().contains("news_id") }
            ?: return emptyList()
        val json = script.data()
            .substringAfter("window.__DATA__ =", "")
            .substringBeforeLast(';')
            .trim()
            .takeIf { it.startsWith("{") } ?: return emptyList()
        return runCatching { json.parseAs<ChapterData>().chapters.map { chapter ->
            SChapter.create().apply {
                url = "/reader/${chapterDataId(json)}/${chapter.id}"
                name = chapter.title
                chapter_number = chapter.number
                date_upload = DATE.tryParse(chapter.date)
            }
        } }.getOrDefault(emptyList())
    }

    private fun chapterDataId(json: String): Int =
        runCatching { json.parseAs<ChapterData>().comicId }.getOrDefault(0)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ids = chapter.url.substringAfter("/reader/").split('/')
        if (ids.size < 2) return emptyList()
        val response = client.post(
            "$baseUrl/engine/ajax/controller.php?mod=api&action=reader/getChapterData",
            ChapterRequest(ids[0], ids[1]).toJsonRequestBody(),
        )
        return response.parseAs<ReaderResponse>().data.images.mapIndexed { index, image ->
            Page(index, imageUrl = image.toAbsoluteUrl(baseUrl))
        }
    }

    private fun String.toAbsoluteUrl(base: String): String =
        when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> base + this
            else -> "$base/$this"
        }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData() =
        client.get("$baseUrl/comix/").asJsoup()
            .selectFirst("script:containsData(__XFILTER__)")?.data()
            ?.substringAfter("window.__XFILTER__ =", "")
            ?.substringBeforeLast(';')?.trim()
            ?.parseAs<XFilters>()?.filterItems ?: kotlinx.serialization.json.JsonNull

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList {
        val parsed = runCatching { data?.parseAs<XFilterItems>() }.getOrNull()
        val publishers = parsed?.publisher?.values?.map { it.value to it.id } ?: emptyList()
        val genres = parsed?.genre?.values?.map { it.value to it.id } ?: emptyList()
        return FilterList(
            SortFilter(),
            YearFilter(),
            PublisherFilter(publishers),
            GenreFilter(genres),
        )
    }

    private val DATE = DateTimeFormatter.ofPattern("d.M.yyyy")
}
