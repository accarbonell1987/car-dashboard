// Igual que build.gradle.kts pero sin el plugin de Android. Se usa con -Pcarobd.android=false,
// que compila solo core y server (sin Android SDK ni el repositorio de Google).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
