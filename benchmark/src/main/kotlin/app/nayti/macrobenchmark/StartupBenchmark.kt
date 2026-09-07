package app.nayti.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the isolated minified app. Never targets the owner's app.nayti installation. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = startup(StartupMode.COLD, 10)
    @Test fun warmStartup() = startup(StartupMode.WARM, 30)

    private fun startup(mode: StartupMode, repetitions: Int) = benchmark.measureRepeated(
        packageName = "app.nayti.benchmark",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = mode,
        iterations = repetitions,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
