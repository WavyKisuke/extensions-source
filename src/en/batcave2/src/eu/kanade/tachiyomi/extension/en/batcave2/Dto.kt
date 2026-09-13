package eu.kanade.tachiyomi.extension.en.batcave2

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
