package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal class TextFilter(name: String, val tag: String) : Filter.Text(name)

internal class SortFilter :
    Filter.Select<String>(
        "Sort By",
        arrayOf("Popular: All Time", "Popular: Month", "Popular: Week", "Popular: Today", "Recent"),
    ) {
    fun getValue() = when (state) {
        1 -> "popular-month"
        2 -> "popular-week"
        3 -> "popular-today"
        4 -> "date"
        else -> "popular"
    }
}

internal class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

internal class OffsetPageFilter : Filter.Text("Offset results by # pages")

internal open class TagTriState(name: String) : Filter.TriState(name)

internal class CategoryFilter :
    Filter.Group<TagTriState>(
        "Categories",
        listOf(
            "Doujinshi", "Manga", "Image Set", "Artist CG",
            "Game CG", "Western", "Non-H", "Cosplay", "Misc",
        ).map { TagTriState(it) },
    )

internal fun getFilters(): FilterList = FilterList(
    Filter.Header("Separate tags with commas (,)"),
    Filter.Header("Prepend with dash (-) to exclude"),
    TextFilter("Tags", "tag"),
    TextFilter("Female Tags", "female"),
    TextFilter("Male Tags", "male"),
    TextFilter("Artists", "artist"),
    TextFilter("Groups", "group"),
    TextFilter("Parodies", "parody"),
    TextFilter("Characters", "character"),
    CategoryFilter(),
    Filter.Separator(),
    Filter.Header("Uploaded valid units are h, d, w, m, y. Example: (>20d)"),
    TextFilter("Uploaded", "uploaded"),
    Filter.Header("Filter by pages, for example: (>20)"),
    TextFilter("Pages", "pages"),
    Filter.Separator(),
    SortFilter(),
    OffsetPageFilter(),
    Filter.Header("Sort is ignored if favorites only"),
    FavoriteFilter(),
)
