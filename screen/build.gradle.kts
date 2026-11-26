plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.carobd.screen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carobd.screen"
        // Android 7.0+: muchas pantallas de auto declaran una versión y corren otra más vieja.
        minSdk = 24
        targetSdk = 35
        // Commit count: grows with every release, so the in-app updater can tell which APK is newer.
        val commits = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
        versionCode = commits
        versionName = "0.1.$commits"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (y kotlinx-datetime, que lo usa en JVM) exige API 26; el desugaring lo trae a 24-25.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // The 3D model is read by the WebView in one go; stored uncompressed it loads much faster on a slow head unit.
    androidResources {
        noCompress += "glb"
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lottie.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
