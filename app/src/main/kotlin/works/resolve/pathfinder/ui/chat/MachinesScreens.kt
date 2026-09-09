package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ssh.Machine

/** Machine list (Settings ▸ Machines). */
@Composable
internal fun MachinesContent(
    machines: List<Machine>,
    onAddMachine: () -> Unit,
    onOpenMachine: (machineId: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = onAddMachine) {
            Text(stringResource(R.string.machines_add))
        }
        if (machines.isEmpty()) {
            Text(
                text = stringResource(R.string.machines_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column {
                machines.forEach { machine ->
                    ListItem(
                        headlineContent = { Text(machineLabel(machine)) },
                        supportingContent = {
                            Text(stringResource(R.string.machine_port, machine.port))
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { onOpenMachine(machine.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Add/edit form. A new machine is created on save; editing keeps the machine's
 * keypair and changes only the connection fields. The public-key section
 * (edit only) shows the authorized_keys line for copying — the only key
 * material the UI ever sees. Also edit-only: the connection test (progress
 * and result inline), which dials the saved machine.
 */
@Composable
internal fun MachineEditContent(
    machine: Machine?,
    machineTest: MachineTestState?,
    onTestConnection: (machineId: String) -> Unit,
    onSave: (address: String, port: Int, username: String, cwd: String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit
) {
    var address by rememberSaveable(machine?.id) { mutableStateOf(machine?.address.orEmpty()) }
    var port by rememberSaveable(machine?.id) {
        mutableStateOf(machine?.port?.toString().orEmpty())
    }
    var username by rememberSaveable(machine?.id) { mutableStateOf(machine?.username.orEmpty()) }
    var cwd by rememberSaveable(machine?.id) { mutableStateOf(machine?.cwd.orEmpty()) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val portNumber = port.trim().toIntOrNull()
    val valid = address.isNotBlank() &&
        username.isNotBlank() &&
        cwd.trim().startsWith("/") &&
        portNumber != null && portNumber in 1..65535

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.machine_address)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text(stringResource(R.string.machine_port_label)) },
            isError = port.isNotEmpty() && (portNumber == null || portNumber !in 1..65535),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.machine_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cwd,
            onValueChange = { cwd = it },
            label = { Text(stringResource(R.string.machine_cwd)) },
            supportingText = {
                Text(stringResource(R.string.machine_cwd_hint))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (machine != null) {
            Text(
                text = stringResource(R.string.machine_public_key_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = machine.publicKeyLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(machine.publicKeyLine)) }) {
                Text(stringResource(R.string.machine_copy_public_key))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onTestConnection(machine.id) },
                    enabled = machineTest?.running != true
                ) {
                    Text(stringResource(R.string.machine_test))
                }
                if (machineTest?.running == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            machineTest?.message?.let { message ->
                Text(
                    text = message.asText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (machineTest.success) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(address, portNumber ?: 0, username, cwd) },
                enabled = valid
            ) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
            if (machine != null) {
                TextButton(
                    onClick = { confirmRemove = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.machine_delete))
                }
            }
        }
    }

    if (confirmRemove && machine != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.machine_delete)) },
            text = { Text(stringResource(R.string.machine_delete_confirm, machineLabel(machine))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.machine_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun machineLabel(machine: Machine): String = "${machine.username}@${machine.address}"
