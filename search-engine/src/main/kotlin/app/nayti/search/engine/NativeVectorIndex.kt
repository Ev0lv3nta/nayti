package app.nayti.search.engine

object NativeVectorIndex {
    init {
        System.loadLibrary("nayti_search")
    }

    external fun contractVersion(): Int

    external fun optimizedDotMatchesScalar(seed: Int, cases: Int): Boolean

    external fun mappedRecordCount(path: String, expectedLength: Long, expectedSha256: ByteArray): Int
}
