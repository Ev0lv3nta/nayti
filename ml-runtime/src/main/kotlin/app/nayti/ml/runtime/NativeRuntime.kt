package app.nayti.ml.runtime

object NativeRuntime {
    init {
        System.loadLibrary("nayti_runtime")
    }

    external fun contractVersion(): Int
}
