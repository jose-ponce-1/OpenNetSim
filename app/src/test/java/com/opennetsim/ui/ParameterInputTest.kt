package com.opennetsim.ui

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterInputTest {

    private val slow3g = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G)

    private fun valid(field: ParameterField, text: String, unit: BandwidthUnit = BandwidthUnit.KBPS): Double {
        val result = ParameterInput.parse(field, text, unit)
        assertTrue("«$text» debería ser válido: $result", result is ParameterParse.Valid)
        return (result as ParameterParse.Valid).value
    }

    private fun errorText(field: ParameterField, text: String, unit: BandwidthUnit): UiText {
        val result = ParameterInput.parse(field, text, unit)
        assertTrue("«$text» no debería ser válido: $result", result is ParameterParse.Invalid)
        return (result as ParameterParse.Invalid).message
    }

    /** Error en español, el idioma de las specs. */
    private fun error(field: ParameterField, text: String, unit: BandwidthUnit = BandwidthUnit.KBPS): String? =
        errorText(field, text, unit).es()

    @Test
    fun acceptsCommaOrDotAndTrimsSpaces() {
        assertEquals(2.5, valid(ParameterField.DOWN_LOSS_PERCENT, "2,5"), 0.0)
        assertEquals(2.5, valid(ParameterField.DOWN_LOSS_PERCENT, " 2.5 "), 0.0)
        assertEquals(200.0, valid(ParameterField.LATENCY_MS, "0200"), 0.0)
        assertEquals(0.5, valid(ParameterField.UP_LOSS_PERCENT, ".5"), 0.0)
    }

    @Test
    fun emptyTextMeansZero() {
        assertEquals(0.0, valid(ParameterField.LATENCY_MS, ""), 0.0)
        assertEquals(0.0, valid(ParameterField.JITTER_MS, "   "), 0.0)
        assertEquals(0.0, valid(ParameterField.DOWN_LOSS_PERCENT, ""), 0.0)
        // Donde 0 no está en el rango, el vacío da el mismo error que escribir 0.
        assertEquals("Rango: 20 kbps – 20 Mbps", error(ParameterField.UP_KBPS, ""))
        assertEquals(error(ParameterField.UP_KBPS, "0"), error(ParameterField.UP_KBPS, "  "))
        assertEquals("Rango: 1 – 10 paquetes", error(ParameterField.LOSS_BURST, ""))
    }

    @Test
    fun rejectsNonNumericText() {
        assertEquals("Solo números", error(ParameterField.UP_KBPS, "12a"))
        assertEquals("Solo números", error(ParameterField.UP_KBPS, "-5"))
        assertEquals("Solo números", error(ParameterField.UP_KBPS, "."))
        assertEquals("Solo números", error(ParameterField.UP_KBPS, "1.2.3"))
    }

    @Test
    fun outOfRangeShowsAdmittedRange() {
        assertEquals("Rango: 0 – 1000 ms", error(ParameterField.LATENCY_MS, "5000"))
        assertEquals("Rango: 50 kbps – 50 Mbps", error(ParameterField.DOWN_KBPS, "10"))
        assertEquals("Rango: 0 – 25 %", error(ParameterField.REORDER_PERCENT, "30"))
        assertEquals("Rango: 1 – 10 paquetes", error(ParameterField.LOSS_BURST, "0"))
    }

    @Test
    fun errorsInEnglish() {
        assertEquals("Range: 0 – 1000 ms", errorText(ParameterField.LATENCY_MS, "5000", BandwidthUnit.KBPS).en())
        assertEquals("Range: 1 – 10 packets", errorText(ParameterField.LOSS_BURST, "0", BandwidthUnit.KBPS).en())
        assertEquals("Numbers only", errorText(ParameterField.UP_KBPS, "12a", BandwidthUnit.KBPS).en())
        assertEquals("Average burst", ParameterField.LOSS_BURST.label.en())
        assertEquals("Reordenación", ParameterField.REORDER_PERCENT.label.es())
    }

    @Test
    fun bandwidthInMbpsIsStoredInKbps() {
        assertEquals(12_000.0, valid(ParameterField.DOWN_KBPS, "12", BandwidthUnit.MBPS), 0.0)
        assertEquals(1_500.0, valid(ParameterField.DOWN_KBPS, "1,5", BandwidthUnit.MBPS), 0.0)
        assertEquals("Rango: 20 kbps – 20 Mbps", error(ParameterField.UP_KBPS, "25", BandwidthUnit.MBPS))
        // La unidad solo afecta al ancho de banda.
        assertEquals(12.0, valid(ParameterField.LATENCY_MS, "12", BandwidthUnit.MBPS), 0.0)
    }

    @Test
    fun valuesAreRoundedToTheFieldPrecision() {
        assertEquals(2.6, valid(ParameterField.UP_LOSS_PERCENT, "2.56"), 1e-9)
        assertEquals(1235.0, valid(ParameterField.DOWN_KBPS, "1.2346", BandwidthUnit.MBPS), 0.0)
        assertEquals(100.0, valid(ParameterField.LATENCY_MS, "99.6"), 0.0)
    }

    @Test
    fun formatTrimsTrailingZerosAndConvertsUnits() {
        assertEquals("1.5", ParameterInput.format(ParameterField.DOWN_KBPS, 1500.0, BandwidthUnit.MBPS))
        assertEquals("1500", ParameterInput.format(ParameterField.DOWN_KBPS, 1500.0, BandwidthUnit.KBPS))
        assertEquals("0.384", ParameterInput.format(ParameterField.DOWN_KBPS, 384.0, BandwidthUnit.MBPS))
        assertEquals("2", ParameterInput.format(ParameterField.UP_LOSS_PERCENT, 2.0, BandwidthUnit.KBPS))
        assertEquals("2.5", ParameterInput.format(ParameterField.UP_LOSS_PERCENT, 2.5, BandwidthUnit.KBPS))
        assertEquals("200", ParameterInput.format(ParameterField.LATENCY_MS, 200.0, BandwidthUnit.KBPS))
    }

    @Test
    fun readAndWriteEveryField() {
        var config = ThrottleConfig()
        ParameterField.entries.forEach { field ->
            val value = field.range.start + 1.0
            config = field.write(config, value)
            assertEquals(field.name, value, field.read(config), 1e-4)
        }
    }

    @Test
    fun applyNoteCountsInvalidFields() {
        assertEquals(
            "Los valores válidos se aplican al instante, también con la simulación en curso.",
            ParameterInput.applyNote(0).es()
        )
        assertEquals("Hay 1 valor no válido: se mantiene el último correcto.", ParameterInput.applyNote(1).es())
        assertEquals(
            "Hay 3 valores no válidos: se mantienen los últimos correctos.",
            ParameterInput.applyNote(3).es()
        )
        assertEquals("There is 1 invalid value: the last valid one is kept.", ParameterInput.applyNote(1).en())
        assertEquals("There are 3 invalid values: the last valid ones are kept.", ParameterInput.applyNote(3).en())
    }

    @Test
    fun reorderIsDisabledWithoutLatency() {
        val noLatency = slow3g.copy(latencyMs = 0, reorderPercent = 5f)
        val states = ParameterInput.fieldStates(
            mapOf(ParameterField.REORDER_PERCENT to "99"),
            noLatency,
            slow3g,
            BandwidthUnit.KBPS
        )
        val reorder = states.single { it.field == ParameterField.REORDER_PERCENT }
        assertFalse(reorder.enabled)
        assertNull("Un campo desactivado no muestra error", reorder.error)
        assertEquals("Requiere latencia mayor que 0", reorder.supportingText.es())
        assertTrue(ParameterField.REORDER_PERCENT.isEnabled(slow3g))
    }

    @Test
    fun fieldStatesShowErrorsHintsAndChanges() {
        val config = slow3g.copy(latencyMs = 250)
        val drafts = ParameterInput.drafts(config, BandwidthUnit.KBPS) +
            (ParameterField.JITTER_MS to "500") + (ParameterField.UP_LOSS_PERCENT to "")
        val states = ParameterInput.fieldStates(drafts, config, slow3g, BandwidthUnit.KBPS).associateBy { it.field }

        assertEquals("Rango: 0 – 200 ms", states.getValue(ParameterField.JITTER_MS).error.es())
        assertNull("Vacío equivale a 0, que es válido", states.getValue(ParameterField.UP_LOSS_PERCENT).error)
        assertTrue(states.getValue(ParameterField.LATENCY_MS).changed)
        assertFalse(states.getValue(ParameterField.DOWN_KBPS).changed)
        assertEquals("= 0.384 Mbps · 48 KB/s", states.getValue(ParameterField.DOWN_KBPS).supportingText.es())
        assertEquals("1 = pérdidas independientes", states.getValue(ParameterField.LOSS_BURST).supportingText.es())
        assertEquals("0 – 1000 ms", states.getValue(ParameterField.LATENCY_MS).supportingText.es())

        val mbps = ParameterInput.fieldStates(
            ParameterInput.drafts(config, BandwidthUnit.MBPS),
            config.copy(lossBurstLength = 3),
            slow3g,
            BandwidthUnit.MBPS
        ).associateBy { it.field }
        assertEquals("= 384 kbps · 48 KB/s", mbps.getValue(ParameterField.DOWN_KBPS).supportingText.es())
        assertEquals("Pérdidas en ráfagas de 3", mbps.getValue(ParameterField.LOSS_BURST).supportingText.es())
        assertEquals("Losses in bursts of 3", mbps.getValue(ParameterField.LOSS_BURST).supportingText.en())
        assertEquals("Mbps", ParameterInput.suffix(ParameterField.DOWN_KBPS, BandwidthUnit.MBPS).es())
        assertEquals("ms", ParameterInput.suffix(ParameterField.LATENCY_MS, BandwidthUnit.MBPS).es())
        assertEquals("packets", ParameterInput.suffix(ParameterField.LOSS_BURST, BandwidthUnit.KBPS).en())
    }
}
