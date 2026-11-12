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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.personal.cwaweather.ui.WeatherScreen

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModels { WeatherViewModel.Factory }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.loadDeviceLocation()
            } else {
                viewModel.showMessage("沒有定位權限，請手動選擇地點；之後要用目前位置，可以到系統設定開啟這個 App 的定位權限。")
                viewModel.setShowPicker(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                // Runs for a new ViewModel and after a key is saved, but not for a recreated activity.
                LaunchedEffect(state.startPending) {
                    if (viewModel.claimStart()) start()
                }
                WeatherScreen(
                    state = state,
                    onShowPicker = viewModel::setShowPicker,
                    onRefresh = viewModel::refresh,
                    onUseDeviceLocation = ::requestDeviceLocation,
                    onSelectPlace = viewModel::selectPlace,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAskForKey = viewModel::setAskForKey,
                    onSaveApiKey = viewModel::saveApiKey,
                    onKeyEdited = viewModel::clearKeyError,
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
