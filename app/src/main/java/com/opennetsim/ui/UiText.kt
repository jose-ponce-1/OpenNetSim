package com.opennetsim.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Texto para el usuario aún sin resolver. La lógica de la interfaz (probada en JVM) dice qué texto
 * mostrar y la interfaz lo resuelve en el idioma activo al pintarlo, así que un aviso ya visible cambia de
 * idioma si el usuario cambia el de la app. Los argumentos pueden ser a su vez [UiText].
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    /** Texto que no se traduce, como el nombre de un perfil que escribió el usuario. */
    data class Raw(val text: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        /** Plural cuyo único argumento es el propio recuento. */
        fun plural(@PluralsRes id: Int, count: Int): UiText = Plural(id, count, listOf(count))
    }
}
