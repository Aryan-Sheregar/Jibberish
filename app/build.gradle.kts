import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.3.5"
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.jibberish"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.jibberish"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // STT credentials — set in local.properties or environment variables.
        // SARVAM_API_KEY  : used for direct API calls during development.
        // STT_PROXY_URL   : when set, app routes STT through your Ktor proxy instead.
        val sarvamApiKey = localProps.getProperty("SARVAM_API_KEY")
            ?: System.getenv("SARVAM_API_KEY") ?: ""
        val sttProxyUrl = localProps.getProperty("STT_PROXY_URL")
            ?: System.getenv("STT_PROXY_URL") ?: ""
        buildConfigField("String", "SARVAM_API_KEY", "\"$sarvamApiKey\"")
        buildConfigField("String", "STT_PROXY_URL", "\"$sttProxyUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Encrypted storage for sensitive credentials
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // OkHttp for network requests (model download)
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    // MediaPipe GenAI for on-device LLM inference
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}