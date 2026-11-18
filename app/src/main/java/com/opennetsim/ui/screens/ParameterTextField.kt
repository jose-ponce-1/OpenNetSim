package com.opennetsim.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.opennetsim.ui.BandwidthUnit
import com.opennetsim.ui.ParameterField
import com.opennetsim.ui.ParameterFieldState
import com.opennetsim.ui.ParameterInput
import com.opennetsim.ui.components.TabularNumbers

/**
 * Campo de valor exacto. El texto que se está escribiendo vive aquí y se envía al ViewModel en cada
 * cambio; los textos que el ViewModel devuelve se reconocen como eco y no pisan lo que el usuario sigue
 * escribiendo. Cualquier otro texto del ViewModel (perfil elegido, restablecer, unidad, reformateo al
 * salir) sustituye al del campo.
 *
 * Sin icono de error: en pantallas de 360 dp taparía el número; el error ya lo marcan el borde, la
 * etiqueta y el mensaje (y `isError` para TalkBack).
 */
@Composable
fun ParameterTextField(
    state: ParameterFieldState,
    unit: BandwidthUnit,
    actions: ParameterActions,
    modifier: Modifier = Modifier
) {
    val field = state.field
    var value by remember { mutableStateOf(TextFieldValue(state.text, TextRange(state.text.length))) }
    val pendingEchoes = remember { ArrayDeque<String>() }
    var hadFocus by remember { mutableStateOf(false) }

    LaunchedEffect(state.text) {
        val echo = pendingEchoes.indexOf(state.text)
        if (echo >= 0) {
            repeat(echo + 1) { pendingEchoes.removeFirst() }
        } else if (state.text != value.text) {
            pendingEchoes.clear()
            value = TextFieldValue(state.text, TextRange(state.text.length))
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = { updated ->
            val changed = updated.text != value.text
            value = updated
            if (changed) {
                pendingEchoes.addLast(updated.text)
                actions.onTextChange(field, updated.text)
            }
        },
        modifier = modifier.onFocusChanged { focus ->
            if (hadFocus && !focus.isFocused) actions.onFocusLost(field)
            hadFocus = focus.isFocused
        },
        enabled = state.enabled,
        textStyle = LocalTextStyle.current.merge(TabularNumbers),
        label = { Text(if (state.changed) "${field.label} •" else field.label) },
        prefix = if (field.prefix.isNotEmpty()) {
            { Text(field.prefix) }
        } else {
            null
        },
        suffix = { Text(ParameterInput.suffix(field, unit)) },
        supportingText = { Text(state.supportingText) },
        isError = state.error != null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = if (field == ParameterField.entries.last()) ImeAction.Done else ImeAction.Next
        ),
        singleLine = true
    )
}
