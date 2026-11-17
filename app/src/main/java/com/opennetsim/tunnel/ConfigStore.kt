package com.opennetsim.tunnel

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset

/**
 * Persistencia de la última configuración aplicada al túnel, para que un reinicio del servicio
 * (muerte del proceso o VPN siempre activa) restablezca las mismas condiciones de red.
 */
interface ConfigStore {
    fun load(): ThrottleConfig?

    fun save(config: ThrottleConfig)
}

/** Implementación en memoria (tests y valor por defecto sin almacenamiento). */
class InMemoryConfigStore(private var stored: ThrottleConfig? = null) : ConfigStore {
    override fun load(): ThrottleConfig? = stored

    override fun save(config: ThrottleConfig) {
        stored = config
    }
}

/**
 * Codificación de [ThrottleConfig] como pares clave/valor de texto, independiente de Android.
 * Una entrada incompleta o corrupta se descarta entera; un perfil desconocido pasa a CUSTOM.
 */
object ThrottleConfigCodec {
    const val KEY_DOWN_KBPS = "down_kbps"
    const val KEY_UP_KBPS = "up_kbps"
    const val KEY_LATENCY_MS = "latency_ms"
    const val KEY_JITTER_MS = "jitter_ms"
    const val KEY_LOSS_PERCENT = "loss_percent"
    const val KEY_PRESET = "preset"

    fun encode(config: ThrottleConfig): Map<String, String> = mapOf(
        KEY_DOWN_KBPS to config.downKbps.toString(),
        KEY_UP_KBPS to config.upKbps.toString(),
        KEY_LATENCY_MS to config.latencyMs.toString(),
        KEY_JITTER_MS to config.jitterMs.toString(),
        KEY_LOSS_PERCENT to config.packetLossPercent.toString(),
        KEY_PRESET to config.preset.name
    )

    @Suppress("ReturnCount") // Un retorno temprano por campo obligatorio ausente o corrupto.
    fun decode(values: Map<String, String?>): ThrottleConfig? {
        val downKbps = values[KEY_DOWN_KBPS]?.toIntOrNull() ?: return null
        val upKbps = values[KEY_UP_KBPS]?.toIntOrNull() ?: return null
        val latencyMs = values[KEY_LATENCY_MS]?.toIntOrNull() ?: return null
        val jitterMs = values[KEY_JITTER_MS]?.toIntOrNull() ?: return null
        val lossPercent = values[KEY_LOSS_PERCENT]?.toFloatOrNull() ?: return null
        val preset = values[KEY_PRESET]
            ?.let { name -> ThrottlePreset.entries.firstOrNull { it.name == name } }
            ?: ThrottlePreset.CUSTOM
        return ThrottleConfig(
            downKbps = downKbps,
            upKbps = upKbps,
            latencyMs = latencyMs,
            jitterMs = jitterMs,
            packetLossPercent = lossPercent,
            preset = preset
        )
    }
}
