package eu.kanade.tachiyomi.extension.en.mangafire

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable

@Source
abstract class MangaFire :
    HttpSource(),
    ConfigurableSource {

    private val langCode = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json")

    private val preferences = getPreferences()

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder()
            .addQueryParameter("order[views_30d]", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "50")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<MangaDto>>()
        return MangasPage(data.items.map { it.toSManga() }, data.meta?.hasNext ?: false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder()
            .addQueryParameter("order[chapter_updated_at]", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "50")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.defer {
            val authorQuery = filters.firstInstanceOrNull<AuthorFilter>()?.state.orEmpty()
            var authorId: String? = null

            if (authorQuery.isNotBlank()) {
                val tagReq = GET("$baseUrl/api/tags?keyword=$authorQuery", headers)
                val tagRes = client.newCall(tagReq).execute()
                val tags = tagRes.parseAs<TagResponse>()
                authorId = tags.data.firstOrNull { it.type == "author" || it.type == "artist" }?.id?.toString()

                if (authorId == null) {
                    return@defer Observable.just(MangasPage(emptyList(), false))
                }
            }

            val req = searchMangaRequest(page, query, filters, authorId)
            val res = client.newCall(req).execute()
            Observable.just(searchMangaParse(res))
        }
    }

    private fun searchMangaRequest(page: Int, query: String, filters: FilterList, authorId: String?): Request {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            authorId?.let { addQueryParameter("authors[]", it) }
            (filters.ifEmpty { getFilterList() })
                .filterIsInstance<UriFilter>()
                .forEach { it.addToUri(this) }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        searchMangaRequest(page, query, filters, null)

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val hid = getHid(manga.url)
        return GET("$baseUrl/api/titles/$hid", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseAs<MangaDetailsResponse>().data.toSManga()

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val hid = getHid(manga.url)
        var page = 1
        var lastPage: Int
        var displayVolumes = showAsVolumes
        val chapters = mutableListOf<SChapter>()

        if (displayVolumes) {
            val volumes = client.newCall(GET("$baseUrl/api/titles/$hid/volumes", headers))
                .execute()
                .parseAs<ApiResponse<VolumeDto>>()
                .items
                .filter { it.language == langCode }

            if (volumes.isNotEmpty()) {
                volumes.forEach { chapters.add(it.toSChapter(manga.url)) }
                displayVolumes = true
            } else {
                displayVolumes = false
            }
        }

        if (!displayVolumes) {
            do {
                val url = "$baseUrl/api/titles/$hid/chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("language", langCode)
                    .addQueryParameter("sort", "number")
                    .addQueryParameter("order", "desc")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("limit", "200")
                    .build()

                val data = client.newCall(GET(url, headers)).execute()
                    .parseAs<ApiResponse<ChapterDto>>()

                data.items.forEach { chapters.add(it.toSChapter(manga.url, langCode)) }
                lastPage = data.meta?.lastPage ?: 1
                page++
            } while (page <= lastPage)
        }

        chapters
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = (baseUrl + chapter.url).toHttpUrl().pathSegments
        val last = segments.last()
        val url = if (segments.contains("volume")) {
            "$baseUrl/api/volumes/$last"
        } else {
            "$baseUrl/api/chapters/${last.substringBefore("-")}"
        }
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PagesResponse>()
        return data.data.pages.mapIndexed { index, page -> Page(index, imageUrl = page.url) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        TypeFilter(),
        Filter.Separator(),
        GenreModeFilter(),
        GenreFilter(),
        Filter.Separator(),
        ThemeFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        AuthorFilter(),
        YearFromFilter(),
        YearToFilter(),
        MinChapterFilter(),
        Filter.Separator(),
        SortFilter(),
    )

    private fun getHid(url: String): String {
        val lastPart = url.removeSuffix("/").substringAfterLast("/")
        return when {
            lastPart.contains('.') -> lastPart.substringAfterLast('.')
            lastPart.contains('-') -> lastPart.substringBefore('-')
            else -> lastPart
        }
    }

    private val showAsVolumes: Boolean
        get() = preferences.getBoolean(PREF_SHOW_AS_VOLUMES, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_AS_VOLUMES
            title = "Prefer Volume Release"
            summary = SUMMARY_MSG
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_AS_VOLUMES = "show_as_volumes"
        private const val SUMMARY_MSG = "Requires Chapter List Refresh to Apply"
    }
}
