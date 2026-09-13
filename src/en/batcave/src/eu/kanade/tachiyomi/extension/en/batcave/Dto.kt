package eu.kanade.tachiyomi.extension.en.batcave

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChapterData(
    @SerialName("news_id") val comicId: Int,
    val chapters: List<ChapterItem> = emptyList(),
)

@Serializable
class ChapterItem(
    val id: Int,
    @SerialName("posi") val number: Float,
    val title: String,
    val date: String,
)

@Serializable
class ChapterRequest(
    @SerialName("news_id") val comicId: String,
    @SerialName("chapter_id") val chapterId: String,
)

@Serializable
class ReaderResponse(
    val data: ReaderData,
)

@Serializable
class ReaderData(
    val images: List<String> = emptyList(),
)

@Serializable
class XFilters(
    @SerialName("filter_items") val filterItems: XFilterItems,
)

@Serializable
class XFilterItems(
    @SerialName("p") val publisher: XFilterGroup = XFilterGroup(),
    @SerialName("g") val genre: XFilterGroup = XFilterGroup(),
)

@Serializable
class XFilterGroup(
    val values: List<XFilterValue> = emptyList(),
)

@Serializable
class XFilterValue(
    val id: Int,
    val value: String,
)
