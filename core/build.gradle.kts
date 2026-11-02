plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Módulo común: entidades, JSON, contrato de sincronización, fórmulas OBD y simulador.
// Es JVM puro (sin APIs de Android ni de servidor) para que lo importen las dos apps y el servidor.
// Cuando entre iOS se convierte a Kotlin Multiplatform sin cambiar el código.
// Bytecode JVM 17 (lo que corre en Android y en la imagen Docker), compilando con cualquier JDK 17+.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.datetime)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
