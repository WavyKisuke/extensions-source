package eu.kanade.tachiyomi.extension.en.batcave

import eu.kanade.tachiyomi.source.model.Filter
import java.util.Calendar

class YearFilter : Filter.Group<Filter.Text>("Year", listOf(Filter.Text("From"), Filter.Text("To"))) {
    fun appendTo(parts: MutableList<String>) {
        val now = Calendar.getInstance().get(Calendar.YEAR)
        state[0].state.toIntOrNull()?.takeIf { it in 1929..now }?.let { parts += "y[from]=$it/" }
        state[1].state.toIntOrNull()?.takeIf { it in 1929..now }?.let { parts += "y[to]=$it/" }
    }
}

class PublisherFilter(values: List<Pair<String, Int>>) :
    Filter.Group<Filter.CheckBox>("Publisher", values.map { Filter.CheckBox(it.first) }) {
    private val ids = values.map { it.second }
    fun appendTo(parts: MutableList<String>) {
        val selected = state.mapIndexedNotNull { index, item -> ids.getOrNull(index)?.takeIf { item.state } }
        if (selected.isNotEmpty()) parts += "p=${selected.joinToString(",")}/"
    }
}

class GenreFilter(values: List<Pair<String, Int>>) :
    Filter.Group<Filter.CheckBox>("Genre", values.map { Filter.CheckBox(it.first) }) {
    private val ids = values.map { it.second }
    fun appendTo(parts: MutableList<String>) {
        val selected = state.mapIndexedNotNull { index, item -> ids.getOrNull(index)?.takeIf { item.state } }
        if (selected.isNotEmpty()) parts += "g=${selected.joinToString(",")}/"
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
