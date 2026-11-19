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
        versionCode = 2
        versionName = "1.0.1"

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

    // Google Play reparte los AAB por idioma: sin esto, un móvil en inglés no recibiría el español y no
    // podría elegirlo como idioma de la app (Android 13+). Con dos idiomas el tamaño apenas cambia.
    bundle {
        language {
            enableSplit = false
        }
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
