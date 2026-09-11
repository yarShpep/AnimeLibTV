package org.example.atvretranslation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.example.atvretranslation.ui.AnimeLibTvApp
import org.example.atvretranslation.ui.AppViewModel
import org.example.atvretranslation.update.UpdateManager

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            BackHandler(enabled = true) {
                if (!viewModel.back()) finish()
            }
            AnimeLibTvApp(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.installPendingIfAllowed(this)
    }
}
