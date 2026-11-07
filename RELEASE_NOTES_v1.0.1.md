# BenStore v1.0.1

BenStore is a self-hosted Android app store with **no F-Droid dependency**. This release adds a full **mandatory sign-in gate** with three sign-in methods, plus per-account app tiering.

BenStore 是一個完全由你掌控的 Android 應用商店，**不依賴 F-Droid**。本次更新加入**強制登入門禁**、三種登入方式，以及依帳號分級顯示應用。

---

## 本次變更 / What's new in v1.0.1

> **後續修訂（同一版本號）**：分級規則現在也套用到「更新」分頁與背景檢查／自動安裝；
> 離線授權碼改為每次重新驗簽；資料源快取依網址分開；repo.json 與圖示加上大小上限；
> OAuth 回呼伺服器只綁 127.0.0.1；APK 下載暫存檔與快取鍵修正。
> **Post-release fixes (same version)**: tiering now also applies to the Updates tab and
> background checks; license codes are re-verified on every read; catalog cache is keyed by URL;
> size caps on catalog and icons; OAuth loopback bound to 127.0.0.1 only; APK download
> temp-file and cache-key fixes.

### 強制登入 / Mandatory sign-in

`PUBLIC_MODE` 現已設為 `false`：**未登入就停在門禁畫面**，無法瀏覽或安裝任何應用。

`PUBLIC_MODE` is now `false`: **users must sign in before they can browse or install anything.**

同時修正了一個會讓門禁失效的漏洞：原本「三種登入方式都沒設定時」會自動放行，
等同於關了門卻沒鎖。現在只要不是公開模式就一律擋。

Also fixed a flaw that silently disabled the gate: previously, if no sign-in method was configured, the app let everyone through — a locked door that was never actually locked. The gate now holds whenever public mode is off.

想改回公開模式：把 `app/build.gradle.kts` 的 `PUBLIC_MODE` 改回 `true` 重新編譯。
To revert: set `PUBLIC_MODE` back to `true` in `app/build.gradle.kts` and rebuild.

### 三種登入方式 / Three sign-in methods

| 方式 / Method | 需要設定 / Setup required | 說明 / Notes |
| --- | --- | --- |
| **BenStore 本機帳號** / Local account | **零設定** / **None** | 現場註冊帳號密碼，只存在該手機 / Register on-device; stored only on that phone |
| Google | `WEB_CLIENT_ID` | 官方通道 + 網頁 OAuth 備援 / Credential Manager, with web OAuth fallback |
| GitHub | Client ID | Device Flow 或貼 PAT / Device Flow, or paste a PAT |

**本機帳號是零配置方案**：不需要申請任何 Client ID，現場註冊即可進入。密碼用
PBKDF2WithHmacSHA256（12 萬次迭代 + 獨立隨機鹽）雜湊，存進 Keystore 加密的偏好設定，不存明文。

**The local account requires zero setup** — no Client ID of any kind. Passwords are hashed with PBKDF2WithHmacSHA256 (120k iterations, per-account salt) and stored in Keystore-encrypted preferences; plaintext is never saved.

### Google 登入雙通道 / Two Google sign-in paths

BenStore 會**先試官方 Credential Manager，失敗才自動改用網頁 OAuth**，使用者只看到一次流程。

BenStore **tries Credential Manager first and automatically falls back to web OAuth** — the user sees a single continuous flow.

```
1. Credential Manager   系統帳號選擇器，一鍵完成
      │ 失敗（沒 Play 服務 / 帳號不在系統清單 / 使用者取消 / 設定有誤）
      ▼
2. 網頁 OAuth + PKCE    Chrome 自訂分頁開 Google 授權頁
                        → 導回本機 loopback 埠 → 換 id_token
```

網頁通道**不需要包名與 SHA-1**，所以在沒裝 Play 服務的裝置、模擬器，或 CI 簽章會變動的正式版上都能用。

The web path **needs no package name and no SHA-1**, so it works on devices without Play services, on emulators, and on release builds whose signing key changes between CI runs.

> 官方通道仍需在 GCP 建一個「Android」型別用戶端（包名 + SHA-1）才能通過驗證，
> 否則會出現 `[16] Account reauth failed` 並自動降級到網頁通道。
> The official path still requires an **Android**-type OAuth client (package name + SHA-1); without it you'll see `[16] Account reauth failed` and the app falls back to the web path.

### 依帳號分級 / Per-account tiering

每個應用可在 `repo.json` 裡宣告 `allowedEmails` 或 `requiredTiers`，不同帳號看到不同清單。
與存取碼（Access Code）機制可並存，任一通過即可看見。

Each app can declare `allowedEmails` or `requiredTiers` in `repo.json`, so different accounts see different catalogs. Works alongside the Access Code mechanism — passing either one grants visibility.

---

## 升級注意 / Upgrade notes

- **v1.0.0 的使用者**：此版本起強制登入。若你沒設定任何 OAuth，請在門禁畫面註冊本機帳號。
  **Coming from v1.0.0**: sign-in is now mandatory. If no OAuth is configured, register a local account on the lock screen.
- **已設 `ALLOWED_EMAILS` 者**：本機帳號也要寫進名單（寫 `local:帳號名` 或直接帳號名），否則註冊完會被判未授權。
  **If you use `ALLOWED_EMAILS`**: local accounts must be listed too (`local:username` or just `username`), or they'll be rejected as unauthorized.
- 登入狀態存在本機，升級不會自動登出。／Sign-in state persists across upgrades.

---

## 下載 / Download

| 檔案 / File | 說明 / Description |
| --- | --- |
| `benstore-release.apk` | 正式版，CI 自動簽名 / Release build, signed by CI |
| `benstore-debug.apk` | 除錯版，含 debug 簽名 / Debug build with debug signature |

同包內附 `SHA256SUMS.txt`／A `SHA256SUMS.txt` is included — verify with:

```bash
sha256sum -c SHA256SUMS.txt
```

---

## 需求 / Requirements

- Android 8.0（API 26）以上 / Android 8.0 (API 26) or higher
- 需在「設定」填入自己的 `repo.json` 網址（內建預設值是佔位符）/ Enter your own `repo.json` URL in Settings — the built-in default is a placeholder
- 首次使用需註冊或登入帳號 / Sign up or sign in on first launch
- 安裝應用需授予「允許安裝未知應用程式」/ "Install unknown apps" permission required to install apps

---

## 完整功能 / Full feature list

### 帳號與門禁 / Accounts & access

- 強制登入（可改回公開模式）/ Mandatory sign-in (reversible to public mode)
- Google / GitHub / 本機帳號三種登入 / Three methods: Google, GitHub, local account
- Google 雙通道自動降級 / Automatic fallback for Google sign-in
- `ALLOWED_EMAILS` 門禁白名單 / Allow-list gating via `ALLOWED_EMAILS`
- 依帳號分級顯示應用 / Per-account app visibility
- 存取碼分級（免 OAuth）/ Access-code tiers, no OAuth needed
- 離線授權碼（RSA 簽章，可設到期日）/ Offline license codes (RSA-signed, expirable)

### 瀏覽與安裝 / Browse & install

- 自訂資料源：指向自己的 `repo.json`，設定頁可隨時改 / Custom catalog URL, editable in Settings
- 依名稱、包名、簡介搜尋 / Search by name, package ID or summary
- 分類篩選（分類直接讀 JSON）/ Category filters, read straight from the JSON
- 應用詳情：圖示、版本、大小、授權、作者、原始碼連結 / App details: icon, version, size, license, author, source
- SHA-256 驗證：JSON 裡填了就自動校驗 / SHA-256 verification when declared
- `PackageInstaller` Session 安裝，不跳出應用程式 / Installs in-process via the Session API

### 更新 / Updates

- **背景定期檢查**：WorkManager 排程，預設每 12 小時、僅 Wi-Fi，開機後自動恢復
  **Background checks**: via WorkManager, every 12h on Wi-Fi by default, restored after reboot
- **更新通知**：點通知直接進「更新」分頁 / **Notifications**: tap to jump to the Updates tab
- **批次更新**：勾選多個一次下載安裝，即時進度 / **Batch updates**: multiple apps in one run, live progress
- **忽略此版本**：下個新版本仍會提醒 / **Ignore this version**: a later release still notifies you
- **安裝結果通知**：失敗時指明原因（簽名不同、空間不足等）
  **Result notifications**: failures state the reason (signature mismatch, out of space, etc.)

---

## 權限說明 / Permissions

| 權限 / Permission | 用途 / Why |
| --- | --- |
| `INTERNET` | 抓資料源與 APK、Google 登入 / Fetch catalog and APKs; Google sign-in |
| `ACCESS_NETWORK_STATE` | 判斷是否 Wi-Fi，遵守「僅 Wi-Fi」設定 / Detect metered networks for the Wi-Fi-only option |
| `REQUEST_INSTALL_PACKAGES` | 安裝 APK / Install APKs |
| `POST_NOTIFICATIONS` | 更新與安裝結果通知 / Update and result notifications |
| `QUERY_ALL_PACKAGES` | Android 11+ 列舉已安裝應用以比對版本；僅本機比對，不上傳 / Required on Android 11+ to compare installed versions; local-only, nothing uploaded |

本機帳號的密碼只存在裝置上，不上傳任何伺服器；BenStore 沒有追蹤、廣告或遙測。

Local account passwords never leave the device. BenStore contains no trackers, ads or telemetry.

---

## 已知限制 / Known limitations

- **無法完全靜默安裝**：第三方應用拿不到 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`，每次安裝都會彈一次系統確認。要真正的背景靜默更新需要 Shizuku、Root，或將 BenStore 刷成系統應用。
  **Fully silent installs are not possible**: third-party apps cannot hold `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, so each install shows one system confirmation. True silent updates require Shizuku, root, or installing BenStore as a system app.
- **Google 登入的 token 未驗簽**：App 端只解出 email 與名稱，沒有密碼學驗證簽章。這是識別用途，不是安全邊界——要可驗證的版本需搭配後端。
  **Google ID tokens are not signature-verified** on-device: we only decode email and name. This is identification, not a security boundary — cryptographic verification requires a backend.
- **`ALLOWED_EMAILS` 是編譯期常數**：改名單要重新編譯並安裝新版 APK，無法遠端更新。
  **`ALLOWED_EMAILS` is a compile-time constant**: changing it requires a rebuild and reinstall; it cannot be updated remotely.
- **本機帳號沒有忘記密碼機制**：沒有伺服器可寄信，忘了只能刪帳號重設（資料會一併清除）。
  **Local accounts have no password recovery**: with no server, a forgotten password means deleting the account and losing its data.
- **不做簽名比對**：更新時只比版本碼。若資料源的 APK 與已安裝版本簽名不同，安裝會失敗並說明原因。
  **No signature comparison**: updates are matched on version code only. A differently-signed APK fails with an explanation.
- **單一資料源**：同時只能讀一份 `repo.json`。／**Single catalog**: only one `repo.json` at a time.
- **無資料源簽章驗證**：走 HTTPS，但沒驗證 JSON 的數位簽章。／**No catalog signature verification**: fetched over HTTPS, but the JSON is not digitally signed.

---

## 從原始碼建置 / Building from source

```bash
git clone https://github.com/<your-account>/BenStore.git
cd BenStore
./gradlew assembleRelease
```

需要 JDK 17 與 Android SDK 34。／Requires JDK 17 and Android SDK 34.

內建兩個 GitHub Actions workflow／Two workflows are included:

| Workflow | 用途 / Purpose |
| --- | --- |
| `Build APK` | push / PR 時編譯並產出 Artifact（不發布）／Compiles on push or PR, uploads an artifact (no release) |
| `Release` | 手動觸發：改版本號 → 編譯 → 簽名 → 建 tag → 發布／Manual: bump → build → sign → tag → publish |

發版全程在 GitHub 網頁操作：**Actions → Release → Run workflow → 填 `v1.0.1`**。
To publish: **Actions → Release → Run workflow → enter `v1.0.1`**. No local commands needed.

---

## 技術棧 / Tech stack

Kotlin · Jetpack Compose · Material 3 · OkHttp · WorkManager · PackageInstaller · Credential Manager · Kotlin Coroutines

授權 / License: GPL-3.0-or-later
