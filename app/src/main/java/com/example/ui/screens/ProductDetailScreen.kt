package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.AreaEntity
import com.example.domain.BaseUnit
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.ui.components.AreaBadge
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.ProductFormDialog
import com.example.ui.scanner.InlineScannerDialog
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    product: ProductSummary?,
    areas: List<AreaEntity>,
    cooldownMs: Long,
    onCheckCodeConflicts: suspend (List<String>, Long?) -> List<Pair<String, String>>,
    onUpdateProduct: (
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
    onSetActive: (Long, Boolean) -> Unit,
    onAddPresentation: (Long, String, Double, Boolean) -> Unit,
    onSetAreaMinStock: (Long, Long, Double?) -> Unit,
    onRegisterAdjustment: (Long, Long, Double, Long?, String) -> Unit,
    onQuickEntry: (Long) -> Unit,
    onQuickExit: (Long) -> Unit,
    onQuickTransfer: (Long) -> Unit,
    onBack: () -> Unit
) {
    if (product == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ficha de producto") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Producto no encontrado.", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeactivateConfirm by remember { mutableStateOf(false) }
    var showAddPresDialog by remember { mutableStateOf(false) }
    var editingMinStockArea by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var adjustingArea by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var pendingScanCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val baseUnit = BaseUnit.fromKey(product.baseUnit)
    val largestPack = product.presentations.filter { !it.isBase }.maxByOrNull { it.baseUnitsPerPresentation }
    val prefArea = areas.firstOrNull { it.id == product.preferredAreaId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product.name, maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.testTag("edit_product_btn")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar producto")
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = product.internalCode,
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                color = if (product.isActive) StatusSuccess.copy(alpha = 0.14f) else StatusError.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (product.isActive) "ACTIVO" else "INACTIVO",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (product.isActive) StatusSuccess else StatusError,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(product.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${product.category} • Unidad base: ${baseUnit.plural}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (prefArea != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Área asignada:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                AreaBadge(areaId = prefArea.id, areaName = prefArea.name)
                            }
                        }
                        if (product.description.isNotBlank()) {
                            Text(product.description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (product.allCodes.isNotEmpty()) {
                            Text(
                                text = "Códigos: ${product.allCodes.joinToString(" • ")}",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Sin código de barras (registro manual)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Total general: ${
                                QuantityFormatter.formatReadableWithPresentation(
                                    product.totalStockBase,
                                    product.baseUnit,
                                    largestPack?.name,
                                    largestPack?.baseUnitsPerPresentation
                                )
                            }",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Quick Actions
            if (product.isActive) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onQuickEntry(product.productId) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Entrada")
                        }
                        Button(
                            onClick = { onQuickExit(product.productId) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Salida")
                        }
                        OutlinedButton(
                            onClick = { onQuickTransfer(product.productId) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Transferir")
                        }
                    }
                }
            }

            // Stock per area + min stock + adjustment
            item {
                Text(
                    "Existencias y stock mínimo por área",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    product.stocksByArea.forEach { stock ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(
                                1.dp,
                                if (stock.isLowStock) StatusWarning.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AreaBadge(areaId = stock.areaId, areaName = stock.areaName)
                                    Text(
                                        text = QuantityFormatter.formatReadableWithPresentation(
                                            stock.quantityBase,
                                            product.baseUnit,
                                            largestPack?.name,
                                            largestPack?.baseUnitsPerPresentation
                                        ),
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stock.minStockBase?.let {
                                            "Mínimo: ${QuantityFormatter.formatBaseQuantity(it, product.baseUnit)}"
                                        } ?: "Sin stock mínimo configurado",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (stock.isLowStock) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = {
                                            editingMinStockArea = stock.areaId to stock.areaName
                                        }) {
                                            Text("Mínimo")
                                        }
                                        TextButton(onClick = {
                                            adjustingArea = stock.areaId to stock.areaName
                                        }) {
                                            Icon(Icons.Default.Tune, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Ajustar")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Presentations & Equivalences
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Presentaciones y equivalencias",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { showAddPresDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Agregar")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    product.presentations.forEach { pres ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (pres.isBase) "${pres.name} (Unidad base)" else pres.name,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "1 ${pres.name} = ${QuantityFormatter.formatBaseQuantity(pres.baseUnitsPerPresentation, product.baseUnit)}",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Deactivate / Reactivate without losing history
            item {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        if (product.isActive) showDeactivateConfirm = true
                        else onSetActive(product.productId, true)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (product.isActive) "Desactivar producto (conservar historial)" else "Reactivar producto",
                        color = if (product.isActive) StatusError else StatusSuccess
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeactivateConfirm) {
        ConfirmationDialog(
            title = "Desactivar producto",
            message = "El producto '${product.name}' ya no aparecerá en nuevas operaciones, pero conservará todo su historial y existencias anteriores.",
            confirmText = "Desactivar",
            isDestructive = true,
            onConfirm = {
                showDeactivateConfirm = false
                onSetActive(product.productId, false)
            },
            onDismiss = { showDeactivateConfirm = false }
        )
    }

    if (showEditDialog) {
        ProductFormDialog(
            initialProduct = product,
            prefillCode = null,
            areas = areas,
            initialPreferredAreaId = product.preferredAreaId,
            onCheckCodeConflicts = onCheckCodeConflicts,
            onRequestBarcodeScan = { callback -> pendingScanCallback = callback },
            onSaveNew = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            onUpdateExisting = { id, name, primary, additional, desc, cat, dec, frac, minStock, active, overrideDup, customInternal, prefAreaId ->
                onUpdateProduct(id, name, primary, additional, desc, cat, dec, frac, minStock, active, overrideDup, customInternal, prefAreaId)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false }
        )
    }

    if (showAddPresDialog) {
        var presName by remember { mutableStateOf("") }
        var factorText by remember { mutableStateOf("") }
        var allowsFractions by remember { mutableStateOf(product.allowFractionalPresentations) }
        var localError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddPresDialog = false },
            title = { Text("Nueva presentación") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Unidad base: ${baseUnit.plural}")
                    OutlinedTextField(
                        value = presName,
                        onValueChange = { presName = it; localError = null },
                        label = { Text("Nombre (ej. caja, six-pack)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = factorText,
                        onValueChange = { factorText = it; localError = null },
                        label = { Text("Cantidad de ${baseUnit.plural} por unidad") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Permitir fracciones", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = allowsFractions, onCheckedChange = { allowsFractions = it })
                    }
                    if (localError != null) {
                        Text(localError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val f = factorText.trim().replace(",", ".").toDoubleOrNull()
                    if (presName.isBlank() || f == null || f <= 0.0) {
                        localError = "Ingresa nombre y equivalencia mayor a cero."
                    } else {
                        onAddPresentation(product.productId, presName.trim(), f, allowsFractions)
                        showAddPresDialog = false
                    }
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPresDialog = false }) { Text("Cancelar") }
            }
        )
    }

    editingMinStockArea?.let { (areaId, areaName) ->
        val currentMin = product.stocksByArea.firstOrNull { it.areaId == areaId }?.minStockBase
        var minText by remember(areaId) {
            mutableStateOf(currentMin?.let { QuantityFormatter.formatNumber(it) } ?: "")
        }
        AlertDialog(
            onDismissRequest = { editingMinStockArea = null },
            title = { Text("Stock mínimo en $areaName") },
            text = {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it },
                    label = { Text("Mínimo en ${baseUnit.plural} (vacío = sin mínimo)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val v = if (minText.isBlank()) null else minText.trim().replace(",", ".").toDoubleOrNull()
                    onSetAreaMinStock(product.productId, areaId, v)
                    editingMinStockArea = null
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { editingMinStockArea = null }) { Text("Cancelar") }
            }
        )
    }

    adjustingArea?.let { (areaId, areaName) ->
        val currentQty = product.stocksByArea.firstOrNull { it.areaId == areaId }?.quantityBase ?: 0.0
        var newQtyText by remember(areaId) { mutableStateOf(QuantityFormatter.formatNumber(currentQty)) }
        var reason by remember { mutableStateOf("") }
        var localError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { adjustingArea = null },
            title = { Text("Ajuste en $areaName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Existencia actual: ${QuantityFormatter.formatBaseQuantity(currentQty, product.baseUnit)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = newQtyText,
                        onValueChange = { newQtyText = it; localError = null },
                        label = { Text("Nueva existencia (${baseUnit.plural})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it; localError = null },
                        label = { Text("Motivo del ajuste *") },
                        maxLines = 2
                    )
                    if (localError != null) {
                        Text(localError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val q = newQtyText.trim().replace(",", ".").toDoubleOrNull()
                    if (q == null || q < 0.0) {
                        localError = "Cantidad inválida."
                    } else if (reason.isBlank()) {
                        localError = "Indica el motivo del ajuste."
                    } else {
                        onRegisterAdjustment(product.productId, areaId, q, null, reason.trim())
                        adjustingArea = null
                    }
                }) { Text("Registrar ajuste") }
            },
            dismissButton = {
                TextButton(onClick = { adjustingArea = null }) { Text("Cancelar") }
            }
        )
    }

    pendingScanCallback?.let { callback ->
        InlineScannerDialog(
            title = "Escanear código",
            cooldownMs = cooldownMs,
            onScanned = { scanned ->
                callback(scanned)
                pendingScanCallback = null
            },
            onDismiss = { pendingScanCallback = null }
        )
    }
}
