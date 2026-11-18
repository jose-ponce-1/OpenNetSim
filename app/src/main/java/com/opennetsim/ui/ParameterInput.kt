package com.opennetsim.ui

import androidx.annotation.StringRes
import com.opennetsim.R
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
 * banda; ms, % o paquetes para el resto. [decimals] son los decimales que se conservan. [suffix] es la
 * unidad: un texto igual en todos los idiomas ("ms", "%") o un recurso traducido.
 */
enum class ParameterField(
    @param:StringRes val labelRes: Int,
    val range: ClosedFloatingPointRange<Double>,
    val decimals: Int,
    val suffix: UiText,
    val prefix: String = ""
) {
    DOWN_KBPS(R.string.field_download, 50.0..50_000.0, decimals = 0, suffix = UiText.Raw("kbps")),
    UP_KBPS(R.string.field_upload, 20.0..20_000.0, decimals = 0, suffix = UiText.Raw("kbps")),
    LATENCY_MS(R.string.field_latency, 0.0..1000.0, decimals = 0, suffix = UiText.Raw("ms"), prefix = "+"),
    JITTER_MS(R.string.field_jitter, 0.0..200.0, decimals = 0, suffix = UiText.Raw("ms"), prefix = "±"),
    REORDER_PERCENT(
        R.string.field_reorder,
        0.0..ThrottleConfig.MAX_REORDER_PERCENT.toDouble(),
        decimals = 1,
        suffix = UiText.Raw("%")
    ),
    UP_LOSS_PERCENT(R.string.field_upload_loss, 0.0..50.0, decimals = 1, suffix = UiText.Raw("%")),
    DOWN_LOSS_PERCENT(R.string.field_download_loss, 0.0..50.0, decimals = 1, suffix = UiText.Raw("%")),
    LOSS_BURST(
        R.string.field_loss_burst,
        1.0..ThrottleConfig.MAX_LOSS_BURST_LENGTH.toDouble(),
        decimals = 0,
        suffix = UiText.res(R.string.unit_packets)
    );

    val label: UiText get() = UiText.res(labelRes)

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

    data class Invalid(val message: UiText) : ParameterParse
}

/** Estado de un campo tal y como lo pinta la sección Parámetros. */
data class ParameterFieldState(
    val field: ParameterField,
    val text: String,
    val error: UiText?,
    val supportingText: UiText,
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
            normalized == "." || !NUMBER.matches(normalized) ->
                ParameterParse.Invalid(UiText.res(R.string.field_error_numbers_only))
            else -> {
                val typed = normalized.toDouble()
                val internal = if (field.isBandwidth && unit == BandwidthUnit.MBPS) typed * KBPS_PER_MBPS else typed
                val value = round(internal, field.decimals)
                if (value in field.range) {
                    ParameterParse.Valid(value)
                } else {
                    ParameterParse.Invalid(UiText.res(R.string.field_error_range, rangeText(field)))
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

    fun suffix(field: ParameterField, unit: BandwidthUnit): UiText =
        if (field.isBandwidth) UiText.Raw(unit.label) else field.suffix

    /** "0 – 1000 ms", "1 – 10 paquetes", "50 kbps – 50 Mbps". */
    fun rangeText(field: ParameterField): UiText {
        if (field.isBandwidth) {
            return UiText.res(
                R.string.value_range,
                UiFormatters.bandwidth(field.range.start),
                UiFormatters.bandwidth(field.range.endInclusive)
            )
        }
        return UiText.res(
            R.string.value_range_with_unit,
            trimmed(field.range.start, field.decimals),
            trimmed(field.range.endInclusive, field.decimals),
            field.suffix
        )
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
    fun applyNote(invalidCount: Int): UiText = if (invalidCount == 0) {
        UiText.res(R.string.apply_note_ok)
    } else {
        UiText.plural(R.plurals.apply_note_invalid, invalidCount)
    }

    private fun hint(field: ParameterField, config: ThrottleConfig, unit: BandwidthUnit, enabled: Boolean): UiText {
        val value = field.read(config)
        return when {
            !enabled -> UiText.res(R.string.field_hint_requires_latency)
            field.isBandwidth -> {
                val other = if (unit == BandwidthUnit.KBPS) {
                    "${trimmed(value / KBPS_PER_MBPS, MBPS_DECIMALS)} Mbps"
                } else {
                    "${value.roundToLong()} kbps"
                }
                UiText.res(R.string.value_bandwidth_hint, other, trimmed(value / BITS_PER_BYTE, KBYTES_DECIMALS))
            }
            field == ParameterField.LOSS_BURST -> if (value <= 1.0) {
                UiText.res(R.string.field_hint_burst_independent)
            } else {
                UiText.res(R.string.field_hint_burst, value.roundToInt())
            }
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
