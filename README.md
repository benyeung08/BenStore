# BenStore

一個**你自己說了算的 Android 開源應用商店**：從 F-Droid / IzzyOnDroid 倉庫抓 `index-v1.jar`，列出開源應用、顯示圖示與版本，下載 APK 並安裝；還會在背景比對已安裝版本、通知你有更新、並支援批次一鍵更新。

Kotlin + Jetpack Compose，單模組約 2000 行，Push 到 GitHub 就由 Actions 自動編譯、簽名、打包 APK。

**功能一覽**

| 功能 | 說明 |
| --- | --- |
| 瀏覽 / 搜尋 / 分類 | 依名稱、包名、簡介搜尋，分類篩選 |
| 下載安裝 | `PackageInstaller` Session 安裝，不跳出應用程式 |
| 背景檢查更新 | WorkManager 週期性檢查，預設 12 小時、僅 Wi-Fi |
| 更新通知 | 發現新版本發通知，點通知直接進「更新」分頁 |
| 批次更新 | 勾選多個應用一次下載安裝，可單獨「忽略此版本」 |
| 安裝結果通知 | 每個更新成功／失敗都會回報（含衝突、空間不足等原因） |

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

**兩件必須先確認的事**（只做一次）：

1. **Workflow 權限**：`Release` workflow 要建 tag、發 Release，需要寫入權限。到 **Settings → Actions → General → Workflow permissions**，選 **Read and write permissions** 後存檔。沒開的話 `Release` 會在推送 tag 那步失敗。
2. **首次上傳只能用 git**：`.github/` 是隱藏目錄，GitHub 網頁的「Upload files」建不出來，所以第 2 步必須用 `git push`（或用 GitHub Desktop）。**程式碼進倉庫之後，所有編譯、簽名、發版都可以在網頁上完成，不再需要指令。**

## 二、手機上安裝

- 安裝時系統會擋：點「設定」→ 對你的瀏覽器/檔案管理員開啟「允許安裝未知應用程式」。
- 打開 App 會自動下載並解析倉庫索引（F-Droid 約 10–20 MB），第一次請等它跑完。
- 點進任一個應用 → 「下載並安裝」→ 第一次會跳出系統授權頁，勾選「允許來自此來源」後再按一次即可。

## 三、更新功能怎麼運作

```
WorkManager（週期性，開機自動恢復）
   └─ UpdateWorker
        1. 重新下載 index-v1.jar（force = true，否則永遠比不到新版本）
        2. UpdateScanner.scan()：比對已安裝版本 vs 倉庫建議版本
        3. 有更新 → 發通知（或依設定直接下載安裝）

UI「更新」分頁
   └─ 勾選 → 逐一下載 → Installer（PackageInstaller Session）→ 系統確認
         └─ InstallResultReceiver 接收結果 → 發成功／失敗通知
```

幾個實作上的重點：

- **安裝走 Session API 而不是 `ACTION_VIEW`**：好處是不會跳出去瀏覽器/套件安裝程式，可以連續排隊多個，而且能收到安裝結果。`Installer.kt` 裡還保留了 `ACTION_VIEW` 作為降級方案，Session 失敗時自動退回。
- **忽略是「針對版本」**：忽略後存的是 `包名:版本碼`，下個新版本出來會再次提醒，不會從此消失。
- **背景檢查一定重新下載索引**：這是唯一能發現新版本的办法，F-Droid 索引約 10–20 MB，建議維持預設的 12 小時間隔 + 僅 Wi-Fi。
- **系統確認不可避免**：第三方應用無法完全靜默安裝（那需要系統簽名的 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`），所以每個更新仍會彈一次確認框。想少彈幾次，就開「自動下載並安裝」以外的模式，一次勾選多個批次處理。

設定頁可調整：自動檢查開關、間隔（6/12/24 小時）、僅 Wi-Fi、自動下載並安裝、立即檢查、清除忽略清單。

## 四、改造成你自己的商店

| 想改什麼 | 改哪裡 |
| --- | --- |
| 應用名稱 | `app/src/main/res/values/strings.xml` 的 `app_name` |
| 包名（決定能不能跟別的 App 共存） | `app/build.gradle.kts` 的 `applicationId`（目前是 `com.benstore.app`），同步改 `AndroidManifest` 不用動（用了 `${applicationId}`） |
| 預設倉庫 | `RepoStore.kt` 的 `DEFAULT_REPO`，或畫面上方的 F-Droid / IzzyOnDroid 切換 |
| 加更多倉庫 | `StoreScreen.kt` 的 `PRESET_REPOS` 清單，例如 `https://guardianproject.info/fdroid/repo` |
| 版本號 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| 預設檢查間隔 | `Prefs.kt` 的 `K_INTERVAL` 預設值（12 小時） |
| 圖示 | 見下方「圖示規格」 |

**原始碼模組對照**

| 檔案 | 負責什麼 |
| --- | --- |
| `Models.kt` | 解析 `index-v1.json`（應用、APK、倉庫索引） |
| `RepoStore.kt` | 抓索引、讀圖示、下載 APK、安裝權限 |
| `Prefs.kt` | 所有設定（間隔、僅 Wi-Fi、忽略清單） |
| `UpdateScanner.kt` | 比對已安裝版本與倉庫版本 |
| `UpdateWorker.kt` | WorkManager 背景檢查 + 排程 |
| `Installer.kt` | `PackageInstaller` Session 安裝 + 結果接收 |
| `Notifier.kt` | 通知管道與內容 |
| `StoreScreen.kt` | 三個分頁的 Compose UI |

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

## 五、用自己的簽名金鑰（重要）

CI 預設會自動產生一把「測試金鑰」。測試金鑰每次跑 Actions 都不同，**換機器後無法直接覆蓋升級**。要長期用，請：

```bash
keytool -genkeypair -v -keystore my-release.jks -alias benstore \
  -keyalg RSA -keysize 2048 -validity 10950
base64 -w 0 my-release.jks > my-release.jks.b64
```

到 GitHub 倉庫 **Settings → Secrets and variables → Actions** 新增 4 個 secret：
`KEYSTORE_BASE64`、`KEYSTORE_ALIAS`、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_PASSWORD`。

下次 Actions 就會用你的金鑰簽名，升級不掉資料。金鑰檔本身請另外備份，不要進 repo。

## 六、發一個正式版本

**全程在 GitHub 網頁點完，不用碰指令。** 專案有兩個 workflow，各司其職：

| Workflow | 觸發時機 | 做什麼 |
| --- | --- | --- |
| `Build APK` | push / PR 到 `main`，或手動 | 只編譯並上傳 Artifact，不發布 |
| `Release` | 手動（Actions → Release → Run workflow） | 改版本號 → 編譯 → 簽名 → 建 tag → 發布 Release |

### 發版步驟（網頁版）

1. 進倉庫 **Actions** 分頁，左側選 **Release**
2. 點右上角 **Run workflow** → 填 `v1.0.0`（branch 選 `main`）→ 按 **Run workflow**
3. 等約 5–10 分鐘，完成後到 **Releases** 頁面就能看到

`Release` workflow 會自動做完這些事：

1. 把 `build.gradle.kts` 的 `versionName` 改成 `1.0.0`、`versionCode` 改成該次 run 編號，並 commit 回 main
2. 編譯 Debug + Release
3. 用你的金鑰簽名（沒設 secret 就用測試金鑰，並在 log 裡警告）
4. 產生 `SHA256SUMS.txt`
5. 建立並推送 `v1.0.0` tag（若已存在則更新該 Release，不會報錯）
6. 發布 Release，附上兩個 APK + 校驗檔

### Release 正文從哪來

優先用專案根目錄的 `RELEASE_NOTES_<tag>.md`，找不到就用 commit 摘要；兩種情況都會**自動在末尾追加 SHA-256 校驗值表格**，不用手動填。repo 內已附上 `RELEASE_NOTES_v1.0.0.md`（中英雙語）。

日後發新版：複製一份改名成 `RELEASE_NOTES_v1.1.0.md` 再跑 workflow 即可。

### 仍然想用指令？

```bash
git tag v1.0.0 && git push origin v1.0.0
```

這樣只會觸發 `Build APK`（產 Artifact），**不會**自動發布。要發布還是要跑 `Release` workflow，兩條路不要混用，否則會重複。

## 七、想在本機編譯

需要 Android Studio（Ladybug 以上）或 JDK 17 + Android SDK 34：

```bash
./gradlew assembleDebug      # 沒 wrapper 就先跑：gradle wrapper
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 八、另一條更快路線：直接 Fork 成熟客戶端

如果你想要的是「功能完整、馬上能用的商店」而不是自己寫，Fork 這兩個之一更划算，它們本身就有現成的 CI：

- **Droid-ify** `https://github.com/Droid-ify/client` — 介面現代、同步快、支援 Shizuku/Root 靜默安裝
- **Neo Store** `https://github.com/NeoApplications/Neo-Store` — 內建約 100 個倉庫、隱私面板、篩選器最完整

Fork 之後照樣可以在 Actions 裡產出你簽名的 APK。

## 九、已知限制

- **無法完全靜默安裝**：第三方應用拿不到 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`，所以每次安裝都會彈一次系統確認。要真正的背景靜默更新，需要 Shizuku、Root，或把 BenStore 刷成系統應用。
- **不做簽名比對**：更新時只比版本碼，沒檢查 APK 簽名是否與已安裝的一致。若某應用在倉庫裡是 F-Droid 簽名、而你裝的是開發者簽名版，安裝會失敗並在通知裡顯示「簽名不同」。
- **沒有 Nearby 分享、沒有倉庫指紋校驗**：索引走 HTTPS，但沒驗證 F-Droid 的 PGP 簽章。
- **APK 與索引都放在 cache 目錄**：系統清理空間時會被刪掉，重新開啟會再抓一次。
- **只追蹤「同一個倉庫裡找得到」的應用**：若你裝的應用同時存在於多個倉庫，只會比對目前選定的那個倉庫。

授權：GPL-3.0-or-later（沿用 F-Droid 客戶端的 Copyleft 精神）。
