plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingKeyStore = System.getenv("SENTENCELAB_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val signingPassword = System.getenv("SENTENCELAB_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }

android {
    signingConfigs {
        if (signingKeyStore != null && signingPassword != null) {
            create("stable") {
                storeFile = file(signingKeyStore)
                storeType = "pkcs12"
                storePassword = signingPassword
                keyAlias = "sentencelab"
                keyPassword = signingPassword
            }
        }
    }
    namespace = "com.sentencelab.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sentencelab.app"
        minSdk = 26
        targetSdk = 35
        versionName = "0.10.1"
        versionCode = 11
        val webClientId = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID").orElse("").get()
        val apiBaseUrl = providers.gradleProperty("API_BASE_URL").orElse("").get()
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        getByName("debug") {
            if (signingConfigs.findByName("stable") != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
