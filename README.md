# 台灣天氣

使用[中央氣象署開放資料](https://opendata.cwa.gov.tw/)的 Android 天氣 App，提供臺灣各鄉鎮的即時天氣、預報與天氣特報。

## 功能

- **即時天氣：** 溫度、濕度、風、氣壓、雨量和紫外線，並標示來源測站與觀測時間。
- **預報：** 未來 3 天的氣溫趨勢與逐時預報，以及未來 1 週的每日預報。
- **天氣特報：** 顯示所在縣市目前的警特報、有效時間和完整內容。
- **地點：** 可用手機定位或手動選擇縣市鄉鎮，也能收藏常用地點。
- **離線：** 連不上氣象署時顯示上次儲存的資料，並標示儲存時間。

尚未提供桌面小工具和背景通知，目前只有 debug 版，還沒有正式版簽章。

## 安裝與設定

1. 到[氣象資料開放平台](https://opendata.cwa.gov.tw/)登入會員，取得授權碼。
2. 在 Android 8.0 以上的手機安裝 APK。
3. 開啟 App，輸入授權碼並按「驗證並儲存」。

之後要更換授權碼，可以點首頁最下方的「授權碼設定」。

授權碼只會存在手機上的 App 私有空間，並排除在雲端備份和換機轉移之外，但沒有另外加密。授權碼不會打包進 APK，所以 APK 可以分享，每位使用者輸入自己的授權碼即可。

## 從原始碼建置

需要 Android Studio（使用內附的 JDK）和 Android SDK Platform 37。可以直接用 Android Studio 開啟專案，或在指令列建置：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug
```

APK 會產生在 `app/build/outputs/apk/debug/app-debug.apk`。

如果沒有用 Android Studio 開啟過專案，請先建立 `local.properties` 指定 SDK 位置，或設定 `ANDROID_HOME` 環境變數。這個檔案不會納入版本控制：

```properties
sdk.dir=C\:\\Users\\<使用者名稱>\\AppData\\Local\\Android\\Sdk
```

執行單元測試：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest
```

## 使用的資料集

| 資料集 | 用途 |
|---|---|
| `O-A0003-001` | 10 分鐘綜觀氣象觀測 |
| `O-A0001-001` | 全測站逐時觀測，用來判斷所在鄉鎮及補足測站 |
| `F-D0047-001`～`F-D0047-087` | 各縣市鄉鎮預報；3 天與 1 週各一個資料集 |
| `W-C0033-001` | 各縣市目前的警特報 |
| `W-C0033-002` | 警特報的完整內容 |

## 專案結構

```text
app/src/main/java/tw/personal/cwaweather/
├── MainActivity.kt        進入點與定位權限
├── WeatherViewModel.kt    畫面狀態與載入流程
├── data/                  API、回應解析、離線快取、地點與授權碼儲存
└── ui/WeatherScreen.kt    Jetpack Compose 畫面
app/src/test/              單元測試與真實 API 回應樣本
```

## 資料來源

天氣資料來源為交通部中央氣象署。
