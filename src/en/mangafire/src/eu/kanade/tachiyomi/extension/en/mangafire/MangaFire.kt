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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response

@Source
abstract class MangaFire : KeiSource(), ConfigurableSource {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
        addInterceptor(VrfSigner().interceptor())
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept", "application/json")
    }

    private val preferences = getPreferences()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder().apply {
            addQueryParameter("order[views_30d]", "desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            ContentRatingFilter(contentRating).addToUri(this)
        }.build()
        return client.get(url).use(::parseMangaList)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder().apply {
            addQueryParameter("order[chapter_updated_at]", "desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            ContentRatingFilter(contentRating).addToUri(this)
        }.build()
        return client.get(url).use(::parseMangaList)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val data = response.parseAs<ApiResponse<MangaDto>>()
        return MangasPage(data.items.map { it.toSManga() }, data.meta?.hasNext ?: false)
    }

    private val authorIdCache = object : LinkedHashMap<String, String?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 20
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val authorQuery = filters.firstInstanceOrNull<AuthorFilter>()?.state.orEmpty().trim()
        var authorId: String? = null

        if (authorQuery.isNotBlank()) {
            authorId = authorIdCache.getOrPut(authorQuery) {
                val url = "$baseUrl/api/tags".toHttpUrl().newBuilder()
                    .addQueryParameter("keyword", authorQuery)
                    .build()
                val tags = client.get(url).parseAs<TagResponse>()
                tags.data.firstOrNull { it.type == "author" || it.type == "artist" }?.id?.toString()
            }
            if (authorId == null) return MangasPage(emptyList(), false)
        }

        val url = "$baseUrl/api/titles".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("keyword", query.trim())
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "50")
            authorId?.let { addQueryParameter("authors[]", it) }
            filters.filterIsInstance<UriFilter>().forEach { it.addToUri(this) }
        }.build()

        return client.get(url).use(::parseMangaList)
    }

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.size < 2) return null
        return client.get("$baseUrl/api/titles/${getHid(url.pathSegments[1])}")
            .parseAs<MangaDetailsResponse>().data.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val hid = getHid(manga.url)
        val details = async {
            if (fetchDetails) client.get("$baseUrl/api/titles/$hid")
                .parseAs<MangaDetailsResponse>().data.toSManga() else manga
        }
        val chapterList = async {
            if (fetchChapters) fetchChapters(manga) else chapters
        }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchChapters(manga: SManga): List<SChapter> {
        val hid = getHid(manga.url)
        if (showAsVolumes) {
            val volumes = client.get("$baseUrl/api/titles/$hid/volumes")
                .parseAs<ApiResponse<VolumeDto>>().items
                .filter { it.language == lang }
            if (volumes.isNotEmpty()) return volumes.map { it.toSChapter(manga.url) }
        }
        return fetchAllChapters(hid).map { it.toSChapter(manga.url, lang) }
            .let { list ->
                if (!mergeChapters) list else list
                    .sortedWith(compareBy<SChapter> {
                        val official = it.scanlator?.equals("official", true) == true
                        if (preferOfficial) !official else official
                    })
                    .distinctBy { it.chapter_number }
                    .sortedByDescending { it.chapter_number }
            }
    }

    private suspend fun fetchAllChapters(hid: String): List<ChapterDto> = coroutineScope {
        val firstUrl = "$baseUrl/api/titles/$hid/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("language", lang)
            .addQueryParameter("sort", "number")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "200")
            .build()
        val first = client.get(firstUrl).parseAs<ApiResponse<ChapterDto>>()
        val lastPage = first.meta?.lastPage ?: 1
        val pages = (2..lastPage).map { page ->
            async {
                val url = "$baseUrl/api/titles/$hid/chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("language", lang)
                    .addQueryParameter("sort", "number")
                    .addQueryParameter("order", "desc")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("limit", "200")
                    .build()
                client.get(url).parseAs<ApiResponse<ChapterDto>>().items
            }
        }.awaitAll()
        buildList {
            addAll(first.items)
            pages.forEach(::addAll)
        }
    }

    override val supportRelatedMangasBySearch = true

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = (baseUrl + chapter.url).toHttpUrl().pathSegments
        val last = segments.last()
        val url = if (segments.contains("volume")) {
            "$baseUrl/api/volumes/$last"
        } else {
            val id = last.substringBefore("-").toLongOrNull() ?: error("Refresh manga")
            "$baseUrl/api/chapters/$id"
        }
        return client.get(url).parseAs<PagesResponse>().data.pages.mapIndexed { index, page ->
            Page(index, imageUrl = page.url)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        ContentRatingFilter(contentRating), Filter.Separator(), TypeFilter(), Filter.Separator(),
        GenreModeFilter(), GenreFilter(), Filter.Separator(), ThemeFilter(), Filter.Separator(),
        StatusFilter(), Filter.Separator(), AuthorFilter(), YearFromFilter(), YearToFilter(),
        MinChapterFilter(), Filter.Separator(), SortFilter(),
    )

    private val contentRating: Set<String>
        get() = preferences.getStringSet(CONTENT_RATING_PREF, emptySet()) ?: emptySet()
    private val showAsVolumes: Boolean
        get() = preferences.getBoolean(PREF_SHOW_AS_VOLUMES, false)
    private val mergeChapters: Boolean
        get() = preferences.getBoolean(PREF_MERGE_CHAPTERS, false)
    private val preferOfficial: Boolean
        get() = preferences.getBoolean(PREF_PREFER_OFFICIAL, true)

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
