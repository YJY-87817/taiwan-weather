package tw.personal.cwaweather

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.personal.cwaweather.ui.WeatherScreen

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModels()
    private var showPicker by mutableStateOf(false)

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.loadDeviceLocation()
            } else {
                showPicker = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) start()

        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                WeatherScreen(
                    state = state,
                    showPicker = showPicker,
                    onShowPicker = { showPicker = it },
                    onRefresh = viewModel::refresh,
                    onUseDeviceLocation = ::requestDeviceLocation,
                    onSelectPlace = viewModel::selectPlace,
                    onToggleFavorite = viewModel::toggleFavorite,
                    hasApiKey = viewModel.hasApiKey(),
                    onAskForKey = viewModel::setAskForKey,
                    onSaveApiKey = { key ->
                        viewModel.saveApiKey(key).also { error -> if (error == null) start() }
                    },
                    loadTowns = viewModel::towns,
                )
            }
        }
    }

    private fun start() {
        if (!viewModel.hasApiKey()) {
            viewModel.showMessage("需要氣象署授權碼才能取得天氣資料。")
            viewModel.setAskForKey(true)
            return
        }
        val saved = viewModel.savedPlace()
        if (saved != null) viewModel.selectPlace(saved) else requestDeviceLocation()
    }

    private fun requestDeviceLocation() {
        val permission = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadDeviceLocation()
        } else {
            locationPermission.launch(permission)
        }
    }
}
