import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
    alias(libs.plugins.detekt) apply false
}

// detekt con la misma configuración en todos los módulos y una baseline por módulo con la deuda previa
// conocida: toda infracción nueva rompe el build. Regenerar con ./gradlew :<módulo>:detektBaseline.
subprojects {
    pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
            parallel = true
        }
        dependencies {
            "detektPlugins"(rootProject.libs.detekt.formatting)
            // detekt.yml configura también las reglas de Compose; sin el plugin, la configuración no valida.
            "detektPlugins"(rootProject.libs.compose.rules.detekt)
        }
    }
}
