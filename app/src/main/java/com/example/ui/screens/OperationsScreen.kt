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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.domain.TransferRules
import com.example.domain.UnitConverter
import com.example.ui.components.AreaBadge
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.ProductPickerDialog
import com.example.ui.scanner.InlineScannerDialog
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OperationsScreen(
    initialTab: String,
    preselectedProductId: Long?,
    preselectedAreaId: Long?,
    areas: List<AreaEntity>,
    products: List<ProductSummary>,
    cooldownMs: Long,
    onLookupCode: suspend (String) -> ProductSummary?,
    onRegisterEntry: (productId: Long, areaId: Long, qty: Double, presId: Long?, notes: String, onDone: () -> Unit) -> Unit,
    onRegisterExit: (productId: Long, areaId: Long, qty: Double, presId: Long?, notes: String, onDone: () -> Unit) -> Unit,
    onRegisterTransfer: (productId: Long, srcAreaId: Long, dstAreaId: Long, qty: Double, presId: Long?, notes: String, onDone: () -> Unit) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val tabs = listOf("Entradas", "Salidas", "Transferencias")
    var selectedTab by remember {
        mutableIntStateOf(
            when (initialTab) {
                "SALIDA" -> 1
                "TRANSFERENCIA" -> 2
                else -> 0
            }
        )
    }

    var selectedProductId by remember { mutableStateOf(preselectedProductId) }
    val selectedProduct = remember(products, selectedProductId) {
        products.firstOrNull { it.productId == selectedProductId }
    }

    var selectedAreaId by remember { mutableLongStateOf(preselectedAreaId ?: AreaId.ALMACEN_GENERAL.id) }
    var selectedTransferRouteIdx by remember { mutableIntStateOf(0) } // 0: General->Despensa, 1: Licores->Bar
    var selectedPresentationId by remember { mutableStateOf<Long?>(null) }
    var quantityText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    var showPicker by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedProduct) {
        if (selectedProduct != null &&
            selectedProduct.presentations.none { it.presentationId == selectedPresentationId }
        ) {
            selectedPresentationId = selectedProduct.presentations.firstOrNull()?.presentationId
        }
    }

    val selectedPresentation = remember(selectedProduct, selectedPresentationId) {
        selectedProduct?.presentations?.firstOrNull { it.presentationId == selectedPresentationId }
            ?: selectedProduct?.presentations?.firstOrNull()
    }

    val transferRoutes = listOf(
        Pair(AreaId.ALMACEN_GENERAL, AreaId.DESPENSA),
        Pair(AreaId.ALMACEN_LICORES, AreaId.BAR)
    )
    val activeTransferSourceId = transferRoutes[selectedTransferRouteIdx].first.id
    val activeTransferDestId = transferRoutes[selectedTransferRouteIdx].second.id

    val effectiveSourceAreaId = if (selectedTab == 2) activeTransferSourceId else selectedAreaId
    val availableInSource = selectedProduct?.stocksByArea
        ?.firstOrNull { it.areaId == effectiveSourceAreaId }
        ?.quantityBase ?: 0.0

    val parsedQty = quantityText.trim().replace(",", ".").toDoubleOrNull()
    val conversionPreview = remember(selectedProduct, selectedPresentation, parsedQty) {
        if (selectedProduct != null && selectedPresentation != null && parsedQty != null) {
            UnitConverter.convertToBase(
                quantityInput = parsedQty,
                conversionFactor = selectedPresentation.baseUnitsPerPresentation,
                productAllowsDecimals = selectedProduct.allowsDecimals,
                isPackPresentation = !selectedPresentation.isBase,
                presentationAllowsFractions = selectedPresentation.allowsFractions || selectedProduct.allowFractionalPresentations
            )
        } else null
    }
    val convertedBaseQty = conversionPreview?.getOrNull()
    val exceedsAvailable = (selectedTab == 1 || selectedTab == 2) &&
        convertedBaseQty != null &&
        convertedBaseQty > availableInSource + 1e-6

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Movimientos de inventario", fontWeight = FontWeight.Bold) },
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
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            localError = null
                        },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("operation_tab_$index")
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Step 1: Product selection via Scanner or Manual Picker
                Text("1. Producto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showScanner = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Escanear")
                    }
                    OutlinedButton(
                        onClick = { showPicker = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("pick_product_btn")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buscar")
                    }
                }

                if (selectedProduct != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(selectedProduct.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Unidad base: ${selectedProduct.baseUnit} • Código: ${selectedProduct.primaryBarcode ?: selectedProduct.internalCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Disponible en área seleccionada: ${
                                    QuantityFormatter.formatBaseQuantity(availableInSource, selectedProduct.baseUnit)
                                }",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (availableInSource <= 0.0 && selectedTab != 0) StatusError else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Step 2: Area or Authorized Transfer Route
                if (selectedTab == 2) {
                    Text("2. Ruta de transferencia autorizada", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        transferRoutes.forEachIndexed { idx, (src, dst) ->
                            val selected = selectedTransferRouteIdx == idx
                            Surface(
                                onClick = { selectedTransferRouteIdx = idx },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("transfer_route_$idx")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    AreaBadge(areaId = src.id, areaName = src.displayName)
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "hacia")
                                    AreaBadge(areaId = dst.id, areaName = dst.displayName)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (selectedTab == 0) "2. Área de destino" else "2. Área de origen",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        areas.forEach { area ->
                            FilterChip(
                                selected = selectedAreaId == area.id,
                                onClick = { selectedAreaId = area.id },
                                label = { Text(area.name) },
                                modifier = Modifier.testTag("area_chip_${area.id}")
                            )
                        }
                    }
                }

                // Step 3: Presentation & Quantity
                if (selectedProduct != null) {
                    Text("3. Presentación y cantidad", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedProduct.presentations.forEach { pres ->
                            FilterChip(
                                selected = selectedPresentation?.presentationId == pres.presentationId,
                                onClick = {
                                    selectedPresentationId = pres.presentationId
                                    localError = null
                                },
                                label = {
                                    val eq = if (pres.isBase) "" else " (=${QuantityFormatter.formatNumber(pres.baseUnitsPerPresentation)})"
                                    Text("${pres.name}$eq")
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it; localError = null },
                        label = {
                            Text("Cantidad en ${selectedPresentation?.name ?: selectedProduct.baseUnit}")
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (selectedProduct.allowsDecimals || selectedProduct.allowFractionalPresentations) {
                                KeyboardType.Decimal
                            } else {
                                KeyboardType.Number
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("operation_qty_input"),
                        singleLine = true
                    )

                    if (convertedBaseQty != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Equivalencia total: ${
                                    QuantityFormatter.formatBaseQuantity(convertedBaseQty, selectedProduct.baseUnit)
                                }",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else if (conversionPreview?.isFailure == true && quantityText.isNotBlank()) {
                        Text(
                            text = conversionPreview.exceptionOrNull()?.message ?: "Cantidad inválida",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (exceedsAvailable) {
                        Surface(
                            color = StatusError.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "La cantidad solicitada (${QuantityFormatter.formatNumber(convertedBaseQty!!)}) supera las existencias disponibles (${QuantityFormatter.formatNumber(availableInSource)}). No se permiten saldos negativos.",
                                    color = StatusError,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Nota o referencia opcional") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (localError != null) {
                    Text(
                        text = localError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                val actionTitle = when (selectedTab) {
                    0 -> "Confirmar entrada"
                    1 -> "Confirmar salida"
                    else -> "Confirmar transferencia"
                }
                val actionColor = when (selectedTab) {
                    0 -> StatusSuccess
                    1 -> StatusError
                    else -> MaterialTheme.colorScheme.primary
                }

                Button(
                    onClick = {
                        if (selectedProduct == null) {
                            localError = "Selecciona un producto primero."
                        } else if (parsedQty == null || parsedQty <= 0.0) {
                            localError = "Ingresa una cantidad válida mayor que cero."
                        } else if (conversionPreview?.isFailure == true) {
                            localError = conversionPreview.exceptionOrNull()?.message
                        } else if (exceedsAvailable) {
                            localError = "Existencias insuficientes en el área de origen."
                        } else if (selectedTab == 2 && !TransferRules.isAuthorized(activeTransferSourceId, activeTransferDestId)) {
                            localError = "Ruta de transferencia no autorizada."
                        } else {
                            showConfirmation = true
                        }
                    },
                    enabled = selectedProduct != null && !exceedsAvailable,
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("operation_submit_btn")
                ) {
                    Text(actionTitle, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (showPicker) {
        ProductPickerDialog(
            products = products,
            includeInactive = false,
            onSelect = { prod ->
                selectedProductId = prod.productId
                showPicker = false
                localError = null
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showScanner) {
        InlineScannerDialog(
            title = "Escanear producto",
            cooldownMs = cooldownMs,
            onScanned = { code ->
                showScanner = false
                scope.launch {
                    val found = onLookupCode(code)
                    if (found != null) {
                        selectedProductId = found.productId
                        localError = null
                    } else {
                        localError = "Código '$code' no encontrado. Regístralo primero en Productos."
                    }
                }
            },
            onDismiss = { showScanner = false }
        )
    }

    if (showConfirmation && selectedProduct != null && parsedQty != null && convertedBaseQty != null) {
        val summaryText = when (selectedTab) {
            0 -> {
                val areaName = areas.firstOrNull { it.id == selectedAreaId }?.name ?: ""
                "Sumar ${QuantityFormatter.formatNumber(parsedQty)} ${selectedPresentation?.name ?: ""} (${QuantityFormatter.formatBaseQuantity(convertedBaseQty, selectedProduct.baseUnit)}) de '${selectedProduct.name}' en $areaName."
            }
            1 -> {
                val areaName = areas.firstOrNull { it.id == selectedAreaId }?.name ?: ""
                "Retirar ${QuantityFormatter.formatNumber(parsedQty)} ${selectedPresentation?.name ?: ""} (${QuantityFormatter.formatBaseQuantity(convertedBaseQty, selectedProduct.baseUnit)}) de '${selectedProduct.name}' desde $areaName."
            }
            else -> {
                val srcName = AreaId.fromId(activeTransferSourceId)?.displayName ?: ""
                val dstName = AreaId.fromId(activeTransferDestId)?.displayName ?: ""
                "Transferir ${QuantityFormatter.formatBaseQuantity(convertedBaseQty, selectedProduct.baseUnit)} de '${selectedProduct.name}' desde $srcName hacia $dstName."
            }
        }

        ConfirmationDialog(
            title = "Confirmar operación",
            message = summaryText,
            confirmText = "Confirmar",
            isDestructive = selectedTab == 1,
            onConfirm = {
                showConfirmation = false
                val presId = selectedPresentation?.presentationId
                val reset = {
                    quantityText = ""
                    notes = ""
                    localError = null
                }
                when (selectedTab) {
                    0 -> onRegisterEntry(selectedProduct.productId, selectedAreaId, parsedQty, presId, notes, reset)
                    1 -> onRegisterExit(selectedProduct.productId, selectedAreaId, parsedQty, presId, notes, reset)
                    else -> onRegisterTransfer(
                        selectedProduct.productId,
                        activeTransferSourceId,
                        activeTransferDestId,
                        parsedQty,
                        presId,
                        notes,
                        reset
                    )
                }
            },
            onDismiss = { showConfirmation = false }
        )
    }
}
