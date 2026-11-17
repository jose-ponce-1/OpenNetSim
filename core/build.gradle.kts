// Módulo Kotlin/JVM puro: motor de degradación, parsers de paquetes, TCP en espacio de usuario y
// modelo. No puede depender de Android: un `import android.*` aquí no compila.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-test-fixtures`
}

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
    testImplementation(libs.junit)
}
