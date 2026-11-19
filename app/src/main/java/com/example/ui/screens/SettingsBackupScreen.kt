package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.data.backup.BackupFileInfo
import com.example.domain.SyncConflictDetail
import com.example.domain.SyncStatusSummary
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.SyncStatusBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.viewmodel.PinState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    pinState: PinState,
    scanCooldownMs: Long,
    localBackups: List<BackupFileInfo>,
    syncStatus: SyncStatusSummary,
    openConflicts: List<SyncConflictDetail>,
    manualOfflineMode: Boolean,
    onLoadSyncServerConfig: suspend () -> Pair<String, String>,
    onConfigureSyncServer: (String, String) -> Unit,
    onSyncNow: () -> Unit,
    onToggleManualOffline: (Boolean) -> Unit,
    onResolveConflictKeepLocal: (Long) -> Unit,
    onResolveConflictMarkReviewed: (Long) -> Unit,
    onConfigurePin: (String) -> Unit,
    onTogglePinLock: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    onUpdateScanCooldown: (Long) -> Unit,
    onCreateLocalBackup: () -> Unit,
    onExportBackupToUri: (Uri) -> Unit,
    onRestoreFromLocalFile: (String) -> Unit,
    onRestoreFromUri: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")) }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showSyncServerDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf("") }
    var serverTokenInput by remember { mutableStateOf("") }
    var pendingRestoreLocalPath by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        val (url, token) = onLoadSyncServerConfig()
        serverUrlInput = url
        serverTokenInput = token
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportBackupToUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración y respaldos", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Automatic Offline-First Sync Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sincronización automática", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            SyncStatusBadge(syncStatus = syncStatus)
                        }

                        Text(
                            text = buildString {
                                append("Pendientes: ${syncStatus.pendingCount} • Sincronizados: ${syncStatus.syncedCount}")
                                syncStatus.lastSyncAt?.let {
                                    append("\nÚltima sincronización: ${dateFormat.format(Date(it))}")
                                }
                                syncStatus.lastSyncMessage?.let {
                                    append("\n$it")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Modo sin conexión manual", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Úsalo para trabajar sin red o probar la cola local pendiente",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = manualOfflineMode,
                                onCheckedChange = onToggleManualOffline,
                                modifier = Modifier.testTag("manual_offline_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSyncNow,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sync_now_btn")
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sincronizar ahora")
                            }
                            OutlinedButton(
                                onClick = { showSyncServerDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (syncStatus.remoteUrlConfigured) "Servidor API" else "Servidor (opc.)")
                            }
                        }

                        Text(
                            text = if (syncStatus.remoteUrlConfigured) {
                                "Servidor remoto configurado. Los cambios pendientes se envían automáticamente al volver internet sin duplicar productos ni movimientos."
                            } else {
                                "Funciona 100% sin internet con cola local persistente y registro idempotente. Si deseas sincronizar entre dispositivos, configura una URL HTTPS de servidor en 'Servidor (opc.)'."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Conflicts review section (only when open conflicts exist)
            if (openConflicts.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Conflictos para revisar (${openConflicts.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StatusError
                        )
                    }
                }

                items(openConflicts, key = { it.id }) { conflict ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.06f)),
                        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${conflict.entityType} • ${conflict.entityKey}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = JetBrainsMonoFamily
                            )
                            Text(
                                text = conflict.reason,
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider()
                            Text(
                                text = "Local (${dateFormat.format(Date(conflict.localTimestamp))}): ${conflict.localPayloadJson}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily
                            )
                            Text(
                                text = "Más reciente (${dateFormat.format(Date(conflict.remoteTimestamp))}): ${conflict.remotePayloadJson}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onResolveConflictMarkReviewed(conflict.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("conflict_reviewed_${conflict.id}")
                                ) {
                                    Text("Conservar reciente")
                                }
                                Button(
                                    onClick = { onResolveConflictKeepLocal(conflict.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("conflict_keep_local_${conflict.id}")
                                ) {
                                    Text("Forzar local")
                                }
                            }
                        }
                    }
                }
            }

            // Security Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Seguridad (Android Keystore)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Solicitar PIN al abrir", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Protegido con cifrado AES-256-GCM en Android Keystore",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = pinState.isPinLockEnabled,
                                onCheckedChange = { onTogglePinLock(it) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showChangePinDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (pinState.isPinConfigured) "Cambiar PIN" else "Crear PIN")
                            }
                            if (pinState.isPinConfigured) {
                                Button(
                                    onClick = onLockNow,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Bloquear ahora")
                                }
                            }
                        }
                    }
                }
            }

            // Scanner anti-duplicate cooldown
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Protección anti-doble escaneo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Pausa mínima antes de aceptar nuevamente el mismo código:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1500L, 2500L, 4000L).forEach { ms ->
                                FilterChip(
                                    selected = scanCooldownMs == ms,
                                    onClick = { onUpdateScanCooldown(ms) },
                                    label = { Text("${ms / 1000.0}s") }
                                )
                            }
                        }
                    }
                }
            }

            // Backups Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copias de seguridad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Formato JSON versionado (v2) con verificación SHA-256. Incluye productos, códigos, equivalencias, existencias, movimientos, cola de sincronización, inventarios iniciales y ajustes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onCreateLocalBackup,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("create_backup_btn")
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Crear copia de seguridad local")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                    exportLauncher.launch("mi_inventario_$stamp.json")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Exportar")
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Importar")
                            }
                        }
                    }
                }
            }

            // Local Backups List
            item {
                Text(
                    "Respaldos locales (${localBackups.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (localBackups.isEmpty()) {
                item {
                    Text(
                        "Aún no has creado copias locales. Toca 'Crear copia de seguridad local' arriba.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(localBackups, key = { it.absolutePath }) { backup ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(backup.fileName, style = MaterialTheme.typography.titleSmall, fontFamily = JetBrainsMonoFamily)
                                Text(
                                    "${dateFormat.format(Date(backup.createdAt))} • ${(backup.sizeBytes / 1024.0).let { String.format(Locale.US, "%.1f KB", it) }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(onClick = {
                                    runCatching {
                                        val file = File(backup.absolutePath)
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Compartir respaldo"))
                                    }
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "Compartir respaldo")
                                }
                                IconButton(
                                    onClick = { pendingRestoreLocalPath = backup.absolutePath },
                                    modifier = Modifier.testTag("restore_backup_btn")
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = "Restaurar respaldo")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showSyncServerDialog) {
        AlertDialog(
            onDismissRequest = { showSyncServerDialog = false },
            title = { Text("Servidor de sincronización (opcional)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Deja la URL vacía para usar el registro local persistente sin servidor externo. Si cuentas con un endpoint REST propio (POST JSON con idempotencyKey), ingrésalo aquí:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("URL del endpoint HTTPS") },
                        placeholder = { Text("https://tu-servidor.com/api/sync") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = serverTokenInput,
                        onValueChange = { serverTokenInput = it },
                        label = { Text("Token Bearer (cifrado con Keystore)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onConfigureSyncServer(serverUrlInput, serverTokenInput)
                    showSyncServerDialog = false
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showSyncServerDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showChangePinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = { Text("Configurar PIN de administrador") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6) newPin = it.filter(Char::isDigit); error = null },
                        label = { Text("Nuevo PIN (4 a 6 dígitos)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6) confirmPin = it.filter(Char::isDigit); error = null },
                        label = { Text("Confirmar PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length !in 4..6) error = "Debe tener entre 4 y 6 dígitos."
                    else if (newPin != confirmPin) error = "Los PIN no coinciden."
                    else {
                        onConfigurePin(newPin)
                        showChangePinDialog = false
                    }
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) { Text("Cancelar") }
            }
        )
    }

    pendingRestoreLocalPath?.let { path ->
        ConfirmationDialog(
            title = "Restaurar copia de seguridad",
            message = "Esta acción validará la integridad de la copia y reemplazará los datos actuales de Mi Inventario. Si la copia presenta errores, no se borrará la información existente.",
            confirmText = "Restaurar",
            isDestructive = true,
            onConfirm = {
                pendingRestoreLocalPath = null
                onRestoreFromLocalFile(path)
            },
            onDismiss = { pendingRestoreLocalPath = null }
        )
    }

    pendingRestoreUri?.let { uri ->
        ConfirmationDialog(
            title = "Importar y restaurar copia",
            message = "Se validará la integridad del archivo seleccionado antes de reemplazar los datos actuales. ¿Deseas continuar?",
            confirmText = "Restaurar",
            isDestructive = true,
            onConfirm = {
                pendingRestoreUri = null
                onRestoreFromUri(uri)
            },
            onDismiss = { pendingRestoreUri = null }
        )
    }
}
