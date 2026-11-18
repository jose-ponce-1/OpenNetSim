package com.opennetsim.ui

import java.util.UUID

/**
 * Cómo se identifican y nombran los perfiles que guarda el usuario.
 *
 * @param reservedNames nombres de los perfiles predefinidos en todos los idiomas de la app, que un perfil
 *   guardado no puede usar (los calcula `MainActivity` a partir de los recursos).
 * @param newId genera el id de un perfil nuevo; los tests pasan uno predecible.
 */
class ProfileNaming(
    val reservedNames: Set<String> = emptySet(),
    val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") }
)
