// Los plugins se cargan una sola vez aquí para que todos los módulos compartan el mismo classloader:
// Kotlin necesita ver las clases de Android (AGP), y Kotlin Multiplatform no admite cargarse dos veces.
// Sin Android SDK (-Pcarobd.android=false) se usa build.sin-android.gradle.kts, que no carga AGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
