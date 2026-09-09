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
import works.resolve.pathfinder.ssh.SshHost

/** SSH host list (Settings ▸ SSH hosts). */
@Composable
internal fun SshHostsContent(
    hosts: List<SshHost>,
    onAddHost: () -> Unit,
    onOpenHost: (hostId: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = onAddHost) {
            Text(stringResource(R.string.ssh_hosts_add))
        }
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(R.string.ssh_hosts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column {
                hosts.forEach { host ->
                    ListItem(
                        headlineContent = { Text(hostLabel(host)) },
                        supportingContent = {
                            Text(stringResource(R.string.ssh_host_port, host.port))
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { onOpenHost(host.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Add/edit form. A new host is created on save; editing keeps the host's
 * keypair and changes only the connection fields. The public-key section
 * (edit only) shows the authorized_keys line for copying — the only key
 * material the UI ever sees. Also edit-only: the connection test (progress
 * and result inline), which dials the saved host.
 */
@Composable
internal fun SshHostEditContent(
    host: SshHost?,
    hostTest: HostTestState?,
    onTestConnection: (hostId: String) -> Unit,
    onSave: (address: String, port: Int, username: String, cwd: String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit
) {
    var address by rememberSaveable(host?.id) { mutableStateOf(host?.address.orEmpty()) }
    var port by rememberSaveable(host?.id) { mutableStateOf(host?.port?.toString().orEmpty()) }
    var username by rememberSaveable(host?.id) { mutableStateOf(host?.username.orEmpty()) }
    var cwd by rememberSaveable(host?.id) { mutableStateOf(host?.cwd.orEmpty()) }
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
            label = { Text(stringResource(R.string.ssh_host_address)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text(stringResource(R.string.ssh_host_port_label)) },
            isError = port.isNotEmpty() && (portNumber == null || portNumber !in 1..65535),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.ssh_host_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cwd,
            onValueChange = { cwd = it },
            label = { Text(stringResource(R.string.ssh_host_cwd)) },
            supportingText = {
                Text(stringResource(R.string.ssh_host_cwd_hint))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (host != null) {
            Text(
                text = stringResource(R.string.ssh_host_public_key_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = host.publicKeyLine,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(host.publicKeyLine)) }) {
                Text(stringResource(R.string.ssh_host_copy_public_key))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onTestConnection(host.id) },
                    enabled = hostTest?.running != true
                ) {
                    Text(stringResource(R.string.ssh_host_test))
                }
                if (hostTest?.running == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            hostTest?.message?.let { message ->
                Text(
                    text = message.asText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hostTest.success) {
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
            if (host != null) {
                TextButton(
                    onClick = { confirmRemove = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.ssh_host_delete))
                }
            }
        }
    }

    if (confirmRemove && host != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.ssh_host_delete)) },
            text = { Text(stringResource(R.string.ssh_host_delete_confirm, hostLabel(host))) },
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
                    Text(stringResource(R.string.ssh_host_delete))
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

private fun hostLabel(host: SshHost): String = "${host.username}@${host.address}"
