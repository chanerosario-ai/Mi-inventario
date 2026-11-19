package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.AreaEntity
import com.example.domain.AreaId
import com.example.domain.InitialInventoryItemDetail
import com.example.domain.InitialInventorySessionDetail
import com.example.domain.InitialInventoryStatus
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.domain.UnitConverter
import com.example.ui.components.AreaBadge
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.ProductPickerDialog
import com.example.ui.scanner.InlineScannerDialog
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InitialInventoryScreen(
    initialAreaId: Long?,
    areas: List<AreaEntity>,
    products: List<ProductSummary>,
    sessions: List<InitialInventorySessionDetail>,
    cooldownMs: Long,
    onLookupCode: suspend (String) -> ProductSummary?,
    onOpenSession: (Long, String) -> Unit,
    onRecordCount: (sessionId: Long, productId: Long, qty: Double, presId: Long?, onDone: () -> Unit) -> Unit,
    onDeleteCountItem: (Long) -> Unit,
    onCloseSession: (Long) -> Unit,
    onRegisterAdjustment: (productId: Long, areaId: Long, newQty: Double, presId: Long?, reason: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")) }

    var selectedAreaId by remember { mutableLongStateOf(initialAreaId ?: AreaId.ALMACEN_GENERAL.id) }
    val areaSessions = remember(sessions, selectedAreaId) {
        sessions.filter { it.areaId == selectedAreaId }
    }
    val openSession = remember(areaSessions) {
        areaSessions.firstOrNull { it.status == InitialInventoryStatus.ABIERTO }
    }
    val closedSessions = remember(areaSessions) {
        areaSessions.filter { it.status == InitialInventoryStatus.CERRADO }
    }

    var selectedProduct by remember { mutableStateOf<ProductSummary?>(null) }
    var selectedPresId by remember { mutableStateOf<Long?>(null) }
    var countQtyText by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var adjustmentTarget by remember { mutableStateOf<InitialInventoryItemDetail?>(null) }

    LaunchedEffect(selectedProduct, openSession) {
        val prod = selectedProduct
        if (prod != null) {
            if (prod.presentations.none { it.presentationId == selectedPresId }) {
                selectedPresId = prod.presentations.firstOrNull()?.presentationId
            }
            // If product was already counted in this open session, prefill existing count so we update instead of duplicating
            val existing = openSession?.items?.firstOrNull { it.productId == prod.productId }
            if (existing != null) {
                countQtyText = QuantityFormatter.formatNumber(existing.countedQuantityInput)
                val matchedPres = prod.presentations.firstOrNull { it.name == existing.presentationName }
                if (matchedPres != null) selectedPresId = matchedPres.presentationId
            }
        }
    }

    val activePres = remember(selectedProduct, selectedPresId) {
        selectedProduct?.presentations?.firstOrNull { it.presentationId == selectedPresId }
            ?: selectedProduct?.presentations?.firstOrNull()
    }
    val parsedQty = countQtyText.trim().replace(",", ".").toDoubleOrNull()
    val convertedBase = remember(selectedProduct, activePres, parsedQty) {
        if (selectedProduct != null && activePres != null && parsedQty != null) {
            if (parsedQty == 0.0) 0.0
            else UnitConverter.convertToBase(
                quantityInput = parsedQty,
                conversionFactor = activePres.baseUnitsPerPresentation,
                productAllowsDecimals = selectedProduct!!.allowsDecimals,
                isPackPresentation = !activePres.isBase,
                presentationAllowsFractions = activePres.allowsFractions || selectedProduct!!.allowFractionalPresentations
            ).getOrNull()
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventario inicial", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("1. Selecciona el área", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    areas.forEach { area ->
                        FilterChip(
                            selected = selectedAreaId == area.id,
                            onClick = {
                                selectedAreaId = area.id
                                selectedProduct = null
                                countQtyText = ""
                                localError = null
                            },
                            label = { Text(area.name) }
                        )
                    }
                }
            }

            if (openSession == null) {
                item {
                    val areaName = areas.firstOrNull { it.id == selectedAreaId }?.name ?: "Área"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Conteo físico independiente: $areaName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Al abrir el inventario inicial se registrará la fecha y hora de apertura. Podrás escanear productos, revisar y corregir el conteo antes de cerrarlo. No se contabiliza como compra.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { onOpenSession(selectedAreaId, "Conteo físico $areaName") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("open_initial_inventory_btn")
                            ) {
                                Text("Abrir inventario inicial")
                            }
                        }
                    }
                }
            } else {
                // Active Open Session
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AreaBadge(areaId = openSession.areaId, areaName = openSession.areaName)
                                Surface(
                                    color = StatusWarning.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "SESIÓN ABIERTA",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusWarning,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(
                                "Apertura: ${dateFormat.format(Date(openSession.openedAt))}",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Productos contados: ${openSession.items.size}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                // Step 2-7: Scan/Pick + Count + Presentation + Confirm
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Agregar o actualizar conteo físico", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showScanner = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Escanear")
                                }
                                OutlinedButton(
                                    onClick = { showPicker = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Elegir")
                                }
                            }

                            if (selectedProduct != null) {
                                val alreadyCounted = openSession.items.firstOrNull { it.productId == selectedProduct!!.productId }
                                Text(
                                    text = selectedProduct!!.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (alreadyCounted != null) {
                                    Text(
                                        text = "Ya contado en esta sesión (${QuantityFormatter.formatBaseQuantity(alreadyCounted.countedQuantityBase, selectedProduct!!.baseUnit)}). Guardar reemplazará el conteo sin duplicarlo.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusWarning
                                    )
                                }

                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    selectedProduct!!.presentations.forEach { pres ->
                                        FilterChip(
                                            selected = activePres?.presentationId == pres.presentationId,
                                            onClick = { selectedPresId = pres.presentationId },
                                            label = {
                                                val suffix = if (pres.isBase) "" else " (=${QuantityFormatter.formatNumber(pres.baseUnitsPerPresentation)})"
                                                Text("${pres.name}$suffix")
                                            }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = countQtyText,
                                    onValueChange = { countQtyText = it; localError = null },
                                    label = { Text("Cantidad física (${activePres?.name ?: selectedProduct!!.baseUnit})") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("initial_count_qty_input"),
                                    singleLine = true
                                )

                                if (convertedBase != null) {
                                    Text(
                                        text = "Equivale a: ${QuantityFormatter.formatBaseQuantity(convertedBase, selectedProduct!!.baseUnit)}",
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (localError != null) {
                                    Text(localError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }

                                Button(
                                    onClick = {
                                        val q = parsedQty
                                        if (q == null || q < 0.0) {
                                            localError = "Ingresa una cantidad física válida."
                                        } else {
                                            onRecordCount(
                                                openSession.id,
                                                selectedProduct!!.productId,
                                                q,
                                                activePres?.presentationId
                                            ) {
                                                selectedProduct = null
                                                countQtyText = ""
                                                localError = null
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("confirm_initial_count_btn")
                                ) {
                                    Text("Confirmar registro en conteo")
                                }
                            }
                        }
                    }
                }

                // Review list before closing
                item {
                    Text(
                        "Revisión del conteo (${openSession.items.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(openSession.items, key = { it.id }) { item ->
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
                                Text(item.productName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${QuantityFormatter.formatNumber(item.countedQuantityInput)} ${item.presentationName} → ${
                                        QuantityFormatter.formatBaseQuantity(item.countedQuantityBase, item.baseUnit)
                                    }",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row {
                                TextButton(onClick = {
                                    selectedProduct = products.firstOrNull { it.productId == item.productId }
                                }) {
                                    Text("Editar")
                                }
                                IconButton(onClick = { onDeleteCountItem(item.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Quitar del conteo")
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = { showCloseConfirm = true },
                        enabled = openSession.items.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusSuccess),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("close_initial_inventory_btn")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cerrar inventario inicial")
                    }
                }
            }

            // Closed sessions history for this area (with post-close Adjustment action)
            if (closedSessions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Inventarios iniciales cerrados",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(closedSessions, key = { it.id }) { session ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                    Text("Sesión #${session.id} cerrada", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    session.closedAt?.let { dateFormat.format(Date(it)) } ?: "",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                "Apertura: ${dateFormat.format(Date(session.openedAt))} • ${session.items.size} productos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            session.items.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${item.productName}: ${QuantityFormatter.formatBaseQuantity(item.countedQuantityBase, item.baseUnit)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { adjustmentTarget = item }) {
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

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showScanner) {
        InlineScannerDialog(
            title = "Escanear producto para conteo",
            cooldownMs = cooldownMs,
            onScanned = { code ->
                showScanner = false
                scope.launch {
                    val found = onLookupCode(code)
                    if (found != null) {
                        selectedProduct = found
                        localError = null
                    } else {
                        localError = "Código '$code' no registrado en el catálogo."
                    }
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    if (showPicker) {
        ProductPickerDialog(
            products = products,
            onSelect = { prod ->
                selectedProduct = prod
                showPicker = false
                localError = null
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showCloseConfirm && openSession != null) {
        ConfirmationDialog(
            title = "Cerrar inventario inicial",
            message = "Se establecerán las existencias reales de ${openSession.areaName} para ${openSession.items.size} productos. Después del cierre, cualquier corrección quedará registrada como ajuste.",
            confirmText = "Cerrar inventario",
            onConfirm = {
                showCloseConfirm = false
                onCloseSession(openSession.id)
            },
            onDismiss = { showCloseConfirm = false }
        )
    }

    adjustmentTarget?.let { target ->
        val prod = products.firstOrNull { it.productId == target.productId }
        val currentStock = prod?.stocksByArea?.firstOrNull { it.areaId == selectedAreaId }?.quantityBase ?: target.countedQuantityBase
        var newQtyText by remember(target.id) { mutableStateOf(QuantityFormatter.formatNumber(currentStock)) }
        var reason by remember { mutableStateOf("") }
        var adjError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { adjustmentTarget = null },
            title = { Text("Ajuste post-cierre: ${target.productName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Existencia actual: ${QuantityFormatter.formatBaseQuantity(currentStock, target.baseUnit)}")
                    OutlinedTextField(
                        value = newQtyText,
                        onValueChange = { newQtyText = it; adjError = null },
                        label = { Text("Nueva existencia (${target.baseUnit})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it; adjError = null },
                        label = { Text("Motivo de la corrección *") }
                    )
                    if (adjError != null) {
                        Text(adjError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val q = newQtyText.trim().replace(",", ".").toDoubleOrNull()
                    if (q == null || q < 0.0) adjError = "Cantidad inválida."
                    else if (reason.isBlank()) adjError = "El motivo es obligatorio."
                    else {
                        onRegisterAdjustment(target.productId, selectedAreaId, q, null, reason.trim())
                        adjustmentTarget = null
                    }
                }) { Text("Registrar ajuste") }
            },
            dismissButton = {
                TextButton(onClick = { adjustmentTarget = null }) { Text("Cancelar") }
            }
        )
    }
}
