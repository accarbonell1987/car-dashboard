// El plugin de Android (AGP) solo se resuelve cuando se incluyen las apps,
// así core y server compilan aunque no haya SDK de Android ni acceso al repo de Google.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
