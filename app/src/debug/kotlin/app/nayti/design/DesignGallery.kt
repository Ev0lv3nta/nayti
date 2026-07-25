package app.nayti.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.theme.NaytiTheme

/**
 * Entry points of the visual direction review.
 *
 * Each function renders one surface of the proposal in one theme. They are debug-only and are not
 * reachable from the product: the shipped navigation is untouched by this stage. On a device they
 * are rendered through the Compose tooling preview activity, so no extra component is added to the
 * manifest.
 */
@Preview(widthDp = 384, heightDp = 832)
@Composable
fun LibraryDark() = NaytiTheme(darkTheme = true) { MockLibrary() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun LibraryLight() = NaytiTheme(darkTheme = false) { MockLibrary() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ResultsDark() = NaytiTheme(darkTheme = true) { MockLibrary(resultsMode = true) }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ResultsLight() = NaytiTheme(darkTheme = false) { MockLibrary(resultsMode = true) }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun PreparationDark() = NaytiTheme(darkTheme = true) { MockPreparationSheet() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun PreparationLight() = NaytiTheme(darkTheme = false) { MockPreparationSheet() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ViewerDark() = NaytiTheme(darkTheme = true) { MockViewer() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ViewerLight() = NaytiTheme(darkTheme = false) { MockViewer() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun SettingsDark() = NaytiTheme(darkTheme = true) { MockSettings() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun SettingsLight() = NaytiTheme(darkTheme = false) { MockSettings() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ChromeCompareDark() = NaytiTheme(darkTheme = true) { MockChromeComparison() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ChromeCompareLight() = NaytiTheme(darkTheme = false) { MockChromeComparison() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun IconsDark() = NaytiTheme(darkTheme = true) { MockIconSheet() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun IconsLight() = NaytiTheme(darkTheme = false) { MockIconSheet() }

/** Solid chrome variant of the library, used to compare the fallback in place. */
@Preview(widthDp = 384, heightDp = 832)
@Composable
fun LibrarySolidDark() = NaytiTheme(darkTheme = true) { MockLibrary(material = ChromeMaterial.Solid) }
