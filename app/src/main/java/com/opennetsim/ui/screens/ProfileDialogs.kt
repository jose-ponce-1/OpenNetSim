package com.opennetsim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opennetsim.R
import com.opennetsim.ui.ConnectionProfile
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.ProfileCatalog
import com.opennetsim.ui.ProfileNameCheck
import com.opennetsim.ui.ProfileRef
import com.opennetsim.ui.UiFormatters
import com.opennetsim.ui.components.asString
import kotlinx.coroutines.launch

/** Hoja con todos los perfiles: los guardados (con eliminar) y los predefinidos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(
    state: MainUiState,
    actions: ProfileActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Se cierra con su animación; el aviso de eliminar (con «Deshacer») queda visible debajo.
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.profiles_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            IconButton(onClick = close) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
        SheetProfileList(state, actions, close)
    }
}

/** Lista de la hoja: elegir un perfil o eliminar uno guardado la cierra con [close]. */
@Composable
private fun SheetProfileList(state: MainUiState, actions: ProfileActions, close: () -> Unit) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        SheetHeader(stringResource(R.string.profiles_mine))
        val all = state.profiles
        val saved = all.filter { it.isSaved }
        if (saved.isEmpty()) NoSavedProfiles()
        Column(Modifier.selectableGroup()) {
            saved.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    selected = profile.ref == state.selectedProfile,
                    onSelect = {
                        actions.onSelect(profile.ref)
                        close()
                    },
                    onDelete = {
                        (profile.ref as? ProfileRef.Saved)?.let { actions.onDelete(it.id) }
                        close()
                    }
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SheetHeader(stringResource(R.string.profiles_builtin))
        Column(Modifier.selectableGroup()) {
            all.filterNot { it.isSaved }.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    selected = profile.ref == state.selectedProfile,
                    onSelect = {
                        actions.onSelect(profile.ref)
                        close()
                    },
                    onDelete = null
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun NoSavedProfiles() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.profiles_none_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ConnectionProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val name = profile.name.asString()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 72.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // «Personalizado» no tiene valores propios: conserva los actuales.
                    text = if (profile.ref == ProfileRef.CUSTOM) {
                        stringResource(R.string.profile_custom_keeps_values)
                    } else {
                        UiFormatters.summary(profile.config)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.profile_delete_content_description, name)
                )
            }
        }
    }
}

/** Diálogo para guardar los valores actuales con un nombre. */
@Composable
fun SaveProfileDialog(
    state: MainUiState,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable { mutableStateOf("") }
    val check = ProfileCatalog.checkName(name, state.savedProfiles, state.reservedProfileNames)
    val canSave = check == ProfileNameCheck.VALID
    val showError = check == ProfileNameCheck.DUPLICATE || check == ProfileNameCheck.TOO_LONG
    val focusRequester = remember { FocusRequester() }
    val save = { if (canSave && onSave(name)) onDismiss() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
        title = { Text(stringResource(R.string.action_save_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.save_profile_body, UiFormatters.summary(state.config)))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ProfileCatalog.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.save_profile_name_label)) },
                    placeholder = { Text(stringResource(R.string.save_profile_name_placeholder)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = {
                        Row {
                            Text(
                                text = if (showError) {
                                    check.message?.asString().orEmpty()
                                } else {
                                    stringResource(R.string.save_profile_hint)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                stringResource(R.string.value_name_counter, name.length, ProfileCatalog.MAX_NAME_LENGTH)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = canSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        modifier = modifier
    )
}
