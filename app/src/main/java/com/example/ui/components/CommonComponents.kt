package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.AppDatabase
import com.example.data.local.AreaEntity
import com.example.data.repository.InventoryRepository
import com.example.domain.AreaId
import com.example.domain.BaseUnit
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.domain.SyncStatusSummary
import com.example.domain.SyncVisualStatus
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.areaAccentColor
import kotlinx.coroutines.launch

@Composable
fun SyncStatusBadge(
    syncStatus: SyncStatusSummary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val (bg, tint, icon, text) = when (syncStatus.visualStatus) {
        SyncVisualStatus.SIN_CONEXION -> Quadruple(
            StatusWarning.copy(alpha = 0.14f),
            StatusWarning,
            Icons.Default.CloudOff,
            if (syncStatus.pendingCount > 0) "Sin conexión • ${syncStatus.pendingCount} pend."
            else "Sin conexión"
        )
        SyncVisualStatus.PENDIENTE -> Quadruple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.primary,
            Icons.Default.CloudQueue,
            "Pendiente de sincronizar (${syncStatus.pendingCount})"
        )
        SyncVisualStatus.SINCRONIZANDO -> Quadruple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.primary,
            Icons.Default.CloudSync,
            "Sincronizando..."
        )
        SyncVisualStatus.CONFLICTO -> Quadruple(
            StatusError.copy(alpha = 0.14f),
            StatusError,
            Icons.Default.Warning,
            "Conflicto por revisar (${syncStatus.openConflictsCount})"
        )
        SyncVisualStatus.SINCRONIZADO -> Quadruple(
            StatusSuccess.copy(alpha = 0.14f),
            StatusSuccess,
            Icons.Default.CloudDone,
            "Sincronizado"
        )
    }

    Surface(
        modifier = modifier
            .then(
                if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
                else Modifier
            )
            .testTag("sync_status_badge"),
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@Composable
fun AreaBadge(
    areaId: Long,
    areaName: String,
    modifier: Modifier = Modifier
) {
    val accent = areaAccentColor(areaId)
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = areaName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirmar",
    dismissText: String = "Cancelar",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.testTag("confirm_dialog_button")
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}

@Composable
fun ProductPickerDialog(
    products: List<ProductSummary>,
    includeInactive: Boolean = false,
    onSelect: (ProductSummary) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(products, query, includeInactive) {
        val base = if (includeInactive) products else products.filter { it.isActive }
        val q = query.trim().lowercase()
        if (q.isEmpty()) base
        else base.filter { p ->
            p.name.lowercase().contains(q) ||
                p.internalCode.lowercase().contains(q) ||
                p.category.lowercase().contains(q) ||
                p.allCodes.any { it.lowercase().contains(q) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Seleccionar producto", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar selector")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar por nombre, código o categoría...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No se encontraron productos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(filtered, key = { it.productId }) { product ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(product) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            product.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = buildString {
                                                append(product.category)
                                                append(" • ")
                                                append(product.primaryBarcode ?: product.internalCode)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = QuantityFormatter.formatBaseQuantity(product.totalStockBase, product.baseUnit),
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DraftPresentationRow(
    val name: String,
    val factorText: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductFormDialog(
    initialProduct: ProductSummary? = null,
    prefillCode: String? = null,
    areas: List<AreaEntity> = emptyList(),
    initialPreferredAreaId: Long = initialProduct?.preferredAreaId ?: AreaId.ALMACEN_GENERAL.id,
    onCheckCodeConflicts: suspend (List<String>, Long?) -> List<Pair<String, String>>,
    onRequestBarcodeScan: ((String) -> Unit) -> Unit,
    onSaveNew: (
        name: String,
        primaryCode: String,
        additionalCodes: List<String>,
        description: String,
        category: String,
        baseUnit: BaseUnit,
        allowsDecimals: Boolean,
        allowFractionalPresentations: Boolean,
        defaultMinStock: Double?,
        extraPresentations: List<InventoryRepository.CustomPresentationDraft>,
        allowDuplicateCodeOverride: Boolean,
        customInternalCode: String?,
        preferredAreaId: Long,
        initialQuantityInput: Double?
    ) -> Unit,
    onUpdateExisting: (
        productId: Long,
        name: String,
        primaryCode: String,
        additionalCodes: List<String>,
        description: String,
        category: String,
        allowsDecimals: Boolean,
        allowFractionalPresentations: Boolean,
        defaultMinStock: Double?,
        isActive: Boolean,
        allowDuplicateCodeOverride: Boolean,
        customInternalCode: String?,
        preferredAreaId: Long
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val isEditing = initialProduct != null
    val scope = rememberCoroutineScope()
    val availableAreas = remember(areas) {
        if (areas.isNotEmpty()) areas.sortedBy { it.sortOrder } else AppDatabase.defaultAreas()
    }

    var name by remember { mutableStateOf(initialProduct?.name ?: "") }
    var internalCode by remember { mutableStateOf(initialProduct?.internalCode ?: "") }
    var primaryCode by remember {
        mutableStateOf(initialProduct?.primaryBarcode ?: prefillCode ?: "")
    }
    var additionalCodesText by remember {
        mutableStateOf(
            initialProduct?.allCodes
                ?.drop(if (initialProduct.primaryBarcode != null) 1 else 0)
                ?.joinToString(", ") ?: ""
        )
    }
    var description by remember { mutableStateOf(initialProduct?.description ?: "") }
    var category by remember { mutableStateOf(initialProduct?.category ?: "Bebidas") }
    var selectedAreaId by remember {
        mutableStateOf(initialProduct?.preferredAreaId ?: initialPreferredAreaId)
    }
    var initialStockText by remember { mutableStateOf("") }
    var selectedUnit by remember {
        mutableStateOf(initialProduct?.let { BaseUnit.fromKey(it.baseUnit) } ?: BaseUnit.BOTELLA)
    }
    var allowsDecimals by remember {
        mutableStateOf(initialProduct?.allowsDecimals ?: BaseUnit.BOTELLA.defaultAllowsDecimals)
    }
    var allowFractionalPresentations by remember {
        mutableStateOf(initialProduct?.allowFractionalPresentations ?: false)
    }
    var defaultMinStockText by remember {
        mutableStateOf(initialProduct?.defaultMinStock?.let { QuantityFormatter.formatNumber(it) } ?: "")
    }
    var isActive by remember { mutableStateOf(initialProduct?.isActive ?: true) }

    val customPresentations = remember { mutableStateListOf<DraftPresentationRow>() }
    var newPresName by remember { mutableStateOf("") }
    var newPresFactor by remember { mutableStateOf("") }

    var validationError by remember { mutableStateOf<String?>(null) }
    var duplicateWarning by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    val categories = listOf("Bebidas", "Licores", "Vinos", "Alimentos", "Insumos", "Limpieza", "General")

    fun attemptSave(overrideDuplicate: Boolean) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            validationError = "El nombre del producto es obligatorio."
            return
        }
        val cleanInternalCode = internalCode.trim().takeIf { it.isNotEmpty() }
        if (isEditing && cleanInternalCode == null) {
            validationError = "El código interno no puede quedar vacío al editar."
            return
        }

        val minStockVal = if (defaultMinStockText.isBlank()) null else {
            defaultMinStockText.trim().replace(",", ".").toDoubleOrNull()
                ?: run {
                    validationError = "El stock mínimo debe ser un número válido."
                    return
                }
        }
        if (minStockVal != null && minStockVal < 0.0) {
            validationError = "El stock mínimo no puede ser negativo."
            return
        }

        val initialQtyVal = if (!isEditing && initialStockText.isNotBlank()) {
            initialStockText.trim().replace(",", ".").toDoubleOrNull()
                ?: run {
                    validationError = "La existencia inicial debe ser un número válido."
                    return
                }
        } else null

        if (initialQtyVal != null && initialQtyVal < 0.0) {
            validationError = "La existencia inicial no puede ser negativa."
            return
        }

        val additionalList = additionalCodesText
            .split(",", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != primaryCode.trim() }
            .distinct()

        val drafts = mutableListOf<InventoryRepository.CustomPresentationDraft>()
        if (!isEditing) {
            for (row in customPresentations) {
                val f = row.factorText.replace(",", ".").toDoubleOrNull()
                if (row.name.isBlank() || f == null || f <= 0.0) {
                    validationError = "Equivalencia inválida en presentación '${row.name}'."
                    return
                }
                drafts.add(InventoryRepository.CustomPresentationDraft(row.name.trim(), f, allowFractionalPresentations))
            }
        }

        scope.launch {
            val allCodes = (listOf(primaryCode.trim()) + additionalList).filter { it.isNotEmpty() }
            if (!overrideDuplicate && allCodes.isNotEmpty()) {
                val conflicts = onCheckCodeConflicts(allCodes, initialProduct?.productId)
                if (conflicts.isNotEmpty()) {
                    duplicateWarning = conflicts
                    return@launch
                }
            }
            if (isEditing && initialProduct != null) {
                onUpdateExisting(
                    initialProduct.productId,
                    cleanName,
                    primaryCode.trim(),
                    additionalList,
                    description.trim(),
                    category.trim(),
                    allowsDecimals,
                    allowFractionalPresentations,
                    minStockVal,
                    isActive,
                    overrideDuplicate,
                    cleanInternalCode,
                    selectedAreaId
                )
            } else {
                onSaveNew(
                    cleanName,
                    primaryCode.trim(),
                    additionalList,
                    description.trim(),
                    category.trim(),
                    selectedUnit,
                    allowsDecimals,
                    allowFractionalPresentations,
                    minStockVal,
                    drafts,
                    overrideDuplicate,
                    cleanInternalCode,
                    selectedAreaId,
                    initialQtyVal
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEditing) "Editar producto" else "Registrar producto manualmente",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar formulario")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (validationError != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = validationError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; validationError = null },
                        label = { Text("Nombre del producto *") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product_name_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = internalCode,
                        onValueChange = { internalCode = it; validationError = null },
                        label = {
                            Text(if (isEditing) "Código interno *" else "Código interno (opcional, automático si se deja vacío)")
                        },
                        supportingText = {
                            Text("Identificador interno único (ej. BAR-001). Si queda vacío se genera automáticamente.")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product_internal_code_input"),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = primaryCode,
                            onValueChange = { primaryCode = it; validationError = null },
                            label = { Text("Código de barras o QR (opcional)") },
                            supportingText = { Text("Puedes guardar el producto aunque no tenga código de barras.") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("product_code_input"),
                            singleLine = true
                        )
                        IconButton(
                            onClick = {
                                onRequestBarcodeScan { scanned -> primaryCode = scanned }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Escanear código",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    OutlinedTextField(
                        value = additionalCodesText,
                        onValueChange = { additionalCodesText = it },
                        label = { Text("Códigos adicionales (opcional, separados por coma)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text("Área correspondiente *", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        availableAreas.forEach { area ->
                            FilterChip(
                                selected = selectedAreaId == area.id,
                                onClick = { selectedAreaId = area.id },
                                label = { Text(area.name) },
                                modifier = Modifier.testTag("product_area_chip_${area.id}")
                            )
                        }
                    }

                    Text("Categoría", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = category.equals(cat, ignoreCase = true),
                                onClick = { category = cat },
                                label = { Text(cat) }
                            )
                        }
                    }

                    if (!isEditing) {
                        Text("Unidad de medida base *", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BaseUnit.entries.forEach { unit ->
                                FilterChip(
                                    selected = selectedUnit == unit,
                                    onClick = {
                                        selectedUnit = unit
                                        allowsDecimals = unit.defaultAllowsDecimals
                                    },
                                    label = { Text(unit.plural.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = initialStockText,
                            onValueChange = { initialStockText = it; validationError = null },
                            label = { Text("Existencia inicial en área elegida (opcional)") },
                            supportingText = { Text("En ${selectedUnit.plural}. Déjalo vacío para iniciar en 0.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("product_initial_stock_input"),
                            singleLine = true
                        )
                    } else {
                        Text(
                            text = "Unidad base: ${BaseUnit.fromKey(initialProduct!!.baseUnit).plural}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Permitir decimales en unidad base", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Útil para litros, kilos o mililitros",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = allowsDecimals, onCheckedChange = { allowsDecimals = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Permitir fracciones de cajas/paquetes", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Desactivado por defecto para exigir cajas completas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowFractionalPresentations,
                            onCheckedChange = { allowFractionalPresentations = it }
                        )
                    }

                    OutlinedTextField(
                        value = defaultMinStockText,
                        onValueChange = { defaultMinStockText = it },
                        label = { Text("Stock mínimo sugerido (${selectedUnit.plural})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Descripción opcional") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product_description_input"),
                        maxLines = 2
                    )

                    if (!isEditing) {
                        HorizontalDivider()
                        Text(
                            "Presentaciones y equivalencias (opcional)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Ej: 1 caja = 24 ${selectedUnit.plural}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        customPresentations.forEachIndexed { index, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "1 ${row.name} = ${row.factorText} ${selectedUnit.plural}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                IconButton(onClick = { customPresentations.removeAt(index) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Quitar presentación")
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newPresName,
                                onValueChange = { newPresName = it },
                                label = { Text("Presentación (ej. caja)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newPresFactor,
                                onValueChange = { newPresFactor = it },
                                label = { Text("Equiv. (${selectedUnit.plural})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    val f = newPresFactor.trim().replace(",", ".").toDoubleOrNull()
                                    if (newPresName.isNotBlank() && f != null && f > 0.0) {
                                        customPresentations.add(
                                            DraftPresentationRow(newPresName.trim(), QuantityFormatter.formatNumber(f))
                                        )
                                        newPresName = ""
                                        newPresFactor = ""
                                        validationError = null
                                    } else {
                                        validationError = "Indica un nombre de presentación y una equivalencia positiva."
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Agregar equivalencia")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Producto activo", style = MaterialTheme.typography.titleSmall)
                            Switch(checked = isActive, onCheckedChange = { isActive = it })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = { attemptSave(overrideDuplicate = false) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("save_product_button")
                    ) {
                        Text(if (isEditing) "Guardar cambios" else "Registrar")
                    }
                }
            }
        }
    }

    duplicateWarning?.let { conflicts ->
        val details = conflicts.joinToString("\n") { "• Código ${it.first} → ${it.second}" }
        AlertDialog(
            onDismissRequest = { duplicateWarning = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Código ya asignado a otro producto") },
            text = {
                Text(
                    "Se detectó un código duplicado:\n\n$details\n\nNo se recomienda asociar el mismo código a productos diferentes. ¿Deseas corregirlo o continuar bajo tu responsabilidad?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        duplicateWarning = null
                        attemptSave(overrideDuplicate = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Asociar de todos modos")
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateWarning = null }) {
                    Text("Corregir código")
                }
            }
        )
    }
}
