plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 讀取敏感設定，優先順序：
 *   1. Gradle 屬性（-PWEB_CLIENT_ID=xxx）
 *   2. 環境變數（CI 用 GitHub Secrets 注入）
 *   3. local.properties（本機開發；已在 .gitignore，不會進 repo）
 *   4. 都沒有 → 空字串（功能停用，App 顯示設定提示而非崩潰）
 *
 * 這樣 Client ID / 允許清單就不必硬編進版本控制。
 */
fun sec(key: String): String {
    val fromProp = project.findProperty(key)?.toString()
    if (!fromProp.isNullOrBlank()) return fromProp.trim()

    val fromEnv = System.getenv(key)
    if (!fromEnv.isNullOrBlank()) return fromEnv.trim().replace("\"", "\\\"")

    val f = rootProject.file("local.properties")
    if (f.exists()) {
        val props = java.util.Properties()
        f.inputStream().use { props.load(it) }
        val v = props.getProperty(key)
        if (!v.isNullOrBlank()) return v.trim().replace("\"", "\\\"")
    }
    return ""
}

android {
    namespace = "com.benstore.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.benstore.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // ---- 公開模式（總開關）----
        // true  = 任何人都能用，不用登入，所有應用全部顯示（公開商店）
        // false = 必須登入，且依 ALLOWED_EMAILS / allowedEmails 做門禁與分級
        //
        // 這個開關優先於下面所有登入設定：設成 true 時，
        // WEB_CLIENT_ID 與 ALLOWED_EMAILS 會被完全忽略。
        buildConfigField("Boolean", "PUBLIC_MODE", "true")

        // ---- Google 帳號登入 ----
        // 值不寫死在這裡，改從外部注入（見檔案最下方的 sec() 函式）：
        //   - 本機：local.properties（已在 .gitignore，不會進 repo）
        //   - CI  ：GitHub Secrets → workflow 的環境變數
        // 這樣就不用把 Client ID / 允許清單硬編進版本控制。
        //
        // 1. WEB_CLIENT_ID：到 Google Cloud Console 建立「網頁應用程式」型別的
        //    OAuth 2.0 用戶端 ID。注意要用 Web 型別，Android 型別會失敗。
        //    沒填的話 App 會顯示設定提示，不會崩潰。
        //    兩條登入通道都傳這一個 ID；但官方 Credential Manager 通道另外還需要
        //    在同一個 GCP 專案建一個「Android」型別用戶端（包名 + SHA-1）才能通過驗證，
        //    否則會噴 [16] Account reauth failed 並自動降級到網頁通道。
        // 2. ALLOWED_EMAILS：允許使用 BenStore 的帳號，逗號分隔。
        //    留空 = 任何帳號都能登入（只做識別，不做門禁）。
        // 3. GOOGLE_CLIENT_SECRET：只有在用「網頁 OAuth 降級通道」且 Google 要求
        //    secret 時才需要。官方通道有填 secret 也能動（多傳一個欄位而已）。
        //    注意：裝在 App 裡的 secret 不算機密，PKCE 才是這條流程的保護機制。
        buildConfigField("String", "WEB_CLIENT_ID", "\"${sec("WEB_CLIENT_ID")}\"")
        buildConfigField("String", "ALLOWED_EMAILS", "\"${sec("ALLOWED_EMAILS")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${sec("GOOGLE_CLIENT_SECRET")}\"")

        // ---- 離線授權（License）----
        // 用 tools/license_key.py init 產生金鑰對後，把公鑰貼到這裡。
        // 留空 = 停用這套機制（設定頁不會顯示「授權碼」區塊）。
        // 私鑰永遠留在你手上，不要進 repo。
        buildConfigField("String", "LICENSE_PUBKEY", "\"${sec("LICENSE_PUBKEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // CI 會透過環境變數（KS_FILE / KS_STORE_PASS / KS_ALIAS / KS_KEY_PASS）
    // 提供簽名金鑰；有的話 Gradle 就直接產出已簽名的 app-release.apk，
    // 沒有的話產出 app-release-unsigned.apk，再由 workflow 用 apksigner 補簽。
    signingConfigs {
        create("ci") {
            val ks = System.getenv("KS_FILE")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("KS_STORE_PASS")
                keyAlias = System.getenv("KS_ALIAS")
                keyPassword = System.getenv("KS_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("KS_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Google 帳號登入（Credential Manager，取代已廢棄的 GoogleSignIn）
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // 網頁 OAuth 降級通道：用 Chrome 自訂分頁開 Google 授權頁
    implementation("androidx.browser:browser:1.8.0")
    // GitHub token 的加密儲存（AES-256-GCM + Keystore）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
