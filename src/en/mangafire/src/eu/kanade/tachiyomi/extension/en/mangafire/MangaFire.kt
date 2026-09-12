package eu.kanade.tachiyomi.extension.en.mangafire

import androidx.preference.MultiSelectListPreference
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Source
abstract class MangaFire : HttpSource(), ConfigurableSource {
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(VrfSigner().interceptor())
        .build()

    override val headers = headersBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")
        .build()

    private val preferences = getPreferences()

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request =
        apiTitlesRequest(page) {
            addQueryParameter("order[views_30d]", "desc")
        }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // =============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request =
        apiTitlesRequest(page) {
            addQueryParameter("order[chapter_updated_at]", "desc")
        }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // =============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val authorQuery = filters.firstInstanceOrNull<AuthorFilter>()?.state.orEmpty().trim()
        val authorId = findAuthorId(authorQuery)

        return apiTitlesRequest(page) {
            if (query.isNotBlank()) addQueryParameter("keyword", query.trim())
            if (authorQuery.isNotBlank() && authorId == null) return@apiTitlesRequest
            authorId?.let { addQueryParameter("authors[]", it) }
            filters.filterIsInstance<UriFilter>().forEach { it.addToUri(this) }
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    private fun findAuthorId(query: String): String? {
        if (query.isBlank()) return null
        val request = GET(
            "$baseUrl/api/tags".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .build(),
            headers,
        )
        return client.newCall(request).execute().use { response ->
            response.parseAs<TagResponse>().data
                .firstOrNull { it.type == "author" || it.type == "artist" }
                ?.id
                ?.toString()
        }
    }

    private fun apiTitlesRequest(page: Int, configure: HttpUrl.Builder.() -> Unit): Request {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            configure()
        }.build()
        return GET(url, headers)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<MangaDto>>()
        return MangasPage(
            data.items.map { it.toSManga() },
            data.meta?.hasNext ?: false,
        )
    }

    // =============================== Details =============================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/api/titles/${getHid(manga.url)}", headers)

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseAs<MangaDetailsResponse>().data.toSManga()

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request =
        chapterListRequestPaginated(getHid(manga.url), 1)

    private fun chapterListRequestPaginated(hid: String, page: Int): Request =
        GET(
            "$baseUrl/api/titles/$hid/chapters".toHttpUrl().newBuilder().apply {
                addQueryParameter("language", lang)
                addQueryParameter("sort", "number")
                addQueryParameter("order", "desc")
                addQueryParameter("page", page.toString())
                addQueryParameter("limit", "200")
            }.build(),
            headers,
        )

    override fun chapterListParse(response: Response): List<SChapter> {
        val hid = response.request.url.pathSegments
            .dropWhile { it != "titles" }
            .getOrNull(1)
            ?: return emptyList()

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var data = response.parseAs<ApiResponse<ChapterDto>>()

        while (true) {
            chapters += data.items.map { it.toSChapter("/title/$hid", lang) }
            if (data.meta?.hasNext != true) break
            page++
            data = client.newCall(chapterListRequestPaginated(hid, page)).execute().use {
                it.parseAs<ApiResponse<ChapterDto>>()
            }
        }

        return if (mergeChapters) {
            chapters.sortedWith(
                compareBy<SChapter> {
                    val official = it.scanlator?.equals("official", ignoreCase = true) == true
                    if (preferOfficial) !official else official
                }.thenByDescending { it.chapter_number },
            ).distinctBy { it.chapter_number }
        } else {
            chapters
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
            .substringAfterLast("/")
            .substringBefore('-')
            .toLongOrNull()
            ?: error("Invalid MangaFire chapter URL")
        return GET("$baseUrl/api/chapters/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> =
        response.parseAs<PagesResponse>().data.pages.mapIndexed { index, page ->
            Page(index, imageUrl = page.url)
        }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        ContentRatingFilter(contentRating),
        Filter.Separator(),
        TypeFilter(),
        Filter.Separator(),
        GenreFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        AuthorFilter(),
        MinChapterFilter(),
        Filter.Separator(),
        SortFilter(),
    )

    // ============================= Preferences ===========================

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
                summary = (values as Set<String>).joinToString {
                    it.replaceFirstChar(Char::uppercase)
                }
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MERGE_CHAPTERS
            title = "Merge duplicate chapters"
            summary = SUMMARY_MSG
            setDefaultValue(false)
        }.also(screen::addPreference)

        val official = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PREFER_OFFICIAL
            title = "Prefer official chapters"
            setDefaultValue(true)
            isEnabled = mergeChapters
        }.also(screen::addPreference)

        screen.findPreference<SwitchPreferenceCompat>(PREF_MERGE_CHAPTERS)
            ?.setOnPreferenceChangeListener { _, value ->
                official.isEnabled = value as Boolean
                true
            }
    }

    private val contentRating: Set<String>
        get() = preferences.getStringSet(CONTENT_RATING_PREF, emptySet()) ?: emptySet()

    private val mergeChapters: Boolean
        get() = preferences.getBoolean(PREF_MERGE_CHAPTERS, false)

    private val preferOfficial: Boolean
        get() = preferences.getBoolean(PREF_PREFER_OFFICIAL, true)

    private fun getHid(url: String): String {
        val last = url.removeSuffix("/").substringAfterLast("/")
        return when {
            last.contains('-') -> last.substringBefore('-')
            last.contains('.') -> last.substringAfterLast('.')
            else -> last
        }
    }

    companion object {
        private const val CONTENT_RATING_PREF = "pref_content_rating"
        private const val PREF_MERGE_CHAPTERS = "merge_chapters"
        private const val PREF_PREFER_OFFICIAL = "prefer_official"
        private const val SUMMARY_MSG = "Requires Chapter List Refresh to Apply"
    }
}
