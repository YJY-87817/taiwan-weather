package tw.personal.cwaweather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import tw.personal.cwaweather.WeatherUiState
import tw.personal.cwaweather.data.Counties
import tw.personal.cwaweather.data.CurrentWeather
import tw.personal.cwaweather.data.DailyForecast
import tw.personal.cwaweather.data.HourlyForecast
import tw.personal.cwaweather.data.Place
import tw.personal.cwaweather.data.WeatherAlert

private val observedFormat = DateTimeFormatter.ofPattern("M/d HH:mm")
private val hourFormat = DateTimeFormatter.ofPattern("HH:mm")
private val hourDateFormat = DateTimeFormatter.ofPattern("M/d")
private val dayFormat = DateTimeFormatter.ofPattern("M/d (E)", Locale.TRADITIONAL_CHINESE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onShowPicker: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onUseDeviceLocation: () -> Unit,
    onSelectPlace: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    onAskForKey: (Boolean) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onKeyEdited: () -> Unit,
    loadTowns: suspend (String) -> Result<List<String>>,
) {
    val snapshot = state.snapshot
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.place?.let { "${it.county} ${it.town}" } ?: "台灣天氣",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    state.place?.let { place ->
                        TextButton(onClick = { onToggleFavorite(place) }) {
                            Text(if (place in state.favorites) "★" else "☆")
                        }
                    }
                    TextButton(onClick = { onShowPicker(true) }) { Text("地點") }
                    TextButton(onClick = onRefresh, enabled = !state.loading && state.place != null) {
                        Text("更新")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                PlaceChips(
                    state = state,
                    onUseDeviceLocation = onUseDeviceLocation,
                    onSelectPlace = onSelectPlace,
                )
            }
            if (state.loading) {
                item { CircularProgressIndicator() }
            }
            state.message?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            snapshot?.offlineSince?.let { savedAt ->
                item {
                    val time = Instant.ofEpochMilli(savedAt).atZone(ZoneId.systemDefault())
                    Text(
                        "無法連線，顯示 ${time.format(observedFormat)} 儲存的資料",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (snapshot != null) {
                item { AlertsSection(snapshot.alerts) }
                snapshot.current?.let { current -> item { CurrentCard(current, snapshot.place) } }
                if (snapshot.hourly.isNotEmpty()) item { HourlySection(snapshot.hourly) }
                if (snapshot.daily.isNotEmpty()) item { DailySection(snapshot.daily) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "資料來源：交通部中央氣象署",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onAskForKey(true) }) { Text("授權碼設定") }
                }
            }
        }
    }

    if (state.askForKey) {
        ApiKeyDialog(
            hasApiKey = state.hasApiKey,
            verifying = state.verifyingKey,
            error = state.keyError,
            onSave = onSaveApiKey,
            onEdited = onKeyEdited,
            onDismiss = { onAskForKey(false) },
        )
    }

    if (state.showPicker) {
        PlacePicker(
            loadTowns = loadTowns,
            onDismiss = { onShowPicker(false) },
            onUseDeviceLocation = {
                onShowPicker(false)
                onUseDeviceLocation()
            },
            onSelect = {
                onShowPicker(false)
                onSelectPlace(it)
            },
        )
    }
}

@Composable
private fun ApiKeyDialog(
    hasApiKey: Boolean,
    verifying: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Saveable so a rotated screen keeps what was typed.
    var text by rememberSaveable { mutableStateOf("") }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        title = { Text("氣象署授權碼") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hasApiKey) {
                        "已設定授權碼。輸入新的授權碼可以更換。"
                    } else {
                        "請輸入你在氣象資料開放平台的會員授權碼，它只會儲存在這支手機上。"
                    },
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (error != null) onEdited()
                    },
                    label = { Text("授權碼") },
                    singleLine = true,
                    enabled = !verifying,
                    isError = error != null,
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (verifying) Text("正在向氣象署確認…")
                Row {
                    TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "隱藏" else "顯示") }
                    TextButton(onClick = { uriHandler.openUri("https://opendata.cwa.gov.tw/") }) {
                        Text("前往開放平台")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !verifying,
                onClick = { onSave(text.trim()) },
            ) { Text("驗證並儲存") }
        },
        dismissButton = {
            TextButton(enabled = !verifying, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PlaceChips(
    state: WeatherUiState,
    onUseDeviceLocation: () -> Unit,
    onSelectPlace: (Place) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = state.usesDeviceLocation,
                onClick = onUseDeviceLocation,
                label = { Text("目前位置") },
            )
        }
        items(state.favorites) { place ->
            FilterChip(
                selected = !state.usesDeviceLocation && state.place == place,
                onClick = { onSelectPlace(place) },
                label = { Text(place.town) },
            )
        }
    }
}

@Composable
private fun AlertsSection(alerts: List<WeatherAlert>?) {
    when {
        alerts == null -> Text(
            "特報資料暫時無法取得，請留意官方資訊。",
            color = MaterialTheme.colorScheme.error,
        )
        alerts.isEmpty() -> Text(
            "本縣市目前無天氣特報",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            alerts.forEach { alert -> AlertCard(alert) }
        }
    }
}

@Composable
private fun AlertCard(alert: WeatherAlert) {
    var expanded by remember(alert) { mutableStateOf(false) }
    val notStarted = alert.start?.isAfter(OffsetDateTime.now()) == true
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = alert.detail != null) { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                alert.title + if (notStarted) "（尚未生效）" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${alert.start?.format(observedFormat) ?: "—"} ～ ${alert.end?.format(observedFormat) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (alert.detail != null) {
                if (expanded) {
                    Text(alert.detail, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("點一下看內容", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CurrentCard(current: CurrentWeather, place: Place) {
    val station = current.station
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${station.temperature.oneDecimal()}°C", style = MaterialTheme.typography.displayMedium)
            station.weather?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (station.dailyHigh != null || station.dailyLow != null) {
                Text("今日最高 ${station.dailyHigh.oneDecimal()}° / 最低 ${station.dailyLow.oneDecimal()}°")
            }
            Text("濕度 ${station.humidity?.roundToInt()?.let { "$it%" } ?: "—"}　氣壓 ${station.pressure.oneDecimal()} hPa")
            Text("風速 ${station.windSpeed.oneDecimal()} m/s　風向 ${windDirection(station.windDirection)}")
            Text("本日雨量 ${station.precipitation.oneDecimal()} mm" + (station.uvIndex?.let { "　紫外線 ${it.roundToInt()}" } ?: ""))
            // A station outside the town says where it is, so its readings are not taken for the town's own.
            val elsewhere = station.county != place.county || station.town != place.town
            val where = "（${station.county}${station.town}）".takeIf { elsewhere && it.length > 2 }.orEmpty()
            val distance = current.distanceKm?.let { "，距離約 ${"%.1f".format(it)} 公里" }.orEmpty()
            val altitude = station.altitude?.takeIf { elsewhere }?.let { "，海拔 ${it.roundToInt()} 公尺" }.orEmpty()
            Text(
                "${station.name}測站$where$distance$altitude\n觀測時間 ${station.observedAt?.format(observedFormat) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HourlySection(hourly: List<HourlyForecast>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("未來 3 天", style = MaterialTheme.typography.titleMedium)
        TemperatureChart(hourly.map { it.temperature })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hourly) { item ->
                Card {
                    Column(
                        Modifier.width(96.dp).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(item.time.format(hourDateFormat), style = MaterialTheme.typography.bodySmall)
                        Text(item.time.format(hourFormat), style = MaterialTheme.typography.bodyMedium)
                        Text("${item.temperature.roundToInt()}°", style = MaterialTheme.typography.titleLarge)
                        Text(item.weather ?: "—", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("降雨 ${item.rainChance?.let { "$it%" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemperatureChart(temperatures: List<Double>) {
    if (temperatures.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val min = temperatures.min()
    val max = temperatures.max()
    Text(
        "氣溫趨勢 ${min.roundToInt()}° ～ ${max.roundToInt()}°",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val range = (max - min).coerceAtLeast(1.0)
        val stepX = size.width / (temperatures.size - 1)
        fun point(index: Int) = Offset(
            x = index * stepX,
            y = (size.height * (1 - (temperatures[index] - min) / range)).toFloat(),
        )
        val path = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (index in 1 until temperatures.size) lineTo(point(index).x, point(index).y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun DailySection(daily: List<DailyForecast>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("未來 1 週", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                daily.forEachIndexed { index, day ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(day.date.format(dayFormat))
                            Text(
                                day.weather ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(day.rainChance?.let { "降雨 $it%" } ?: "", style = MaterialTheme.typography.bodySmall)
                        Text("${day.high?.roundToInt() ?: "—"}° / ${day.low?.roundToInt() ?: "—"}°")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacePicker(
    loadTowns: suspend (String) -> Result<List<String>>,
    onDismiss: () -> Unit,
    onUseDeviceLocation: () -> Unit,
    onSelect: (Place) -> Unit,
) {
    var county by rememberSaveable { mutableStateOf<String?>(null) }
    var towns by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(county) {
        val selected = county ?: return@LaunchedEffect
        towns = null
        failed = false
        loadTowns(selected).onSuccess { towns = it }.onFailure { failed = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(county ?: "選擇地點") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                val selected = county
                if (selected == null) {
                    item { PickerRow("使用目前位置", onUseDeviceLocation) }
                    items(Counties.names) { name -> PickerRow(name) { county = name } }
                } else {
                    when {
                        failed -> item { Text("鄉鎮清單讀取失敗，請確認網路與授權碼。") }
                        towns == null -> item { CircularProgressIndicator() }
                        else -> items(towns.orEmpty()) { town ->
                            PickerRow(town) { onSelect(Place(selected, town)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (county == null) onDismiss() else county = null }) {
                Text(if (county == null) "取消" else "返回")
            }
        },
    )
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

private fun Double?.oneDecimal(): String = this?.let { "%.1f".format(it) } ?: "—"

private fun windDirection(degrees: Double?): String {
    if (degrees == null) return "—"
    val names = listOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")
    return names[((degrees / 45).roundToInt() % 8 + 8) % 8]
}
