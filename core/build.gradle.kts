plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Núcleo compartido en Kotlin Multiplatform: entidades, JSON, contrato de sincronización,
// fórmulas OBD, plan de mantenimiento y simulador.
// - jvm: lo usan el servidor y las apps Android.
// - ios: lo usa la app del iPhone. Los targets de iOS solo compilan en macOS; en Linux se saltan.
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.datetime)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.coroutines.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// Bytecode JVM 17 para Android y la imagen Docker, compilando con cualquier JDK 17+.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
