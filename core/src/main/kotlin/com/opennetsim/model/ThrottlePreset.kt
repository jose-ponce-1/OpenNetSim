package com.opennetsim.model

/**
 * Perfiles preconfigurados para simular diferentes escenarios de degradación de red.
 *
 * [packetLossPercent] se aplica por igual a la subida y a la bajada, sin ráfagas ni reordenación. Los
 * nombres y descripciones que ve el usuario están traducidos en los recursos de `:app` (`PresetTexts`);
 * el nombre del enum es el que se guarda en la configuración.
 */
enum class ThrottlePreset(
    val downKbps: Int,
    val upKbps: Int,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float
) {
    CUSTOM(
        downKbps = 1000,
        upKbps = 500,
        latencyMs = 100,
        jitterMs = 20,
        packetLossPercent = 0f
    ),
    SLOW_3G(
        downKbps = 384,
        upKbps = 128,
        latencyMs = 200,
        jitterMs = 40,
        packetLossPercent = 2.0f
    ),
    EDGE_2G(
        downKbps = 128,
        upKbps = 48,
        latencyMs = 400,
        jitterMs = 80,
        packetLossPercent = 5.0f
    ),
    UNSTABLE_WIFI(
        downKbps = 5000,
        upKbps = 2000,
        latencyMs = 120,
        jitterMs = 60,
        packetLossPercent = 8.0f
    ),
    HIGH_LATENCY(
        downKbps = 10000,
        upKbps = 2000,
        latencyMs = 600,
        jitterMs = 20,
        packetLossPercent = 0.0f
    );

    companion object {
        fun fromPreset(preset: ThrottlePreset): ThrottleConfig {
            return ThrottleConfig(
                downKbps = preset.downKbps,
                upKbps = preset.upKbps,
                latencyMs = preset.latencyMs,
                jitterMs = preset.jitterMs,
                upLossPercent = preset.packetLossPercent,
                downLossPercent = preset.packetLossPercent,
                preset = preset
            )
        }
    }
}
