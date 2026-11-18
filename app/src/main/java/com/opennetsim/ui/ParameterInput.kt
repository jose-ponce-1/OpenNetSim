package com.opennetsim.ui

import com.opennetsim.model.ThrottleConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Unidad en la que el usuario escribe el ancho de banda. */
enum class BandwidthUnit(val label: String) {
    KBPS("kbps"),
    MBPS("Mbps")
}

/**
 * Parámetros editables de [ThrottleConfig]. [range] está en la unidad interna: kbps para el ancho de
 * banda; ms, % o paquetes para el resto. [decimals] son los decimales que se conservan.
 */
enum class ParameterField(
    val label: String,
    val range: ClosedFloatingPointRange<Double>,
    val decimals: Int,
    val suffix: String,
    val prefix: String = ""
) {
    DOWN_KBPS("Bajada", 50.0..50_000.0, decimals = 0, suffix = "kbps"),
    UP_KBPS("Subida", 20.0..20_000.0, decimals = 0, suffix = "kbps"),
    LATENCY_MS("Latencia", 0.0..1000.0, decimals = 0, suffix = "ms", prefix = "+"),
    JITTER_MS("Jitter", 0.0..200.0, decimals = 0, suffix = "ms", prefix = "±"),
    REORDER_PERCENT("Reordenación", 0.0..ThrottleConfig.MAX_REORDER_PERCENT.toDouble(), decimals = 1, suffix = "%"),
    UP_LOSS_PERCENT("Pérdida subida", 0.0..50.0, decimals = 1, suffix = "%"),
    DOWN_LOSS_PERCENT("Pérdida bajada", 0.0..50.0, decimals = 1, suffix = "%"),
    LOSS_BURST("Ráfaga media", 1.0..ThrottleConfig.MAX_LOSS_BURST_LENGTH.toDouble(), decimals = 0, suffix = "paquetes");

    val isBandwidth: Boolean get() = this == DOWN_KBPS || this == UP_KBPS

    fun read(config: ThrottleConfig): Double = when (this) {
        DOWN_KBPS -> config.downKbps.toDouble()
        UP_KBPS -> config.upKbps.toDouble()
        LATENCY_MS -> config.latencyMs.toDouble()
        JITTER_MS -> config.jitterMs.toDouble()
        REORDER_PERCENT -> config.reorderPercent.toDouble()
        UP_LOSS_PERCENT -> config.upLossPercent.toDouble()
        DOWN_LOSS_PERCENT -> config.downLossPercent.toDouble()
        LOSS_BURST -> config.lossBurstLength.toDouble()
    }

    fun write(config: ThrottleConfig, value: Double): ThrottleConfig = when (this) {
        DOWN_KBPS -> config.copy(downKbps = value.roundToInt())
        UP_KBPS -> config.copy(upKbps = value.roundToInt())
        LATENCY_MS -> config.copy(latencyMs = value.roundToInt())
        JITTER_MS -> config.copy(jitterMs = value.roundToInt())
        REORDER_PERCENT -> config.copy(reorderPercent = value.toFloat())
        UP_LOSS_PERCENT -> config.copy(upLossPercent = value.toFloat())
        DOWN_LOSS_PERCENT -> config.copy(downLossPercent = value.toFloat())
        LOSS_BURST -> config.copy(lossBurstLength = value.roundToInt())
    }

    /** La reordenación solo tiene efecto con latencia: sin ella el campo se desactiva. */
    fun isEnabled(config: ThrottleConfig): Boolean = this != REORDER_PERCENT || config.latencyMs > 0

    fun differs(config: ThrottleConfig, other: ThrottleConfig): Boolean = abs(read(config) - read(other)) > EPSILON

    private companion object {
        const val EPSILON = 0.0001
    }
}

/** Resultado de interpretar el texto de un campo. */
sealed interface ParameterParse {
    data class Valid(val value: Double) : ParameterParse

    data class Invalid(val message: String) : ParameterParse
}

/** Estado de un campo tal y como lo pinta la sección Parámetros. */
data class ParameterFieldState(
    val field: ParameterField,
    val text: String,
    val error: String?,
    val supportingText: String,
    val enabled: Boolean,
    val changed: Boolean
)

/** Interpretación, formato y estado de los campos de valor exacto. */
object ParameterInput {
    private val NUMBER = Regex("""\d*\.?\d*""")
    private const val KBPS_PER_MBPS = 1000.0
    private const val MBPS_DECIMALS = 3
    private const val KBYTES_DECIMALS = 1
    private const val BITS_PER_BYTE = 8.0
    private const val TEN = 10.0

    /**
     * Un campo vacío equivale a 0. Donde 0 queda fuera del rango (ancho de banda, ráfaga media) se trata
     * como cualquier otro valor fuera de rango: error y se conserva el último valor válido.
     */
    fun parse(field: ParameterField, text: String, unit: BandwidthUnit): ParameterParse {
        val normalized = text.trim().replace(',', '.').ifEmpty { "0" }
        return when {
            normalized == "." || !NUMBER.matches(normalized) -> ParameterParse.Invalid("Solo números")
            else -> {
                val typed = normalized.toDouble()
                val internal = if (field.isBandwidth && unit == BandwidthUnit.MBPS) typed * KBPS_PER_MBPS else typed
                val value = round(internal, field.decimals)
                if (value in field.range) {
                    ParameterParse.Valid(value)
                } else {
                    ParameterParse.Invalid("Rango: ${rangeText(field)}")
                }
            }
        }
    }

    fun format(field: ParameterField, value: Double, unit: BandwidthUnit): String = when {
        field.isBandwidth && unit == BandwidthUnit.MBPS -> trimmed(value / KBPS_PER_MBPS, MBPS_DECIMALS)
        else -> trimmed(value, field.decimals)
    }

    /** Textos de todos los campos a partir de la configuración. */
    fun drafts(config: ThrottleConfig, unit: BandwidthUnit): Map<ParameterField, String> =
        ParameterField.entries.associateWith { format(it, it.read(config), unit) }

    fun suffix(field: ParameterField, unit: BandwidthUnit): String = if (field.isBandwidth) unit.label else field.suffix

    fun rangeText(field: ParameterField): String {
        if (field.isBandwidth) {
            return "${UiFormatters.bandwidth(field.range.start)} – ${UiFormatters.bandwidth(field.range.endInclusive)}"
        }
        val unit = if (field.suffix == "%") " %" else " ${field.suffix}"
        val start = trimmed(field.range.start, field.decimals)
        return "$start – ${trimmed(field.range.endInclusive, field.decimals)}$unit"
    }

    /** Estado de cada campo: texto escrito, error, ayuda, si está activo y si difiere de [base]. */
    fun fieldStates(
        drafts: Map<ParameterField, String>,
        config: ThrottleConfig,
        base: ThrottleConfig,
        unit: BandwidthUnit
    ): List<ParameterFieldState> = ParameterField.entries.map { field ->
        val text = drafts[field] ?: format(field, field.read(config), unit)
        val enabled = field.isEnabled(config)
        val error = (parse(field, text, unit) as? ParameterParse.Invalid)?.message?.takeIf { enabled }
        ParameterFieldState(
            field = field,
            text = text,
            error = error,
            supportingText = error ?: hint(field, config, unit, enabled),
            enabled = enabled,
            changed = field.differs(config, base)
        )
    }

    /** Aviso bajo la cabecera de Parámetros según cuántos campos no son válidos. */
    fun applyNote(invalidCount: Int): String = when (invalidCount) {
        0 -> "Los valores válidos se aplican al instante, también con la simulación en curso."
        1 -> "Hay 1 valor no válido: se mantiene el último correcto."
        else -> "Hay $invalidCount valores no válidos: se mantienen los últimos correctos."
    }

    private fun hint(field: ParameterField, config: ThrottleConfig, unit: BandwidthUnit, enabled: Boolean): String {
        val value = field.read(config)
        return when {
            !enabled -> "Requiere latencia mayor que 0"
            field.isBandwidth -> {
                val other = if (unit == BandwidthUnit.KBPS) {
                    "${trimmed(value / KBPS_PER_MBPS, MBPS_DECIMALS)} Mbps"
                } else {
                    "${value.roundToLong()} kbps"
                }
                "= $other · ${trimmed(value / BITS_PER_BYTE, KBYTES_DECIMALS)} KB/s"
            }
            field == ParameterField.LOSS_BURST ->
                if (value <= 1.0) "1 = pérdidas independientes" else "Pérdidas en ráfagas de ${value.roundToInt()}"
            else -> rangeText(field)
        }
    }

    private fun round(value: Double, decimals: Int): Double {
        val factor = TEN.pow(decimals)
        return (value * factor).roundToLong() / factor
    }

    /** Número con como mucho [decimals] decimales y sin ceros finales: 1.500 → "1.5", 2.0 → "2". */
    fun trimmed(value: Double, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        return if (decimals == 0) text else text.trimEnd('0').trimEnd('.')
    }
}
