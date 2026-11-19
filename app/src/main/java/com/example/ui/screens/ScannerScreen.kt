package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.ui.components.AreaBadge
import com.example.ui.components.ProductPickerDialog
import com.example.ui.scanner.BarcodeScannerViewport
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.launch

private data class ScannedHistoryEntry(
    val code: String,
    val formatLabel: String,
    val matchedProduct: ProductSummary?,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    products: List<ProductSummary>,
    cooldownMs: Long,
    onLookupCode: suspend (String) -> ProductSummary?,
    onOpenProductDetail: (Long) -> Unit,
    onRegisterNewWithCode: (String) -> Unit,
    onQuickEntry: (Long) -> Unit,
    onQuickExit: (Long) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var continuousMode by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var lastScannedCode by remember { mutableStateOf<String?>(null) }
    var lastFormat by remember { mutableStateOf<String?>(null) }
    var matchedProduct by remember { mutableStateOf<ProductSummary?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val continuousLog = remember { mutableStateListOf<ScannedHistoryEntry>() }

    fun handleCode(raw: String, format: String) {
        val clean = raw.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val found = onLookupCode(clean)
            lastScannedCode = clean
            lastFormat = format
            matchedProduct = found
            if (continuousMode) {
                continuousLog.add(
                    0,
                    ScannedHistoryEntry(clean, format, found, System.currentTimeMillis())
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Escáner de códigos", fontWeight = FontWeight.Bold) },
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
                BarcodeScannerViewport(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    cooldownMs = cooldownMs,
                    continuousMode = continuousMode,
                    onBarcodeDetected = { raw, format -> handleCode(raw, format) }
                )
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Escaneo continuo", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Procesa varios productos seguidos (bloqueo anti-doble lectura: ${cooldownMs / 1000.0}s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = continuousMode,
                            onCheckedChange = { continuousMode = it },
                            modifier = Modifier.testTag("continuous_scan_switch")
                        )
                    }
                }
            }

            // Manual fallback input
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        label = { Text("Ingresar código manualmente") },
                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_barcode_input"),
                        singleLine = true
                    )
                    Button(
                        onClick = { handleCode(manualCode, "Manual") },
                        enabled = manualCode.isNotBlank(),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("manual_barcode_search_btn")
                    ) {
                        Text("Buscar")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Buscar producto por nombre o categoría")
                }
            }

            // Result card
            if (lastScannedCode != null) {
                item {
                    val found = matchedProduct
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (found != null) {
                                StatusSuccess.copy(alpha = 0.08f)
                            } else {
                                StatusWarning.copy(alpha = 0.12f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (found != null) StatusSuccess.copy(alpha = 0.4f) else StatusWarning.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (found != null) "Producto encontrado" else "Código no registrado",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (found != null) StatusSuccess else StatusWarning
                                )
                                Text(
                                    text = "${lastFormat ?: ""} • $lastScannedCode",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            if (found != null) {
                                Text(found.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${found.category} • Total: ${QuantityFormatter.formatBaseQuantity(found.totalStockBase, found.baseUnit)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    found.stocksByArea.forEach { s ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AreaBadge(areaId = s.areaId, areaName = s.areaName)
                                            Text(
                                                QuantityFormatter.formatBaseQuantity(s.quantityBase, found.baseUnit),
                                                fontFamily = JetBrainsMonoFamily,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onOpenProductDetail(found.productId) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Ver ficha")
                                    }
                                    Button(
                                        onClick = { onQuickEntry(found.productId) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Entrada")
                                    }
                                    Button(
                                        onClick = { onQuickExit(found.productId) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Salida")
                                    }
                                }
                            } else {
                                Text(
                                    text = "Ningún producto tiene asignado el código $lastScannedCode.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { onRegisterNewWithCode(lastScannedCode!!) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("register_scanned_product_btn")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Registrar nuevo producto con este código")
                                }
                            }
                        }
                    }
                }
            }

            if (continuousMode && continuousLog.isNotEmpty()) {
                item {
                    Text(
                        "Lecturas recientes (${continuousLog.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(continuousLog) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.matchedProduct != null) {
                                    onOpenProductDetail(item.matchedProduct.productId)
                                } else {
                                    onRegisterNewWithCode(item.code)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = item.matchedProduct?.name ?: "Código nuevo: ${item.code}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "${item.formatLabel} • ${item.code}",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (item.matchedProduct != null) "Ver" else "Registrar",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showPicker) {
        ProductPickerDialog(
            products = products,
            includeInactive = true,
            onSelect = { product ->
                showPicker = false
                matchedProduct = product
                lastScannedCode = product.primaryBarcode ?: product.internalCode
                lastFormat = "Búsqueda"
            },
            onDismiss = { showPicker = false }
        )
    }
}
