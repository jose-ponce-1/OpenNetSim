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
 *
 * Compatibilidad: una entrada guardada antes de separar la pérdida por dirección (solo
 * [KEY_LEGACY_LOSS_PERCENT]) aplica esa pérdida a ambas direcciones, sin ráfagas ni reordenación.
 */
object ThrottleConfigCodec {
    const val KEY_DOWN_KBPS = "down_kbps"
    const val KEY_UP_KBPS = "up_kbps"
    const val KEY_LATENCY_MS = "latency_ms"
    const val KEY_JITTER_MS = "jitter_ms"
    const val KEY_UP_LOSS_PERCENT = "up_loss_percent"
    const val KEY_DOWN_LOSS_PERCENT = "down_loss_percent"
    const val KEY_LOSS_BURST_LENGTH = "loss_burst_length"
    const val KEY_REORDER_PERCENT = "reorder_percent"
    const val KEY_LEGACY_LOSS_PERCENT = "loss_percent"
    const val KEY_PRESET = "preset"

    fun encode(config: ThrottleConfig): Map<String, String> = mapOf(
        KEY_DOWN_KBPS to config.downKbps.toString(),
        KEY_UP_KBPS to config.upKbps.toString(),
        KEY_LATENCY_MS to config.latencyMs.toString(),
        KEY_JITTER_MS to config.jitterMs.toString(),
        KEY_UP_LOSS_PERCENT to config.upLossPercent.toString(),
        KEY_DOWN_LOSS_PERCENT to config.downLossPercent.toString(),
        KEY_LOSS_BURST_LENGTH to config.lossBurstLength.toString(),
        KEY_REORDER_PERCENT to config.reorderPercent.toString(),
        KEY_PRESET to config.preset.name
    )

    @Suppress("ReturnCount") // Un retorno temprano por campo obligatorio ausente o corrupto.
    fun decode(values: Map<String, String?>): ThrottleConfig? {
        val downKbps = values[KEY_DOWN_KBPS]?.toIntOrNull() ?: return null
        val upKbps = values[KEY_UP_KBPS]?.toIntOrNull() ?: return null
        val latencyMs = values[KEY_LATENCY_MS]?.toIntOrNull() ?: return null
        val jitterMs = values[KEY_JITTER_MS]?.toIntOrNull() ?: return null
        val legacyLoss = values[KEY_LEGACY_LOSS_PERCENT]?.toFloatOrNull()
        val upLoss = values[KEY_UP_LOSS_PERCENT]?.toFloatOrNull() ?: legacyLoss ?: return null
        val downLoss = values[KEY_DOWN_LOSS_PERCENT]?.toFloatOrNull() ?: legacyLoss ?: return null
        val preset = values[KEY_PRESET]
            ?.let { name -> ThrottlePreset.entries.firstOrNull { it.name == name } }
            ?: ThrottlePreset.CUSTOM
        return ThrottleConfig(
            downKbps = downKbps,
            upKbps = upKbps,
            latencyMs = latencyMs,
            jitterMs = jitterMs,
            upLossPercent = upLoss,
            downLossPercent = downLoss,
            lossBurstLength = values[KEY_LOSS_BURST_LENGTH]?.toIntOrNull() ?: 1,
            reorderPercent = values[KEY_REORDER_PERCENT]?.toFloatOrNull() ?: 0f,
            preset = preset
        )
    }
}
