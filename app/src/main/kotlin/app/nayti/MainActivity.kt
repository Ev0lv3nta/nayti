package app.nayti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.nayti.ui.NaytiApp
import app.nayti.ui.designsystem.theme.NaytiTheme
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
            NaytiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    NaytiApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        catalogRuntime.refreshAccess()
    }
}
