package com.opennetsim.ui.components

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import com.opennetsim.ui.UiText

/** Texto en el idioma de [resources]; los argumentos que son [UiText] se resuelven antes. */
@Suppress("SpreadOperator") // getString y getQuantityString solo aceptan los argumentos como vararg.
fun UiText.asString(resources: Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *resolveArgs(args, resources))
    is UiText.Plural -> resources.getQuantityString(id, count, *resolveArgs(args, resources))
    is UiText.Raw -> text
}

fun UiText.asString(context: Context): String = asString(context.resources)

/** Texto en el idioma activo; se vuelve a resolver si cambia la configuración (p. ej. el idioma). */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = asString(LocalResources.current)

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UiText) it.asString(resources) else it }.toTypedArray()
