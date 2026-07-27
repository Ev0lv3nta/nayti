package app.nayti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.nayti.ui.NaytiApp
import app.nayti.ui.designsystem.theme.NaytiTheme
import app.nayti.ui.designsystem.theme.ThemeMode
import app.nayti.indexer.CatalogRuntime
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var catalogRuntime: CatalogRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Nayti)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearancePreferences =
                remember {
                    getSharedPreferences(AppearancePreferencesName, MODE_PRIVATE)
                }
            var themeMode by remember {
                mutableStateOf(
                    appearancePreferences
                        .getString(AppearanceThemeKey, null)
                        ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                        ?: ThemeMode.System,
                )
            }
            NaytiTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    NaytiApp(
                        themeMode = themeMode,
                        onThemeModeChange = { selected ->
                            themeMode = selected
                            appearancePreferences
                                .edit()
                                .putString(AppearanceThemeKey, selected.name)
                                .apply()
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        catalogRuntime.refreshAccess()
    }

    private companion object {
        const val AppearancePreferencesName = "nayti_appearance"
        const val AppearanceThemeKey = "theme_mode"
    }
}
