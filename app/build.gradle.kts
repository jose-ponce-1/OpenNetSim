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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opennetsim"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

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
}

dependencies {
    implementation(project(":core"))
    kover(project(":core"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
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
                    "com.opennetsim.ui.ThrottleDashboardScreenKt*",
                    "com.opennetsim.ui.ComposableSingletons*",
                    "com.opennetsim.ui.theme.*",
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
