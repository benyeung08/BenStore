# BenStore

一個**完全由你掌控的 Android 應用商店**：讀取你自己維護的 `repo.json`，列出應用、顯示圖示與版本、下載 APK 並安裝；還會在背景比對已安裝版本、通知你有更新、支援批次一鍵更新。

**不依賴 F-Droid。** 資料源是一份你自己的 JSON，放在 GitHub Raw、Pages 或任何 HTTPS 位置都行；上架什麼、給什麼版本、從哪裡下載，全部你說了算。

**預設公開**：裝了就能用，不用登入。想改成「只有指定帳號能用」，把 `PUBLIC_MODE` 設成 `false` 即可（見第四章）。

Kotlin + Jetpack Compose，單模組約 2000 行，Push 到 GitHub 就由 Actions 自動編譯、簽名、打包 APK。

**功能一覽**

| 功能 | 說明 |
| --- | --- |
| 自訂資料源 | 指向自己的 `repo.json`，App 內「設定」可隨時改網址 |
| 公開／限定模式 | 總開關：`PUBLIC_MODE = true` 時免登入、全公開（預設） |
| Google 帳號登入 | Credential Manager 登入，可限定哪些帳號能用 BenStore |
| 帳號分級顯示 | `repo.json` 每個應用可指定 `allowedEmails`，不同帳號看到不同清單 |
| 瀏覽 / 搜尋 / 分類 | 依名稱、包名、簡介搜尋，分類篩選（分類直接讀 JSON） |
| 下載安裝 | `PackageInstaller` Session 安裝，不跳出應用程式 |
| SHA-256 驗證 | JSON 裡填了 `sha256` 就會在下載後驗證，不符直接報錯 |
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
- 首次開啟會載入預設資料源。若網址還是範例的 `<your-account>`，會載入失敗——這是正常的，進「設定」改成你自己的 `repo.json` 網址即可（見下一節）。
- 點進任一個應用 → 「下載並安裝」→ 第一次會跳出系統授權頁，勾選「允許來自此來源」後再按一次即可。

## 三、資料源格式（repo.json）

BenStore 讀一份你自己維護的 JSON。放在 GitHub Raw、Pages、或任何 HTTPS 公開可讀的位置都行。

```json
{
  "meta": { "name": "BenStore", "updated": 1730000000000 },
  "tiers": {
    "beta": { "label": "測試組", "hashes": ["存取碼的 SHA-256，見第五節"] }
  },
  "apps": [
    {
      "packageName": "com.example.app",
      "name": "範例應用",
      "summary": "一句話簡介",
      "description": "完整說明，可用 \\n 換行",
      "icon": "https://.../icon.png",
      "categories": ["工具", "網路"],
      "author": "作者",
      "license": "GPL-3.0",
      "webSite": "https://...",
      "sourceCode": "https://github.com/...",
      "requiredTiers": ["beta"],
      "versions": [
        {
          "versionName": "1.2.0",
          "versionCode": 120,
          "url": "https://.../app-1.2.0.apk",
          "size": 1234567,
          "minSdk": 26,
          "sha256": "選填，填了下載後會驗證"
        }
      ]
    }
  ]
}
```

**欄位說明**

| 欄位 | 必填 | 說明 |
| --- | --- | --- |
| `packageName` | ✅ | 必須與 APK 裡的包名完全一致，否則無法比對更新 |
| `name` | ✅ | 顯示名稱 |
| `versions[].url` | ✅ | APK 直連網址 |
| `versions[].versionCode` | ✅ | 整數，比更新的依據；同一應用可放多版，取最大者為最新 |
| `versions[].versionName` | 建議 | 顯示用，如 `1.2.0` |
| `versions[].size` | 建議 | bytes，沒填就顯示「未知大小」 |
| `versions[].minSdk` | 建議 | 沒填當 0；高於裝置版本會自動跳過 |
| `versions[].sha256` | 選填 | 填了會在下載後驗證，不符就刪除並報錯 |
| `versions[].requiresAuth` | 選填 | `true` 表示需登入 GitHub 才能下載 |
| `versions[].githubAsset` | 選填 | 私有 Release 資產定位（見下方）；填了會自動視為 `requiresAuth` |
| `allowedEmails` | 選填 | 陣列。留空/省略 = 所有已授權帳號都看得到；有填 = 只有列出的 email 看得到（需登入，見第六節） |
| `requiredTiers` | 選填 | 陣列。留空/省略 = 所有人看得到；有填 = 只有持存取碼解鎖該分級者看得到（見第五節） |
| `tiers`（頂層） | 選填 | 分級定義，`hashes` 放存取碼的 SHA-256。用 `tools/gen_access_code.py` 產生 |
| `icon` | 建議 | 圖示網址；沒填就顯示名稱首字 |
| 其餘 | 選填 | 純顯示用，可省略 |

> `requiredTiers` 與 `allowedEmails` **任一通過即可看見**，可並存。
> 兩個都沒寫 = 所有人可見。

```json
{
  "packageName": "com.example.internal",
  "name": "內部工具",
  "allowedEmails": ["you@gmail.com", " teammate@gmail.com"],
  "versions": [ ... ]
}
```

### 私有應用（需登入）

APK 放在私有倉庫的 Release 時，一般下載連結會回 404。改用 `githubAsset`：

```json
{
  "packageName": "com.example.internal",
  "name": "內部工具",
  "versions": [{
    "versionName": "0.9.0",
    "versionCode": 90,
    "url": "",
    "requiresAuth": true,
    "githubAsset": { "owner": "<owner>", "repo": "<repo>", "assetId": 123456789 }
  }]
}
```

BenStore 會帶 token 打 `GET /repos/{owner}/{repo}/releases/assets/{assetId}`，
並用 `Accept: application/octet-stream` 取得檔案。

`assetId` 這樣查：

```bash
gh api repos/<owner>/<repo>/releases/latest --jq '.assets[] | {name, id}'
```

### 用腳本產生（推薦）

`size` 和 `sha256` 手填最容易錯，用附的腳本自動補：

```bash
# 1. 編輯 repo/source.json（網址填 APK 連結或本機路徑，size/sha256 可省略）
# 2. 產生 repo/repo.json
python3 tools/build_repo.py
```

腳本會自動計算每個 APK 的 `size` 與 `sha256`（網址會下載來算，本機路徑直接讀檔）、補上 `updated` 時間戳。

### 讓 App 讀到它

三種方式，選一種：

1. **App 內設定**（最快）：設定 → 資料來源 → 貼上網址 → 儲存並載入
2. **改預設值**：`RepoStore.kt` 的 `DEFAULT_REPO`
3. **GitHub Raw**：把 `repo/repo.json` 推到 repo，網址形如
   `https://raw.githubusercontent.com/<帳號>/BenStore/main/repo/repo.json`

> 用 `raw.githubusercontent.com` 要注意：GitHub 對 raw 有快取，更新 JSON 後可能幾分鐘才生效。要即時生效就改用 GitHub Pages。

## 四、公開模式／限定模式（目前：限定）

BenStore 有總開關，在 `app/build.gradle.kts`：

```kotlin
buildConfigField("Boolean", "PUBLIC_MODE", "true")
```

| | `PUBLIC_MODE = true`（預設） | `PUBLIC_MODE = false` |
| --- | --- | --- |
| 誰能用 | 任何人，免登入 | 需登入且在 `ALLOWED_EMAILS` 內 |
| 看到什麼 | 全部應用 | 依 `allowedEmails` 分級 |
| 登入設定 | 被忽略（仍可登入拿額外能力） | 生效 |

**目前已設為強制登入模式（`PUBLIC_MODE = false`）**：沒登入就停在門禁畫面，
必須用下列三種方式之一進入：

| 方式 | 需要設定什麼 |
| --- | --- |
| **BenStore 本機帳號** | **零設定**，現場註冊帳號密碼即可（預設可用） |
| Google 帳號 | `local.properties` 填 `WEB_CLIENT_ID` |
| GitHub 帳號 | 設定頁填 GitHub OAuth Client ID |

三種都沒設定時，App **仍然會擋**（不會自動放行），並由本機帳號作為唯一入口。
這避免了「以為關了門其實沒關」的漏洞。

### 要改回公開模式

1. 把 `app/build.gradle.kts` 的 `PUBLIC_MODE` 改成 `true`
2. 重新編譯

公開模式下任何人都能瀏覽並安裝全部應用，不做門禁也不做分級，
`WEB_CLIENT_ID` 與 `ALLOWED_EMAILS` 會被完全忽略。

### 要讓別人也裝得到

公開模式只管 App 本身，「別人能不能下載 APK」是另一件事，三種做法：

| 做法 | 怎麼做 | 適合 |
| --- | --- | --- |
| GitHub Release | 跑 `Release` workflow，Releases 頁面公開 | 最簡單，推薦 |
| 倉庫轉公開 | Settings → Danger Zone → Change visibility | 連原始碼一起公開（GPL 要求） |
| 自己 hosting | 把 APK 放任何 HTTPS 位置 | 不想公開原始碼時 |

> 注意：專案用 GPL-3.0，若你要分發 APK 給別人，原始碼也必須一併提供。
> 公開 GitHub 倉庫是最省事的合規做法。

### 資料源也要公開

若 `repo.json` 放在私有倉，別人即使裝了 BenStore 也讀不到清單。公開模式要搭配公開資料源：

- 把 `repo/repo.json` 推到公開倉庫，用 GitHub Raw 或 Pages
- 或填 `DEFAULT_REPO` 指向公開位置

## 五、不用 OAuth Client ID 的方案

### 先講清楚一件事

**Google 帳號登入在技術上繞不開 OAuth Client ID。** 那是 Google 身份服務的硬性要求，
沒有任何客戶端能跳過——`GetGoogleIdOption` 在 `serverClientId` 為空時會直接拋例外。
所以「要 Google 登入但不建 Client ID」是做不到的，別浪費時間找捷徑。

但你要的**「識別身分 + 私有應用授權」有別的路**，而且都不用申請任何東西。

### 四種方案比較

| 方案 | 要建什麼 | 能否偽造 | 能否設到期 | 綁定身分 | 適合 |
| --- | --- | --- | --- | --- | --- |
| **A. 離線授權（License）** | RSA 金鑰對（一條指令） | ❌ 不能 | ✅ 可 | ✅ email | **要發給外部使用者** |
| **B. 存取碼** | 無（跑腳本產生） | ✅ 能（拿到就轉傳） | ❌ 不可 | ❌ | 內部小圈子、快速試 |
| **C. GitHub 權杖 PAT** | 無（使用者自己產生） | ❌ 不能 | ✅ 可（GitHub 端設） | ✅ GitHub 帳號 | 本來就用 GitHub |
| **D. 不公開 repo.json** | 無 | — | — | — | 只求「別被人找到」 |

**A 和 B 都在專案裡做好了，可並存；C 也已接好；D 不需要任何程式碼。**

> 提醒：以上全部都在 `PUBLIC_MODE = false` 時才生效。
> 預設是 `true`（公開商店），此時所有分級失效、全部應用一律顯示。

### 怎麼選

- **要發給不特定的外部人士** → A（可設到期日、可綁 email、無法偽造）
- **給幾個同事試用，最省事** → B
- **你自己或團隊本來就用 GitHub** → C（順帶能讀私有 repo）
- **只是不想被搜到** → D，但要知道這不是認證

---

## 五之一、離線授權 License（最強，推薦）

用 RSA 簽名做到**無法偽造**的授權：你用私鑰簽一張授權碼，App 用內嵌的公鑰驗簽。
全程離線，不需要後端、不需要聯網驗證、不需要 OAuth。

### 步驟一：生成金鑰對（只需一次）

```bash
python3 tools/license_key.py init
```

會印出一行可直接貼的公鑰設定：

```
buildConfigField("String", "LICENSE_PUBKEY", "MIIBIjANBgkq...")
```

把這行貼進 `app/build.gradle.kts`，重新編譯。

> 私鑰存在 `tools/license_private.pem`，**絕對不要上傳**。
> `.gitignore` 已包含它。洩漏私鑰 = 任何人都能偽造授權碼。

### 步驟二：簽發授權碼

```bash
python3 tools/license_key.py issue \
    --email customer@example.com \
    --tiers beta \
    --days 365
```

輸出：

```
對象 email : customer@example.com
分級 tiers : beta
有效期限   : 365 天（到 2027-10-26）
------------------------------------------------------------
eyJlbWFpbCI6ImFAYi5jb20iLCJ0aWVycyI6WyJiZXRhIl0sImlhdCI6MTc5....
------------------------------------------------------------
```

那串就是授權碼，交給使用者。

想確認自己簽了什麼：`python3 tools/license_key.py show <授權碼>`

### 步驟三：使用者啟用

BenStore → 設定 → **授權碼** → 貼上 → 啟用。

會顯示綁定的 email、解鎖的分級、還剩幾天。過期後背景檢查會自動失效。

### repo.json 怎麼寫

和存取碼共用同一套 `requiredTiers`：

```json
{
  "apps": [{
    "packageName": "com.example.internal",
    "name": "內部工具",
    "requiredTiers": ["beta"],
    "versions": [...]
  }]
}
```

差別只在「授權從哪裡來」：
- 存取碼 → `repo.json` 頂層 `tiers` 放 SHA-256
- License → 公鑰內嵌在 App，授權碼由你簽發

**兩者任一命中即可看見**，不用二選一。

### 安全性

| 情境 | 是否安全 |
| --- | --- |
| 有人篡改授權碼內容（改分級、改到期日） | ✅ 驗簽失敗，拒絕 |
| 有人自己編一串授權碼 | ✅ 沒有私鑰，簽不出來 |
| 拿到授權碼的人轉傳給別人 | ❌ 仍可（除非你綁 email 並人工核對） |
| 使用者改系統時間繞過到期 | ❌ 可能（離線驗證的固有極限） |

要完全防轉傳與防改時間，必需有後端線上驗證。這是離線方案的取捨。

### 與存取碼的取捨

| | 存取碼 | License |
| --- | --- | --- |
| 建設成本 | 零（跑腳本） | 低（生一組金鑰） |
| 撤銷方式 | 改 repo.json 的 hashes | 改 repo.json 或等到期 |
| 換新碼要重新編譯 App | 不用 | 不用 |
| 洩漏後果 | 改 JSON 即可 | 私鑰洩才嚴重（JSON 無害） |

---

## 五之二、存取碼分級（最省事）

如果你連金鑰都不想生，**用這套**。

### 怎麼運作

```
1. 你用 tools/gen_access_code.py 產生一組碼
     → 得到 CODE（給使用者）和 HASH（放進 repo.json）
2. repo.json 頂層寫 tiers，每個分級放 HASH
3. 私有應用寫 "requiredTiers": ["beta"]
4. 使用者在 BenStore 設定頁輸入 CODE
     → App 算出 SHA-256 → 比對命中哪個分級 → 解鎖
```

只存雜湊不存明文，所以 `repo.json` 就算完全公開，也無法反推存取碼。

### 步驟一：產生存取碼

```bash
python3 tools/gen_access_code.py --tier beta --label "測試組"
```

會印出：

```
存取碼 CODE : SSJR-GYW9-35AN-VHKP
雜湊   HASH : 8af74994c3e84be6b889d29ff3b9cc051ebda8b9075a3668feba24c9f96a06f0
```

**CODE 只出現這一次**，程式不保存，請另外交給使用者（任何管道都行）。
加 `--json` 會直接印出可貼進 `repo.json` 的片段。

### 步驟二：寫進 repo.json

```json
{
  "tiers": {
    "beta": { "label": "測試組", "hashes": ["8af74994c3e8..."] }
  },
  "apps": [
    {
      "packageName": "com.example.internal",
      "name": "內部工具",
      "requiredTiers": ["beta"],
      "versions": [ { "versionName": "1.0", "versionCode": 1, "url": "https://..." } ]
    }
  ]
}
```

`hashes` 是陣列，同一個分級可以放多組碼（給不同人）。

### 步驟三：使用者輸入

BenStore → 設定 → **存取碼** → 貼上 CODE → 兌換。
沒輸入的人連列表都看不到這些應用（不是「看到但不能裝」）。

### 這套的強度到哪

| 情境 | 是否安全 |
| --- | --- |
| `repo.json` 公開在 GitHub | ✅ 只有雜湊，反推不出碼 |
| 防止沒碼的人看到/下載私有應用 | ✅ 足夠 |
| 防止拿到碼的人轉傳給別人 | ❌ 做不到，碼就是字串 |
| 抵禦有心的攻擊者 | ❌ 這不是密碼學強認證 |

要能撤銷、能綁裝置、能防轉傳，就得有後端 + 真正的帳號系統。存取碼是**零成本的 80 分解法**。

### 注意

`PUBLIC_MODE = true`（預設）時**所有分級都會失效**，全部應用一律顯示。
要讓存取碼生效，必須把 `app/build.gradle.kts` 的 `PUBLIC_MODE` 改成 `false`。

---

## 五之三、GitHub 權杖（PAT）—— 也完全不用建 OAuth App

不想用 Device Flow 申請 OAuth App？讓使用者自己產生權杖貼進來即可。

**使用者的動作**（你這邊零建設）：

1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token，勾選 `read:user`、`user:email`（要讀私有 repo 再加 `repo`）
3. 複製那串 `ghp_...`

**在 App 裡**：設定 → 帳號 → 貼進「GitHub 權杖」→ 用權杖登入。

App 會拿這組 token 打 GitHub API 取得身分（login、email），之後：

- 門禁：`ALLOWED_EMAILS` 可填 `github:yourlogin`
- 分級：應用的 `allowedEmails` 可填他的 GitHub email
- 讀私有 repo：同一個 token 直接用於下載

token 存在 `EncryptedSharedPreferences`（AES-256-GCM，金鑰由系統 Keystore 保管），
不是明文 SharedPreferences。

> 注意：PAT 等同於帳號的部分權限，建議用 **fine-grained token** 並只給需要的 repo，
> 且設到期日。這點要提醒使用者。

---

## 五之四、最弱但最簡單：不公開 repo.json

如果目的只是「不要被陌生人找到」，那根本不需要任何認證機制：

- `repo.json` 放在不公開的網址（例如帶隨機路徑的物件儲存）
- APK 也放同一個位置
- 知道網址的人就能用

這不是認證，只是「隱匿式安全」（security by obscurity）。
連結一旦外流就完全失效，且無法撤銷單一人的存取權。
適合自用、或臨時分發，不適合當正式機制。

---

## 六、帳號登入（Google / GitHub / 本機帳號）

**限定模式下才需要**——公開模式不用設這些。BenStore 支援**三種登入方式，共用同一套帳號體系**，使用者任選一種即可，效果一致。

| 方式 | 要不要設定 | 說明 |
| --- | --- | --- |
| **BenStore 本機帳號** | **零設定** | 現場註冊帳號密碼，只存在該手機上。沒設 OAuth 時就靠它進入 |
| Google | 填 `WEB_CLIENT_ID` | 官方通道 + 網頁 OAuth 備援 |
| GitHub | 填 Client ID | Device Flow 或貼 PAT |

> 這套與上面的存取碼分級**可以並存**，任一通過即可看見應用。
> 若你不想碰 OAuth，直接用存取碼或本機帳號就好，Google / GitHub 兩節可以跳過。

| | Google | GitHub |
| --- | --- | --- |
| 技術 | Credential Manager，**失敗自動降級到瀏覽器網頁 OAuth** | Device Flow（免 client secret） |
| 身份依據 | email | 主要 email，拿不到時用 `github:<login>` |
| 需要設定 | `WEB_CLIENT_ID`（編譯期） | OAuth App Client ID（執行期，設定頁填） |
| 額外好處 | 系統帳號選擇器，一鍵完成 | 同一個 token 可讀私有 repo |

預設**都關閉**：沒設定任何一種就不會出現登入頁，商店照常可用。設定任一種才啟用門禁。

### 設定 Google（只需做一次）

1. 到 [Google Cloud Console](https://console.cloud.google.com/apis/credentials) 建立 OAuth 2.0 用戶端 ID
2. **應用程式類型選「網頁應用程式」** —— 這點很關鍵，選「Android」會出現
   `activity is cancelled by the user` 或 `DEVELOPER_ERROR` 而無法登入
3. 複製那串 `xxxxx.apps.googleusercontent.com`，**不要直接改 `build.gradle.kts`** ——
   這些值改成外部注入，避免被 commit 進 repo。複製範例檔來填：

```bash
cp local.properties.example local.properties
```

```properties
# local.properties（已在 .gitignore，不會進 repo）
WEB_CLIENT_ID=你的ID.apps.googleusercontent.com
# 門禁：允許使用 BenStore 的帳號，逗號分隔；留空 = 不限制
ALLOWED_EMAILS=you@gmail.com, github:teammate
GOOGLE_CLIENT_SECRET=      # 通常留空
```

4. 重新編譯即可

**三種注入方式，優先順序由高到低**（見 `app/build.gradle.kts` 的 `sec()`）：

| 方式 | 用途 |
| --- | --- |
| `gradle -PWEB_CLIENT_ID=xxx` | 臨時測試 |
| 環境變數 `WEB_CLIENT_ID=xxx` | **CI**：到 Settings → Secrets 新增同名 secret，workflow 會自動帶入 |
| `local.properties` | 本機開發（已在 `.gitignore`） |

都沒設也能編譯，只是登入功能停用——App 會顯示設定提示，不會崩潰。

> 不需要 `google-services.json`、不需要 Firebase。
> 但要拿到 email，Web Client ID 是**必填的**——`GetGoogleIdOption` 在
> `serverClientId` 為空時會直接拋 `IllegalArgumentException`，沒有繞過的方法。

### Google 的兩條登入通道（自動擇一）

BenStore 會**先試官方通道，失敗才自動改用網頁通道**，使用者只會看到一次登入流程，不用自己選。

```
1. Credential Manager（官方）
   系統帳號選擇器，一鍵完成，體驗最好
   ⚠️ 需要額外登記「Android 型別」用戶端（包名 + SHA-1），見下
      │
      │ 失敗（沒 Play 服務 / 帳號不在系統清單 / 使用者取消 / 設定有誤）
      ▼
2. GoogleWebAuth（網頁 OAuth + PKCE）
   Chrome 自訂分頁開 Google 授權頁 → 導回本機 loopback 埠 → 換 id_token
   ✅ 不需要包名，也不需要 SHA-1
```

**關於 SHA-1（這點最容易踩坑）**

兩條通道的需求不同，別混為一談：

| 通道 | 程式碼裡傳的 Client ID | 還需要 Android 型別用戶端（包名 + SHA-1）嗎 |
| --- | --- | --- |
| Credential Manager | Web 型別 | **需要**。Google 用它來驗證 App 身份 |
| 網頁 OAuth（PKCE） | Web 型別 | 不需要 |

也就是說：**只建 Web 用戶端的話，官方通道會失敗**（典型錯誤是 `[16] Account reauth failed`、
`DEVELOPER_ERROR` 或 `Cannot find a matching credential`），但會自動降級到網頁通道，所以功能仍然可用。

想讓官方通道也能跑，在同一個 GCP 專案下**再建一個「Android」型別的 OAuth 用戶端**，填：

- 套件名稱：`com.benstore.app`（要與 `applicationId` 完全一致）
- SHA-1 憑證指紋：

```bash
# Debug（Android Studio 直接跑的版本）
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android | grep SHA1

# Release（你自己簽名用的 keystore）
keytool -list -v -keystore /path/to/your.jks -alias benstore | grep SHA1
```

> ⚠️ **與 CI 簽章的連動**：SHA-1 是簽章金鑰的指紋。若你還沒設定 `KEYSTORE_BASE64`，
> 每次 CI 產生的測試金鑰都不同，SHA-1 也跟著變，官方通道在正式版上會一直失敗。
> 這正是網頁通道存在的價值——它不受簽章影響。要長期穩定，請先照第五章設定固定金鑰，
> 再把那一把的 SHA-1 登記進去。

**網頁通道要多做一步**：Google 規定 redirect URI 必須逐字相符（含埠號），所以要在同一個 Web 用戶端底下，把 loopback 位址登記進「已授權的重新導向 URI」：

```
http://127.0.0.1:8765
http://127.0.0.1:8766
http://127.0.0.1:8767
```

（加在「已授權的重新導向 URI」，不是「已授權的 JavaScript 來源」——兩者不同，加錯地方一樣會失敗。）

App 會依序找這三個埠，找到第一個能綁的就用它。只登記 8765 也行，前提是那個埠沒被別的程式佔用。

如果 Google 換 token 時回 `client_secret is missing`，把 Web 用戶端的密鑰填進：

```kotlin
buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"你的密鑰\"")
```

> 放在 App 裡的 secret 不算機密（任何人都能從 APK 拆出來），
> 這條流程真正的保護機制是 PKCE。所以它只是讓 Google 接受請求，不是安全邊界。

**網頁通道的限制**：
- id_token 只在 App 端解出 email 與名稱，**沒有驗簽**。這是識別用途，不是安全邊界——真正的門禁判斷在 `ALLOWED_EMAILS` 與應用分級，而那兩者資料都在本機。
- 授權頁等待時間上限 3 分鐘，逾時會回「沒有收到授權碼」。
- 需要 Activity context 才能開瀏覽器；Compose 的 `LocalContext` 通常是 Activity，所以不用額外處理。

### 設定 GitHub

到 `https://github.com/settings/applications/new` 註冊 OAuth App（勾選 Device Flow），
把 Client ID 填進 BenStore 的「設定 → GitHub 資料源設定」。不用重新編譯。

scope 預設 `repo user:email`：

- `repo` —— 讀私有倉庫的 `repo.json`
- `user:email` —— 取得身份用信箱。**統一帳號體系靠它**：沒有這個 scope 就只能用
  帳號名當身份，跟 Google 帳號的 email 對不起來

只要公開內容、不做分級的話，改成 `read:user user:email` 更精簡。

### BenStore 本機帳號（零設定）

在門禁畫面點「註冊 BenStore 本機帳號」即可，不需要申請任何東西。

- 密碼用 PBKDF2WithHmacSHA256（12 萬次迭代 + 獨立隨機鹽）雜湊後，
  存進 `EncryptedSharedPreferences`（Keystore 保管金鑰），不存明文
- 帳號只存在**該支手機**上，換機不會同步
- **沒有「忘記密碼」救援機制**（沒有伺服器可以寄信），忘了只能刪帳號重設

想在 `ALLOWED_EMAILS` 名單裡指定本機帳號，寫 `local:帳號名` 或直接寫帳號名都可以。

### 統一身份：門禁與分級怎麼比對

三種登入都收斂成一個 `Account`，門禁與分級用**同一份名單**比對：

| 帳號來源 | `id` 長怎樣 | 名單裡可以怎麼寫 |
| --- | --- | --- |
| Google | `you@gmail.com` | `you@gmail.com` |
| GitHub（有公開信箱） | `you@gmail.com` | `you@gmail.com` |
| GitHub（沒公開信箱） | `github:your-id` | `github:your-id` 或 `your-id` |

所以 `ALLOWED_EMAILS` 和 `repo.json` 的 `allowedEmails` 三種寫法都吃：

```kotlin
buildConfigField("String", "ALLOWED_EMAILS", "\"you@gmail.com, github:teammate, partner@gmail.com\"")
```

### 兩層用途

| 層級 | 設定位置 | 效果 |
| --- | --- | --- |
| 門禁 | `ALLOWED_EMAILS` | 不在清單裡的帳號登入後只看到「未獲授權」，進不了商店 |
| 分級 | `repo.json` 的 `allowedEmails` | 同一份資料源，不同帳號看到不同的應用清單 |

列表頂端會顯示「依你的帳號顯示 N/M 個應用」。

### 安全邊界（務必了解）

這是**單機判定，沒有後端**：

- email 由系統的 Google 帳號選擇器提供，比「使用者自己輸入」可靠得多
- 但 BenStore **沒有驗證 Google ID token 的簽章**，無法密碼學證明「這個 email 真的是本人」
- 在已 root 或可 hook 的環境，理論上可以偽造

適合：防止家人／同事誤用、依帳號切換清單、自家內部工具分發。
**不適合**當作付費內容或敏感資料的唯一防線——那需要在後端驗證 ID token。

要升級成可驗證版本：把 Google 登入取到的 ID token 送到你的後端，
用 Google 的公鑰驗證簽章與 `aud` / `iss` / `exp` 後再決定放行。
GitHub 那邊則可以用 token 打一次 `GET /user` 來確認身份有效性。

## 七、GitHub 資料源（讀私有 repo）

上一章講的是「用 GitHub 當登入方式」，這一章講「用 GitHub token 讀私有資料源」。
兩者共用同一個 token，所以登入一次就都有了。

### 為什麼用 Device Flow，而不是純 PKCE

直覺上會覺得：行動 App 沒後端，用 PKCE 就對了。但**GitHub 的 OAuth App 在換 token 時仍然強制要求 `client_secret`**，PKCE 並沒有免除它。

GitHub 官方文件原文：

> Client secrets are required to generate user access tokens for your app, **unless your app uses the device flow**.

也就是說，GitHub 確實支援 PKCE（而且建議使用），但它的角色是「即使 secret 洩漏，被攔截的 authorization code 也換不到 token」——是**加一層防護**，不是替代 secret。行動 App 藏不住 secret，只能包進 APK，而任何人都反編譯得出來。

所以這裡預設走 **Device Flow**：GitHub 官方唯一真正免 secret 的流程，client ID 天生就是公開值（它自己什麼都開不了，只能發起一次授權）。

代價是使用者要手動輸入一組短碼，但換來的是「沒有任何密鑰需要藏」。

### 設定步驟（約 3 分鐘）

1. 到 `https://github.com/settings/applications/new` 註冊 **OAuth App**
   - Application name：隨意（使用者授權時會看到）
   - Homepage URL：隨意，例如 `https://example.com`
   - Authorization callback URL：填 `https://github.com/login/device`（Device Flow 不會真的跳轉，但欄位必填）
2. 註冊後，**勾選「Enable Device Flow」並儲存**（預設關閉，沒開會一直失敗）
3. 複製 **Client ID**（形如 `Iv1.xxxx`）。**不要**產生 client secret — Device Flow 用不到。
4. 在 BenStore：設定 → GitHub 資料源設定 → 貼上 Client ID
5. 回到「帳號」區塊點「使用 GitHub 帳號登入」→ 開啟 GitHub → 輸入短碼 → 完成

> 建議把 OAuth App 註冊在**組織**底下而非個人帳號：個人帳號消失，App 也跟著消失。

### Scope 與權限

預設 `repo user:email`：`repo` 才能讀私有倉庫的 release 資產，`user:email` 是統一帳號體系的身份依據。可在設定頁的 Scope 欄位改，或改 `GitHubConfig.kt` 的 `DEFAULT_SCOPE`。

### token 怎麼存

`EncryptedSharedPreferences`（AES-256-GCM，金鑰由系統 Keystore 保管），不是一般 SharedPreferences。token 等同於你 GitHub 帳號的部分權限，明文放在 `/data/data` 下，root 過的機器能直接讀走。

### 一個重要的安全提醒

GitHub 官方明確警告：**public client 是可被冒用的** —— 任何人都能拿你的 client ID 發起授權。

所以**不要**用「使用者有沒有 token」當作你服務的門禁，那擋不住人。真正的門禁應該是**私有倉庫權限**：repo 沒權限就是讀不到，這才是可靠的那一層。

### 登出

設定 → 登出。會清除本機 token，並嘗試呼叫撤銷 API。

注意：GitHub 的撤銷端點需要 Basic 認證（`client_id:client_secret`），Device Flow 沒有 secret，所以撤銷會失敗——這是正常的。要徹底撤銷請到 GitHub → Settings → Applications → Authorized OAuth Apps 手動移除。

## 八、更新功能怎麼運作

```
WorkManager（週期性，開機自動恢復）
   └─ UpdateWorker
        1. 重新抓取 repo.json（不抓新版就永遠比不到新版本）
        2. UpdateScanner.scan()：比對已安裝版本 vs 資料源最新版本
        3. 有更新 → 發通知（或依設定直接下載安裝）

UI「更新」分頁
   └─ 勾選 → 逐一下載 → Installer（PackageInstaller Session）→ 系統確認
         └─ InstallResultReceiver 接收結果 → 發成功／失敗通知
```

幾個實作上的重點：

- **安裝走 Session API 而不是 `ACTION_VIEW`**：好處是不會跳出去瀏覽器/套件安裝程式，可以連續排隊多個，而且能收到安裝結果。`Installer.kt` 裡還保留了 `ACTION_VIEW` 作為降級方案，Session 失敗時自動退回。
- **忽略是「針對版本」**：忽略後存的是 `包名:版本碼`，下個新版本出來會再次提醒，不會從此消失。
- **圖示有兩層快取**：記憶體（LRU 200 張）+ 磁碟（`cacheDir/icons`），滑動列表不會重複下載。
- **APK 也有快取**：下載過的會留在 `cacheDir/apks`，重複安裝同一版本不再重抓；若 `sha256` 與快取不符會自動重下。
- **資料源失敗會退回快取**：抓不到新的 `repo.json` 時，若上次成功過就用舊的，離線也能瀏覽。
- **系統確認不可避免**：第三方應用無法完全靜默安裝（那需要系統簽名的 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`），所以每個更新仍會彈一次確認框。想少彈幾次，就開「自動下載並安裝」以外的模式，一次勾選多個批次處理。

設定頁可調整：資料來源網址、自動檢查開關、間隔（6/12/24 小時）、僅 Wi-Fi、自動下載並安裝、立即檢查、清除忽略清單。

## 九、改造成你自己的商店

| 想改什麼 | 改哪裡 |
| --- | --- |
| 應用名稱 | `app/src/main/res/values/strings.xml` 的 `app_name` |
| 包名（決定能不能跟別的 App 共存） | `app/build.gradle.kts` 的 `applicationId`（目前是 `com.benstore.app`），同步改 `AndroidManifest` 不用動（用了 `${applicationId}`） |
| 預設資料源 | `RepoStore.kt` 的 `DEFAULT_REPO`，或 App 內「設定 → 資料來源」（後者優先） |
| 加多個資料源 | 目前是單一資料源；要多源就把多個 JSON 合併後放在同一個網址 |
| 版本號 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| 預設檢查間隔 | `Prefs.kt` 的 `K_INTERVAL` 預設值（12 小時） |
| 圖示 | 見下方「圖示規格」 |

**原始碼模組對照**

| 檔案 | 負責什麼 |
| --- | --- |
| `Models.kt` | 定義並解析 `repo.json`（應用、版本、資料源） |
| `RepoStore.kt` | 抓 `repo.json`、圖示快取、下載 APK（含 SHA-256 驗證、私有資產）、安裝權限 |
| `GitHubAuth.kt` | Device Flow 登入、PKCE 換 token、取得使用者資料 |
| `AuthSettings.kt` | token 加密儲存、client ID / scope 設定 |
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

## 十、用自己的簽名金鑰（重要）

CI 預設會自動產生一把「測試金鑰」。測試金鑰每次跑 Actions 都不同，**換機器後無法直接覆蓋升級**。要長期用，請：

```bash
keytool -genkeypair -v -keystore my-release.jks -alias benstore \
  -keyalg RSA -keysize 2048 -validity 10950
base64 -w 0 my-release.jks > my-release.jks.b64
```

到 GitHub 倉庫 **Settings → Secrets and variables → Actions** 新增 4 個 secret：
`KEYSTORE_BASE64`、`KEYSTORE_ALIAS`、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_PASSWORD`。

下次 Actions 就會用你的金鑰簽名，升級不掉資料。金鑰檔本身請另外備份，不要進 repo。

## 十一、發一個正式版本

**全程在 GitHub 網頁點完，不用碰指令。** 專案有兩個 workflow，各司其職：

| Workflow | 觸發時機 | 做什麼 |
| --- | --- | --- |
| `Build APK` | push / PR 到 `main`，或手動 | 只編譯並上傳 Artifact，不發布 |
| `Release` | 手動（Actions → Release → Run workflow） | 改版本號 → 編譯 → 簽名 → 建 tag → 發布 Release |

### 發版步驟（網頁版）

1. 進倉庫 **Actions** 分頁，左側選 **Release**
2. 點右上角 **Run workflow** → 填 `v1.0.1`（branch 選 `main`）→ 按 **Run workflow**
3. 等約 5–10 分鐘，完成後到 **Releases** 頁面就能看到

`Release` workflow 會自動做完這些事：

1. 把 `build.gradle.kts` 的 `versionName` 改成 `1.0.1`、`versionCode` 改成該次 run 編號，並 commit 回 main
2. 編譯 Debug + Release
3. 用你的金鑰簽名（沒設 secret 就用測試金鑰，並在 log 裡警告）
4. 產生 `SHA256SUMS.txt`
5. 建立並推送 `v1.0.1` tag（若已存在則更新該 Release，不會報錯）
6. 發布 Release，附上兩個 APK + 校驗檔

### Release 正文從哪來

優先用專案根目錄的 `RELEASE_NOTES_<tag>.md`，找不到就用 commit 摘要；兩種情況都會**自動在末尾追加 SHA-256 校驗值表格**，不用手動填。repo 內已附上 `RELEASE_NOTES_v1.0.1.md`（中英雙語）。

日後發新版：複製一份改名成 `RELEASE_NOTES_v1.1.0.md` 再跑 workflow 即可。

### 仍然想用指令？

```bash
git tag v1.0.1 && git push origin v1.0.1
```

這樣只會觸發 `Build APK`（產 Artifact），**不會**自動發布。要發布還是要跑 `Release` workflow，兩條路不要混用，否則會重複。

## 十二、想在本機編譯

需要 Android Studio（Ladybug 以上）或 JDK 17 + Android SDK 34：

```bash
./gradlew assembleDebug      # 沒 wrapper 就先跑：gradle wrapper
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 十三、另一條更快路線：直接 Fork 成熟客戶端

如果你其實想要「有人幫你維護好幾千個開源應用」，那 F-Droid 生態的成熟客戶端更划算，它們本身就有現成的 CI：

- **Droid-ify** `https://github.com/Droid-ify/client` — 介面現代、同步快、支援 Shizuku/Root 靜默安裝
- **Neo Store** `https://github.com/NeoApplications/Neo-Store` — 內建約 100 個倉庫、隱私面板、篩選器最完整
- **Obtainium** `https://github.com/ImranR98/Obtainium` — 直接追 GitHub / GitLab 等來源的 Release，不走 F-Droid 倉庫

BenStore 的定位不同：**應用清單完全由你自己寫**，適合分發內部工具、自架服務的客戶端、或不方便上架的應用。

## 十四、已知限制

- **無法完全靜默安裝**：第三方應用拿不到 `UPDATE_PACKAGES_WITHOUT_USER_ACTION`，所以每次安裝都會彈一次系統確認。要真正的背景靜默更新，需要 Shizuku、Root，或把 BenStore 刷成系統應用。
- **不做簽名比對**：更新時只比版本碼，沒檢查 APK 簽名是否與已安裝的一致。若資料源裡的 APK 與你已安裝的版本簽名不同，安裝會失敗並在通知裡顯示「簽名不同」。
- **沒有資料源簽章驗證**：走 HTTPS，但沒驗證 JSON 的數位簽章。若你的 `repo.json` 放在不受信任的環境，建議只在 JSON 的 `url` 裡填 SHA-256 已確認的 APK。
- **單一資料源**：同時只能讀一份 `repo.json`。要多源就自己合併成一份。
- **APK、圖示、JSON 都放在 cache 目錄**：系統清理空間時會被刪掉，重新開啟會再抓一次。
- **Client ID 可被冒用**：GitHub public client 的先天限制，任何人都能拿你的 client ID 發起授權。不要把「有沒有 token」當門禁，門禁要擋在私有倉庫權限上。
- **登出無法遠端撤銷**：Device Flow 沒有 client secret，而 GitHub 的撤銷端點需要 Basic 認證。本機 token 會清乾淨，但伺服器端的授權要手動到 GitHub 移除。
- **Web Flow + PKCE 需要 secret**：程式碼有保留這條路（`GitHubAuth.exchangeCode`），但 GitHub 強制要求 `client_secret`，行動端必須把它包進 APK。只有你自己要用、且不介意 secret 在 binary 裡時才建議開。
- **帳號登入只做單機判定**：身份由 Google／GitHub 提供，但 BenStore 沒有驗證 token 簽章，無法密碼學證明身份。詳見第四章的安全邊界。
- **`ALLOWED_EMAILS` 是編譯期常數**：改用 Google 名單要重新編譯並安裝新版 APK，無法遠端更新。要動態管理就得配上後端。
- **GitHub 拿不到信箱時身份退化**：使用者若沒公開 email，`/user/emails` 也取不到，身份會退回 `github:<login>`。這時 `ALLOWED_EMAILS` 要寫 `github:xxx` 或純帳號名才匹配得到。
- **兩種登入共用一個 session**：同時登入不可能，後登入的會覆蓋前一個（兩邊 token 都清掉）。

授權：GPL-3.0-or-later（沿用 F-Droid 客戶端的 Copyleft 精神）。
