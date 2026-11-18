package com.opennetsim.ui.preferences

import com.opennetsim.ui.BandwidthUnit

/** Preferencias propias de la interfaz que se conservan entre ejecuciones. */
interface UiPreferences {
    /** True cuando el usuario completó la bienvenida («Empezar» o «Ahora no»). */
    var onboardingCompleted: Boolean

    /** Unidad en la que se escribe el ancho de banda. */
    var bandwidthUnit: BandwidthUnit
}

/** Implementación en memoria (tests y valor por defecto sin almacenamiento). */
class InMemoryUiPreferences(
    override var onboardingCompleted: Boolean = false,
    override var bandwidthUnit: BandwidthUnit = BandwidthUnit.KBPS
) : UiPreferences
