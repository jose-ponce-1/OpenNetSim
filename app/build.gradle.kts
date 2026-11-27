import com.opennetsim.gradle.GenerateOpenSourceLicensesTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// Firma de release: se lee de propiedades de Gradle fuera del repositorio (~/.gradle/gradle.properties,
// o variables ORG_GRADLE_PROJECT_<nombre> en CI). Sin ellas, assembleRelease genera un APK sin firmar.
val releaseStoreFile = providers.gradleProperty("openNetSimReleaseStoreFile").orNull

android {
    namespace = "com.opennetsim"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.opennetsim"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("openNetSimReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("openNetSimReleaseKeyAlias").get()
                keyPassword = providers.gradleProperty("openNetSimReleaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Idiomas de la app: inglés (por defecto, values/) y español (values-es/). Las traducciones de las
    // librerías a otros idiomas no se empaquetan; deben coincidir con res/xml/locales_config.xml.
    androidResources {
        localeFilters += listOf("en", "es")
    }

    // Desactiva el bloque binario de metadatos de dependencias de AGP para generar APKs reproducibles y aptos para F-Droid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))
    kover(project(":core"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Cobertura con Kover del código de :app y :core juntos (los tests de :app también ejercitan :core):
// informe en `:app:koverHtmlReportCoverage` y mínimo en `:app:koverVerifyCoverage`.
kover {
    currentProject {
        createVariant("coverage") { add("debug") }
    }
    reports {
        filters {
            excludes {
                // Código que solo se ejecuta con el framework de Android o la interfaz de Compose: los tests
                // JVM no lo alcanzan y se verifica en dispositivo (openspec/config.yaml).
                classes(
                    "com.opennetsim.ui.MainActivity*",
                    "com.opennetsim.ui.ComposableSingletons*",
                    "com.opennetsim.ui.screens.*",
                    "com.opennetsim.ui.components.*",
                    "com.opennetsim.ui.theme.*",
                    "com.opennetsim.ui.preferences.SharedPreferencesUiPreferences*",
                    "com.opennetsim.profiles.SharedPreferencesProfileStore*",
                    "com.opennetsim.service.ThrottleVpnService*",
                    "com.opennetsim.service.ServiceTunnelController*",
                    "com.opennetsim.service.SharedPreferencesConfigStore*",
                    "com.opennetsim.service.TunnelIntents*",
                    "com.opennetsim.service.TunnelNotifications*",
                    "com.opennetsim.service.diag.AndroidLogSink*",
                    "com.opennetsim.service.forwarder.Android*",
                    "com.opennetsim.service.network.UnderlyingNetworkMonitor*",
                    "com.opennetsim.service.pipeline.ParcelFileDescriptorTunDevice*"
                )
            }
        }
        verify {
            rule("Cobertura mínima de líneas de :app y :core") { minBound(85) }
        }
    }
}

private fun Project.resolveRuntimePomInputs(): Pair<List<String>, Set<File>> {
    val runtimeConfig = configurations.getByName("releaseRuntimeClasspath")
    val coords = runtimeConfig.incoming.artifacts.artifacts
        .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}:${it.version}" }
        .distinct()
        .sorted()
    val allPoms = linkedSetOf<File>()
    val parsedCoords = hashSetOf<String>()
    var pending = coords
    repeat(4) {
        if (pending.isEmpty()) return@repeat
        val pomConfig = configurations.detachedConfiguration(
            *pending.map { dependencies.create("$it@pom") }.toTypedArray()
        ).apply { isTransitive = false }
        val resolved = pomConfig.incoming.artifactView { lenient(true) }.artifacts.artifacts.map { it.file }
        allPoms += resolved
        val nextParents = mutableListOf<String>()
        for (file in resolved) {
            val meta = GenerateOpenSourceLicensesTask.parsePom(file) ?: continue
            parsedCoords += meta.coordinate
            if (meta.licenses.isEmpty() && meta.parentCoordinate != null) {
                nextParents += meta.parentCoordinate!!
            }
        }
        pending = nextParents.distinct().filter { it !in parsedCoords }
    }
    return coords to allPoms
}

androidComponents {
    onVariants { variant ->
        val taskName = "generate${variant.name.replaceFirstChar(Char::uppercase)}OpenSourceLicenses"
        val licenseTask = tasks.register<GenerateOpenSourceLicensesTask>(taskName) {
            val (coords, poms) = project.resolveRuntimePomInputs()
            resolvedCoordinates.set(coords)
            pomFiles.from(poms)
            apacheLicenseFile.set(rootProject.layout.projectDirectory.file("LICENSE.txt"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            licenseTask,
            GenerateOpenSourceLicensesTask::getOutputAssetsDir
        )
    }
}
