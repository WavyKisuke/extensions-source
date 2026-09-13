package eu.kanade.tachiyomi.extension.en.batcave2

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class BatCave2 : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
        addInterceptor(BatCave2Guard(baseUrl).interceptor())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/comix/".toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
        }.build()
        return client.get(url).use { response -> parseList(response.asJsoup()) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
        }.build()
        return client.get(url).use { response -> parseList(response.asJsoup()) }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$baseUrl/search/$encoded/".toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
        }.build()
        return client.get(url).use { response -> parseList(response.asJsoup()) }
    }

    private fun parseList(doc: Document): MangasPage {
        val items = doc.select(
            "#dle-content .readed, #content-load .readed, " +
                ".latest.grid-item, #content-load .latest",
        ).mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val title = card.selectFirst(
                ".readed__title, .latest__title, h2, h3",
            )?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = card.selectFirst("img[data-src], img[src]")?.let { image ->
                    image.absUrl(if (image.hasAttr("data-src")) "data-src" else "src")
                }
            }
        }.distinctBy { it.url }

        return MangasPage(items, hasNextPage(doc))
    }

    private fun hasNextPage(doc: Document): Boolean = doc.select(
        ".pagination a[href], .pagination__pages a[href]",
    ).any {
        it.text().trim().equals("Next", true) || it.text().trim() == "›"
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        return client.get(url).use { response -> parseDetails(response.asJsoup()) }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Keep detail and chapter refreshes independent so a failed HTML parse
        // does not cancel the other half of the update.
        val detailsDeferred = async {
            if (fetchDetails) {
                runCatching {
                    client.get(getMangaUrl(manga)).use { response ->
                        parseDetails(response.asJsoup())
                    }
                }.getOrElse { manga }
            } else {
                manga
            }
        }

        val chaptersDeferred = async {
            if (fetchChapters) {
                runCatching {
                    client.get(getMangaUrl(manga)).use { response ->
                        parseChapters(response.asJsoup())
                    }
                }.getOrElse { chapters }
            } else {
                chapters
            }
        }

        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private fun parseDetails(doc: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst("header.page__header h1, h1")?.text()?.trim().orEmpty()
        thumbnail_url = doc.selectFirst(".page__poster img, .page__poster [data-src]")?.let { image ->
            image.absUrl(if (image.hasAttr("data-src")) "data-src" else "src")
        }
        author = infoValue(doc, "Writer")
        artist = infoValue(doc, "Artist")
        status = when (infoValue(doc, "Release type")?.lowercase(Locale.US)) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = doc.select(".page__tags a[href]").joinToString { it.text().trim() }
        description = buildString {
            infoValue(doc, "Publisher")?.let { append("Publisher: ").append(it).append('\n') }
            infoValue(doc, "Year")?.let { append("Year: ").append(it).append('\n') }
            doc.selectFirst(".page__text, .page__description")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { append('\n').append(it) }
        }.trim()
    }

    private fun infoValue(doc: Document, label: String): String? =
        doc.select(".page__list > li")
            .firstOrNull { it.text().trim().startsWith(label, true) }
            ?.selectFirst("a")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun parseChapters(doc: Document): List<SChapter> {
        val script = doc.select("script").firstOrNull {
            it.data().contains("window.__DATA__") && it.data().contains("chapters")
        } ?: return emptyList()

        val raw = script.data()
            .substringAfter("window.__DATA__ =", "")
            .substringBeforeLast(';')
            .trim()
            .takeIf { it.startsWith("{") } ?: return emptyList()

        val data = runCatching { raw.parseAs<ChapterData>() }.getOrNull() ?: return emptyList()

        return data.chapters.map { chapter ->
            SChapter.create().apply {
                url = "/reader/${data.comicId}/${chapter.id}"
                name = chapter.title
                chapter_number = chapter.number
                date_upload = runCatching {
                    LocalDate.parse(chapter.date, DATE_FORMAT)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }.getOrDefault(0L)
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.removeSuffix("/").substringAfter("/reader/").split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return emptyList()

        val response = client.post(
            "$baseUrl/engine/ajax/controller.php?mod=api&action=reader/getChapterData",
            ChapterRequest(parts[0], parts[1]).toJsonRequestBody(),
        )

        return runCatching { response.parseAs<ReaderResponse>().data.images }
            .getOrDefault(emptyList())
            .mapIndexed { index, image ->
                Page(index, imageUrl = image.toAbsoluteUrl(baseUrl))
            }
    }

    private fun String.toAbsoluteUrl(base: String): String = when {
        startsWith("https://") || startsWith("http://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> base + this
        else -> "$base/$this"
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")
    }
}

// Publisher trigger: keep source behavior unchanged while forcing a push event.
