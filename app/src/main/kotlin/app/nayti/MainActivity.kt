package app.nayti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nayti.ui.NaytiApp
import app.nayti.ui.theme.NaytiTheme
import app.nayti.indexer.CatalogRuntime
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var catalogRuntime: CatalogRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NaytiTheme {
                NaytiApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        catalogRuntime.refreshAccess()
    }
}
