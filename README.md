# BenStore

一個**你自己說了算的 Android 開源應用商店**：從 F-Droid / IzzyOnDroid 倉庫抓 `index-v1.jar`，列出開源應用、顯示圖示與版本，下載 APK 並呼叫系統安裝器安裝。

Kotlin + Jetpack Compose，單模組、約 700 行，Push 到 GitHub 就由 Actions 自動編譯、簽名、打包 APK。

---

## 一、30 秒開始

1. 在 GitHub 新建一個空倉庫（例如 `BenStore`），**不要**勾選 README / .gitignore。
2. 把本專案所有檔案（含 `.github/`）上傳或 push 上去：
   ```bash
   git init
   git add .
   git commit -m "init: BenStore"
   git branch -M main
   git remote add origin https://github.com/<你的帳號>/BenStore.git
   git push -u origin main
   ```
3. 打開倉庫的 **Actions** 分頁 → 等 `Build APK` 跑完（約 5–10 分鐘）→ 點進去 → 在 **Artifacts** 下載 `benstore-apk`。

解壓後有兩個檔案，裝任一個都可以：

| 檔案 | 說明 |
| --- | --- |
| `benstore-release.apk` | 正式版，CI 自動簽名 |
| `benstore-debug.apk` | 除錯版，含 debug 簽名 |

> 第一次跑會比較慢（要下載 Gradle 與 Android 依賴），之後有快取就只要 2–3 分鐘。

## 二、手機上安裝

- 安裝時系統會擋：點「設定」→ 對你的瀏覽器/檔案管理員開啟「允許安裝未知應用程式」。
- 打開 App 會自動下載並解析倉庫索引（F-Droid 約 10–20 MB），第一次請等它跑完。
- 點進任一個應用 → 「下載並安裝」→ 第一次會跳出系統授權頁，勾選「允許來自此來源」後再按一次即可。

## 三、改造成你自己的商店

| 想改什麼 | 改哪裡 |
| --- | --- |
| 應用名稱 | `app/src/main/res/values/strings.xml` 的 `app_name` |
| 包名（決定能不能跟別的 App 共存） | `app/build.gradle.kts` 的 `applicationId`（目前是 `com.benstore.app`），同步改 `AndroidManifest` 不用動（用了 `${applicationId}`） |
| 預設倉庫 | `RepoStore.kt` 的 `DEFAULT_REPO`，或畫面上方的 F-Droid / IzzyOnDroid 切換 |
| 加更多倉庫 | `StoreScreen.kt` 的 `PRESET_REPOS` 清單，例如 `https://guardianproject.info/fdroid/repo` |
| 版本號 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| 圖示 | 見下方「圖示規格」 |

## 圖示規格

Logo 是那隻方塊機器人，已做成**標準 Android 自適應圖示**（Adaptive Icon），自動適配各家啟動器的圓形／圓角方形／超橢圓遮罩：

```
res/
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml              # 自適應圖示（API 26+ 優先使用）
│   └── ic_launcher_round.xml
├── drawable-nodpi/
│   ├── ic_launcher_background.png   # 彩虹漸變底 432×432
│   └── ic_launcher_foreground.png   # 機器人（去背 + 白描邊）432×432
└── mipmap-mdpi…xxxhdpi/
    └── ic_launcher.png              # 舊啟動器／通知用的合成圖
art/
├── logo_original.jpg                # 原始 logo 備份
├── ic_launcher_playstore.png        # 512×512（上架用）
└── icon_preview.png                 # 三種遮罩下的預覽
```

設計要點：

- **去背**：原圖背景是橫向彩虹漸變，按「每一列的背景色」建模後把機器人從中分離，保留原有的粗黑輪廓與積木配色。
- **白描邊**：機器人本身是黃頭／紅身／藍腿，直接壓在彩虹底上對比度不夠，所以加了一圈約 1.5dp 的白色描邊，任何背景色上都看得清。
- **安全區**：機器人身高設為 72dp（自適應圖示畫布 108dp），正好落在官方的 72dp 可視區內，圓形遮罩也不會切到頭頂或腳底。

想換 logo：直接替換 `art/logo_original.jpg` 後重跑 `python3 make_icons.py`（腳本在專案根目錄），就會重新產出整套圖示。

## 四、用自己的簽名金鑰（重要）

CI 預設會自動產生一把「測試金鑰」。測試金鑰每次跑 Actions 都不同，**換機器後無法直接覆蓋升級**。要長期用，請：

```bash
keytool -genkeypair -v -keystore my-release.jks -alias benstore \
  -keyalg RSA -keysize 2048 -validity 10950
base64 -w 0 my-release.jks > my-release.jks.b64
```

到 GitHub 倉庫 **Settings → Secrets and variables → Actions** 新增 4 個 secret：
`KEYSTORE_BASE64`、`KEYSTORE_ALIAS`、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_PASSWORD`。

下次 Actions 就會用你的金鑰簽名，升級不掉資料。金鑰檔本身請另外備份，不要進 repo。

## 五、發一個正式版本

```bash
git tag v1.0
git push origin v1.0
```

Actions 會自動把兩個 APK 掛到 GitHub Release 上，別人直接從 Releases 頁面就能下載。

## 六、想在本機編譯

需要 Android Studio（Ladybug 以上）或 JDK 17 + Android SDK 34：

```bash
./gradlew assembleDebug      # 沒 wrapper 就先跑：gradle wrapper
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 七、另一條更快路線：直接 Fork 成熟客戶端

如果你想要的是「功能完整、馬上能用的商店」而不是自己寫，Fork 這兩個之一更划算，它們本身就有現成的 CI：

- **Droid-ify** `https://github.com/Droid-ify/client` — 介面現代、同步快、支援 Shizuku/Root 靜默安裝
- **Neo Store** `https://github.com/NeoApplications/Neo-Store` — 內建約 100 個倉庫、隱私面板、篩選器最完整

Fork 之後照樣可以在 Actions 裡產出你簽名的 APK。

## 八、已知限制

- 目前只做「瀏覽 + 下載 + 安裝」，沒有背景自動更新、沒有 Nearby 分享、沒有倉庫指紋校驗。
- 安裝用系統安裝器（`ACTION_VIEW` + FileProvider），需要使用者按一次確認；要靜默更新得接 `PackageInstaller` Session API 或 Shizuku。
- APK 與索引都放在 app 的 cache 目錄，系統清理空間時會被刪掉，重新開啟會再抓一次。

授權：GPL-3.0-or-later（沿用 F-Droid 客戶端的 Copyleft 精神）。
