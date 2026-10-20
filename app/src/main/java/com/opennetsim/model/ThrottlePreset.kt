package com.opennetsim.model

/**
 * Perfiles preconfigurados para simular diferentes escenarios de degradación de red.
 */
enum class ThrottlePreset(
    val displayName: String,
    val description: String,
    val downKbps: Int,
    val upKbps: Int,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float
) {
    CUSTOM(
        displayName = "Personalizado",
        description = "Ajuste manual de todos los parámetros de red.",
        downKbps = 1000,
        upKbps = 500,
        latencyMs = 100,
        jitterMs = 20,
        packetLossPercent = 0f
    ),
    SLOW_3G(
        displayName = "3G Lento",
        description = "Simula conectividad móvil 3G débil y congestionada.",
        downKbps = 384,
        upKbps = 128,
        latencyMs = 200,
        jitterMs = 40,
        packetLossPercent = 2.0f
    ),
    EDGE_2G(
        displayName = "2G / EDGE",
        description = "Conexión móvil muy limitada de baja velocidad y alto ping.",
        downKbps = 128,
        upKbps = 48,
        latencyMs = 400,
        jitterMs = 80,
        packetLossPercent = 5.0f
    ),
    UNSTABLE_WIFI(
        displayName = "Wi-Fi Inestable",
        description = "Red local con interferencias, jitter considerable y pérdida periódica.",
        downKbps = 5000,
        upKbps = 2000,
        latencyMs = 120,
        jitterMs = 60,
        packetLossPercent = 8.0f
    ),
    HIGH_LATENCY(
        displayName = "Alta Latencia",
        description = "Simula enlace satelital o servidores geográficamente remotos.",
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
                packetLossPercent = preset.packetLossPercent,
                preset = preset
            )
        }
    }
}

