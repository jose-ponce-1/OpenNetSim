# Reglas de R8 para OpenNetSim. AndroidX, Compose y las corrutinas traen sus propias reglas, y los
# componentes del manifiesto se conservan solos: aquí solo va lo que el código necesita además.

# ConfigStore y TunnelIntents guardan el preset por su nombre (`preset.name`). Sin esta regla R8 puede
# optimizar el enum y la configuración guardada dejaría de leerse tras actualizar la app.
-keep enum com.opennetsim.model.ThrottlePreset { *; }

# La unidad de ancho de banda elegida se guarda por su nombre (`BandwidthUnit.name`), igual que el preset.
-keep enum com.opennetsim.ui.BandwidthUnit { *; }
