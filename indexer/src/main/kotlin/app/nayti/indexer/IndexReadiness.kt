package app.nayti.indexer

data class IndexReadiness(
    val discovered: Int,
    val searchable: Int,
    val failed: Int,
    val isRunning: Boolean,
) {
    init {
        require(discovered >= 0)
        require(searchable in 0..discovered)
        require(failed in 0..discovered)
        require(searchable + failed <= discovered)
    }

    val pending: Int = discovered - searchable - failed

    companion object {
        val Empty = IndexReadiness(
            discovered = 0,
            searchable = 0,
            failed = 0,
            isRunning = false,
        )
    }
}
