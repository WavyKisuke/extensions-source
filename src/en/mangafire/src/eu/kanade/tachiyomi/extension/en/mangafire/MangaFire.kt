package eu.kanade.tachiyomi.extension.en.mangafire

import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import rx.Observable

@Source
abstract class MangaFire : HttpSource(), ConfigurableSource {

    override val client = super.client.newBuilder()
        .addInterceptor(VrfSigner().interceptor())
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")

    private val preferences = getPreferences()

    override fun popularMangaRequest(page: Int): Request = GET(buildTitlesUrl(
        page = page,
        orderKey = "views_30d",
        orderValue = "desc",
    ), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(buildTitlesUrl(
        page = page,
        orderKey = "chapter_updated_at",
        orderValue = "desc",
    ), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/titles")
            if (query.isNotBlank()) addQueryParameter("keyword", query.trim())
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            ContentRatingFilter(contentRating).addToUri(this)
            filters.filterIsInstance<UriFilter>().forEach { it.addToUri(this) }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            return Observable.fromCallable {
                val manga = SManga.create().apply { url = query.removePrefix(baseUrl) }
                client.newCall(mangaDetailsRequest(manga)).execute().use(::mangaDetailsParse)
            }.map { MangasPage(listOf(it), false) }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(
        "$baseUrl/api/titles/${getHid(manga.url)}",
        headers,
    )

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseAs<MangaDetailsResponse>().data.toSManga()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        fetchChaptersSync(manga)
    }

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl/api/titles/${getHid(manga.url)}/chapters?language=$lang", headers)

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("MangaFire uses custom paginated chapter fetching")

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = chapter.url.trim('/').split('/')
        val last = segments.lastOrNull() ?: return GET(baseUrl, headers)
        val url = if (segments.contains("volume")) {
            "$baseUrl/api/volumes/$last"
        } else {
            val chapterId = last.substringBefore('-').toLongOrNull() ?: error("Refresh manga")
            "$baseUrl/api/chapters/$chapterId"
        }
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> =
        response.parseAs<PagesResponse>().data.pages.mapIndexed { index, page ->
            Page(index, imageUrl = page.url)
        }

    override fun imageUrlRequest(page: Page): Request = GET(page.url!!, headers)

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun getFilterList(): FilterList = FilterList(
        ContentRatingFilter(contentRating), Filter.Separator(), TypeFilter(), Filter.Separator(),
        GenreFilter(), Filter.Separator(), StatusFilter(), Filter.Separator(), AuthorFilter(),
        MinChapterFilter(), Filter.Separator(), SortFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        MultiSelectListPreference(screen.context).apply {
            key = CONTENT_RATING_PREF
            title = "Content Rating"
            entries = CONTENT_RATINGS.map { it.replaceFirstChar(Char::uppercase) }.toTypedArray()
            entryValues = CONTENT_RATINGS.toTypedArray()
            summary = contentRating.joinToString { it.replaceFirstChar(Char::uppercase) }
            setDefaultValue(emptySet<String>())
            setOnPreferenceChangeListener { _, values ->
                @Suppress("UNCHECKED_CAST")
                summary = (values as Set<String>).joinToString { it.replaceFirstChar(Char::uppercase) }
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_AS_VOLUMES
            title = "Prefer Volume Release"
            summary = SUMMARY_MSG
            setDefaultValue(false)
        }.also(screen::addPreference)

        val merge = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MERGE_CHAPTERS
            title = "Merge duplicate chapters"
            summary = SUMMARY_MSG
            setDefaultValue(false)
        }.also(screen::addPreference)

        val official = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PREFER_OFFICIAL
            title = "Prefer official chapters"
            setDefaultValue(true)
            setEnabled(mergeChapters)
        }.also(screen::addPreference)

        merge.setOnPreferenceChangeListener { _, value ->
            official.setEnabled(value as Boolean)
            true
        }
    }

    private fun parseMangaList(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<MangaDto>>()
        return MangasPage(data.items.map { it.toSManga() }, data.meta?.hasNext ?: false)
    }

    private fun buildTitlesUrl(page: Int, orderKey: String, orderValue: String): String =
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/titles")
            addQueryParameter("order[$orderKey]", orderValue)
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            ContentRatingFilter(contentRating).addToUri(this)
        }.build().toString()

    private fun fetchChaptersSync(manga: SManga): List<SChapter> {
        val hid = getHid(manga.url)
        if (showAsVolumes) {
            val volumes = client.newCall(GET("$baseUrl/api/titles/$hid/volumes", headers)).execute().use {
                it.parseAs<ApiResponse<VolumeDto>>().items.filter { volume -> volume.language == lang }
            }
            if (volumes.isNotEmpty()) return volumes.map { it.toSChapter(manga.url) }
        }

        val result = mutableListOf<ChapterDto>()
        var page = 1
        var lastPage = 1
        do {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegments("api/titles/$hid/chapters")
                addQueryParameter("language", lang)
                addQueryParameter("sort", "number")
                addQueryParameter("order", "desc")
                addQueryParameter("page", page.toString())
                addQueryParameter("limit", "200")
            }.build()
            val data = client.newCall(GET(url, headers)).execute().use {
                it.parseAs<ApiResponse<ChapterDto>>()
            }
            result += data.items
            lastPage = data.meta?.lastPage ?: 1
            page++
        } while (page <= lastPage)

        val chapters = result.map { it.toSChapter(manga.url, lang) }
        return if (mergeChapters) {
            chapters.sortedWith(compareBy<SChapter> {
                val official = it.scanlator?.equals("official", ignoreCase = true) == true
                if (preferOfficial) !official else official
            }.thenByDescending { it.chapter_number }).distinctBy { it.chapter_number }
        } else chapters
    }

    private val contentRating: Set<String>
        get() = preferences.getStringSet(CONTENT_RATING_PREF, emptySet()) ?: emptySet()

    private val showAsVolumes: Boolean
        get() = preferences.getBoolean(PREF_SHOW_AS_VOLUMES, false)

    private val mergeChapters: Boolean
        get() = preferences.getBoolean(PREF_MERGE_CHAPTERS, false)

    private val preferOfficial: Boolean
        get() = preferences.getBoolean(PREF_PREFER_OFFICIAL, true)

    private fun getHid(url: String): String {
        val last = url.removeSuffix("/").substringAfterLast("/")
        return when {
            last.contains('.') -> last.substringAfterLast('.')
            last.contains('-') -> last.substringBefore('-')
            else -> last
        }
    }

    companion object {
        private const val CONTENT_RATING_PREF = "pref_content_rating"
        private const val PREF_SHOW_AS_VOLUMES = "show_as_volumes"
        private const val PREF_MERGE_CHAPTERS = "merge_chapters"
        private const val PREF_PREFER_OFFICIAL = "prefer_official"
        private const val SUMMARY_MSG = "Requires Chapter List Refresh to Apply"
    }
}
