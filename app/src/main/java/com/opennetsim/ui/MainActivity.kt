package com.opennetsim.ui

import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.profiles.SharedPreferencesProfileStore
import com.opennetsim.service.ServiceTunnelController
import com.opennetsim.ui.preferences.SharedPreferencesUiPreferences
import com.opennetsim.ui.screens.OpenNetSimApp
import com.opennetsim.ui.theme.OpenNetSimTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Raíz de composición: el único punto de la UI que conoce las implementaciones Android.
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    tunnel = ServiceTunnelController(applicationContext),
                    profileStore = SharedPreferencesProfileStore(applicationContext),
                    preferences = SharedPreferencesUiPreferences(applicationContext),
                    naming = ProfileNaming(reservedNames = reservedProfileNames())
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setOnExitAnimationListener(::animateSplashExit)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenNetSimTheme {
                OpenNetSimApp(viewModel = viewModel)
            }
        }
    }

    /** Nombres de los perfiles predefinidos en todos los idiomas de la app (ver `ProfileCatalog.checkName`). */
    private fun reservedProfileNames(): Set<String> = APP_LANGUAGES.flatMap { language ->
        val configuration = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = createConfigurationContext(configuration).resources
        ThrottlePreset.entries.map { localized.getString(PresetTexts.nameRes(it)) }
    }.toSet()

    /**
     * Salida de la pantalla de carga: espera a que termine la animación del icono (Android 12+, como
     * mucho su duración) y lo agranda y desvanece. Con las animaciones del sistema desactivadas, la
     * duración escalada es 0 y la salida es inmediata.
     *
     * En Android 12+, `core-splashscreen` aplica las barras del sistema del tema justo antes de esta
     * llamada y otra vez en `remove()` (iconos claros de `Theme.Material.Light`, colores de barra), lo
     * que deshace `enableEdgeToEdge()`: se vuelve a aplicar tras cada uno de esos dos puntos.
     */
    private fun animateSplashExit(provider: SplashScreenViewProvider) {
        enableEdgeToEdge()
        val iconEnd = provider.iconAnimationStartMillis + provider.iconAnimationDurationMillis
        val wait = (iconEnd - SystemClock.uptimeMillis()).coerceIn(0L, MAX_ICON_WAIT_MS)
        provider.iconView.animate()
            .setStartDelay(wait)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(EXIT_EASING)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .alpha(0f)
            .withEndAction {
                provider.remove()
                enableEdgeToEdge()
            }
            .start()
    }

    private companion object {
        /** Idiomas de la app: los de `res/xml/locales_config.xml` y `localeFilters` en `build.gradle.kts`. */
        val APP_LANGUAGES = listOf("en", "es")

        const val MAX_ICON_WAIT_MS = 1_000L
        const val EXIT_DURATION_MS = 320L
        const val EXIT_SCALE = 1.12f

        /** Aceleración enfatizada de Material 3 para elementos que salen. */
        val EXIT_EASING = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
