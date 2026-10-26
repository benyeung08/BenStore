plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    debugImplementation("androidx.compose.ui:ui-tooling")
}
