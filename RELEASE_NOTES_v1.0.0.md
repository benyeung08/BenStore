# BenStore v1.0.0

BenStore is a self-hosted Android app store with **no F-Droid dependency**. It reads a `repo.json` catalog that you maintain yourself, lists apps with icons and versions, downloads APKs (with optional SHA-256 verification), installs them, and tracks updates for everything already installed on your device.

BenStore 是一個完全由你掌控的 Android 應用商店，**不依賴 F-Droid**：讀取你自己維護的 `repo.json`，列出應用的圖示與版本、下載 APK（可選 SHA-256 驗證）並安裝，同時持續追蹤裝置上已安裝應用的更新。

---

## 下載 / Download

| 檔案 / File | 說明 / Description |
| --- | --- |
| `benstore-release.apk` | 正式版，CI 自動簽名 / Release build, signed by CI |
| `benstore-debug.apk` | 除錯版，含 debug 簽名 / Debug build with debug signature |

兩者**不能同時安裝，也不能互相升級**：包名同為 `com.benstore.app`、簽名不同，Android 會回報簽名衝突。要換版本請先解除安裝／**Do not install both**: same package name, different signatures — Android will reject the update. Uninstall first if switching builds.

同包內附 `SHA256SUMS.txt`，下載後可驗證完整性／A `SHA256SUMS.txt` is included — verify with:

```bash
sha256sum -c SHA256SUMS.txt
```

---

## 需求 / Requirements

- Android 8.0（API 26）以上 / Android 8.0 (API 26) or higher
- 首次開啟需連網載入 `repo.json`（通常只有幾 KB）/ Internet required on first launch to load `repo.json` (usually just a few KB)
- 需在「設定」填入自己的 `repo.json` 網址（預設值是佔位符，直接開會載入失敗）/ Enter your own `repo.json` URL in Settings — the built-in default is a placeholder
- 安裝應用需授予「允許安裝未知應用程式」/ "Install unknown apps" permission required to install apps

---

## 功能 / Features

### 瀏覽與安裝 / Browse & Install

- 瀏覽整個倉庫，支援名稱、包名、簡介搜尋 / Browse the full repo; search by name, package ID or summary
- 12 個熱門分類快速篩選 / 12 top categories as quick filters
- 應用詳情：圖示、版本、大小、授權、作者、原始碼連結 / App details: icon, version, size, license, author, source link
- 自訂資料源：指向自己的 `repo.json`，App 內「設定」可隨時改網址 / **Custom catalog**: point to your own `repo.json`; the URL is editable in Settings
- 公開模式（預設）：任何人免登入即可瀏覽並安裝全部應用 / **Public mode (default)**: anyone can browse and install all apps, no sign-in required
- 兩種登入方式：Google（Credential Manager）或 GitHub（Device Flow），共用同一套帳號 / **Two sign-in options**: Google (Credential Manager) or GitHub (Device Flow), unified into one account
- 依帳號分級顯示：每個應用可指定 `allowedEmails`，不同帳號看到不同清單 / **Per-account catalog**: each app can declare `allowedEmails`, so accounts see different apps
- SHA-256 驗證：JSON 裡填了就自動校驗下載結果 / **SHA-256 verification**: automatically checked when declared in the catalog
- 安裝走 `PackageInstaller` Session API，不跳出應用程式 / Installs via the `PackageInstaller` Session API — no jumping out to another app

### 更新 / Updates

- **背景定期檢查**：WorkManager 排程，預設每 12 小時、僅 Wi-Fi，開機後自動恢復
  **Background checks**: scheduled via WorkManager, every 12h on Wi-Fi by default, restored automatically after reboot
- **更新通知**：發現新版本時通知，點通知直接進「更新」分頁
  **Update notifications**: alerts when new versions are found; tap to jump straight to the Updates tab
- **批次更新**：勾選多個應用一次下載安裝，即時進度顯示
  **Batch updates**: select multiple apps and update them in one run, with live progress
- **忽略此版本**：忽略後下個新版本仍會提醒 / **Ignore this version**: a later release will still notify you
- **安裝結果通知**：成功或失敗都會回報，失敗時明確指出原因（簽名不同、空間不足等）
  **Install result notifications**: success or failure reported, with the reason on failure (signature mismatch, out of space, etc.)

### 設定 / Settings

自動檢查開關、檢查間隔（6 / 12 / 24 小時）、僅 Wi-Fi、自動下載並安裝、立即檢查、清除忽略清單、重新同步索引。

Toggle for auto-check, interval (6 / 12 / 24h), Wi-Fi only, auto-download & install, check now, clear ignored list, re-sync index.

---

## 權限說明 / Permissions

| 權限 / Permission | 用途 / Why |
| --- | --- |
| `INTERNET` | 下載索引與 APK / Fetch index and APKs |
| `ACCESS_NETWORK_STATE` | 判斷是否為 Wi-Fi，遵守「僅 Wi-Fi」設定 / Detect metered networks for the Wi-Fi-only option |
| `REQUEST_INSTALL_PACKAGES` | 安裝 APK / Install APKs |
| `POST_NOTIFICATIONS` | 發送更新與安裝結果通知 / Post update and result notifications |
| `QUERY_ALL_PACKAGES` | Android 11+ 上列舉已安裝應用以比對版本；僅在本機比對，不上傳 / Required on Android 11+ to enumerate installed apps for version comparison; comparison is local-only, nothing is uploaded |

BenStore 沒有任何追蹤、廣告或遙測，也不要求帳號登入。

BenStore contains no trackers, ads or telemetry, and requires no account.

---

## 已知限制 / Known limitations

- **無法完全靜默安裝**：第三方應用拿不到 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`，每次安裝都會彈一次系統確認。要真正的背景靜默更新需要 Shizuku、Root，或將 BenStore 刷成系統應用。
  **Fully silent installs are not possible**: third-party apps cannot hold `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, so each install shows one system confirmation. True silent updates require Shizuku, root, or installing BenStore as a system app.
- **不做簽名比對**：更新時只比版本碼。若倉庫中的 APK 與你已安裝的版本簽名不同，安裝會失敗並在通知中說明。
  **No signature comparison**: updates are matched on version code only. If the repo APK is signed differently from your installed version, the install fails and the notification explains why.
- **單一資料源**：同時只能讀一份 `repo.json`。／**Single catalog**: only one `repo.json` is read at a time.
- **無資料源簽章驗證**：走 HTTPS，但沒驗證 JSON 的數位簽章。／**No catalog signature verification**: fetched over HTTPS, but the JSON is not digitally signed.
- APK 與索引存於 cache 目錄，系統清理空間時可能被刪除，重新開啟會再次抓取。
  APKs and the index live in the cache directory; the system may clear them, and they will be re-downloaded on next launch.

---

## 從原始碼建置 / Building from source

```bash
git clone https://github.com/<your-account>/BenStore.git
cd BenStore
./gradlew assembleRelease
```

需要 JDK 17 與 Android SDK 34。／Requires JDK 17 and Android SDK 34.

不想裝 Android Studio 也沒關係，專案內建兩個 GitHub Actions workflow／Prefer not to install Android Studio? Two workflows are included:

| Workflow | 用途 / Purpose |
| --- | --- |
| `Build APK` | push / PR 時自動編譯，產出 Artifact（不發布）／Compiles on push or PR and uploads an artifact (no release) |
| `Release` | 手動觸發：改版本號 → 編譯 → 簽名 → 建 tag → 發布 Release／Manual: bump version → build → sign → tag → publish |

發版只要在 GitHub 網頁操作：**Actions → Release → Run workflow → 填 `v1.0.0`**，不需要本機下任何指令。

To publish: **Actions → Release → Run workflow → enter `v1.0.0`**. No local commands needed.

---

## 技術棧 / Tech stack

Kotlin · Jetpack Compose · Material 3 · OkHttp · WorkManager · PackageInstaller · Kotlin Coroutines

授權 / License: GPL-3.0-or-later
