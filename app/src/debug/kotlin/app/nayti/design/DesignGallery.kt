package app.nayti.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.theme.NaytiTheme
import app.nayti.ui.designsystem.theme.ThemeMode

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
fun LibraryDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockLibrary() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun LibraryLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockLibrary() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ResultsDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockLibrary(resultsMode = true) }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ResultsLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockLibrary(resultsMode = true) }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun PreparationDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockPreparationSheet() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun PreparationLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockPreparationSheet() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ViewerDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockViewer() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ViewerLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockViewer() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun SettingsDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockSettings() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun SettingsLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockSettings() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ChromeCompareDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockChromeComparison() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun ChromeCompareLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockChromeComparison() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun IconsDark() = NaytiTheme(themeMode = ThemeMode.Dark) { MockIconSheet() }

@Preview(widthDp = 384, heightDp = 832)
@Composable
fun IconsLight() = NaytiTheme(themeMode = ThemeMode.Light) { MockIconSheet() }

/** Solid chrome variant of the library, used to compare the fallback in place. */
@Preview(widthDp = 384, heightDp = 832)
@Composable
fun LibrarySolidDark() =
    NaytiTheme(themeMode = ThemeMode.Dark) { MockLibrary(material = ChromeMaterial.Solid) }
