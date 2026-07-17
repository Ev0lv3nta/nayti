package app.nayti.search.engine

@JvmInline
value class SearchQuery private constructor(val value: String) {
    companion object {
        fun parse(raw: String): SearchQuery? =
            raw.trim().takeIf(String::isNotEmpty)?.let(::SearchQuery)
    }
}
