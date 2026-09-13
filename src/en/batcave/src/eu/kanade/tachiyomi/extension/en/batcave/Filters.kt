package eu.kanade.tachiyomi.extension.en.batcave

import eu.kanade.tachiyomi.source.model.Filter
import java.util.Calendar

class CheckBoxItem(name: String, val value: Int) : Filter.CheckBox(name)

open class CheckBoxFilter(
    name: String,
    private val queryParameter: String,
    values: List<Pair<String, Int>>,
) : Filter.Group<CheckBoxItem>(
    name,
    values.map { CheckBoxItem(it.first, it.second) },
) {
    fun appendTo(parts: MutableList<String>) {
        val checked = state.filter { it.state }
        if (checked.isNotEmpty() && queryParameter.isNotBlank()) {
            parts += "$queryParameter=${checked.joinToString(",") { it.value.toString() }}/"
        }
    }
}

class PublisherFilter(values: List<Pair<String, Int>>) : CheckBoxFilter("Publisher", "p", values)

class GenreFilter(values: List<Pair<String, Int>>) : CheckBoxFilter("Genre", "g", values)

class TextBox(name: String) : Filter.Text(name)

class YearFilter : Filter.Group<TextBox>(
    "Year of Issue",
    listOf(TextBox("from"), TextBox("to")),
) {
    fun appendTo(parts: MutableList<String>) {
        val now = Calendar.getInstance().get(Calendar.YEAR)
        state[0].state.toIntOrNull()?.takeIf { it in 1929..now }?.let { parts += "y[from]=$it/" }
        state[1].state.toIntOrNull()?.takeIf { it in 1929..now }?.let { parts += "y[to]=$it/" }
    }
}

class SortFilter : Filter.Sort(
    "Sort",
    arrayOf("Date", "Date of change", "Rating", "Read", "Comments", "Title"),
    Selection(0, false),
) {
    private val keys = arrayOf("date", "editdate", "rating", "news_read", "comm_num", "title")
    fun key() = keys[state?.index ?: 0]
    fun direction() = if (state?.ascending == true) "asc" else "desc"
}
