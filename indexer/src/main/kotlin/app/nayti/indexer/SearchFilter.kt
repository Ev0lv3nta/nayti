package app.nayti.indexer

/** Exact catalog constraints applied before each retriever selects its top candidates. */
data class SearchFilter(
    val takenFromMillis: Long? = null,
    val takenBeforeMillis: Long? = null,
    val bucketId: Long? = null,
    val mimeType: String? = null,
) {
    init {
        require(takenFromMillis == null || takenFromMillis >= 0)
        require(takenBeforeMillis == null || takenBeforeMillis >= 0)
        require(takenFromMillis == null || takenBeforeMillis == null || takenFromMillis < takenBeforeMillis)
        require(bucketId == null || bucketId >= 0)
        require(mimeType == null || MimeType.matches(mimeType))
    }

    val isEmpty: Boolean
        get() = takenFromMillis == null && takenBeforeMillis == null && bucketId == null && mimeType == null

    fun constrainedFrom(scopeTakenFromMillis: Long?): SearchFilter {
        if (scopeTakenFromMillis == null) return this
        val effectiveFrom = maxOf(takenFromMillis ?: 0, scopeTakenFromMillis)
        require(takenBeforeMillis == null || effectiveFrom < takenBeforeMillis) {
            "Search filter does not overlap the active indexing scope"
        }
        return copy(takenFromMillis = effectiveFrom)
    }

    companion object {
        val None = SearchFilter()

        private val MimeType = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
    }
}
