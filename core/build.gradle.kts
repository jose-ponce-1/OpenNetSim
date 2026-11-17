// Módulo Kotlin/JVM puro: motor de degradación, parsers de paquetes, TCP en espacio de usuario y
// modelo. No puede depender de Android: un `import android.*` aquí no compila.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
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

// Cobertura con Kover: `:core:koverVerifyCoverage` exige un mínimo de líneas cubiertas por los tests de
// este módulo; el informe conjunto con :app está en `:app:koverHtmlReportCoverage`.
kover {
    currentProject {
        createVariant("coverage") { add("jvm") }
    }
    reports {
        verify {
            rule("Cobertura mínima de líneas de :core") { minBound(85) }
        }
    }
}
