package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AreaEntity
import com.example.data.repository.InventoryRepository
import com.example.domain.AreaId
import com.example.domain.BaseUnit
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.ui.components.AreaBadge
import com.example.ui.components.ProductFormDialog
import com.example.ui.scanner.InlineScannerDialog
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    products: List<ProductSummary>,
    areas: List<AreaEntity>,
    initialFilterAreaId: Long? = null,
    prefillBarcode: String?,
    openManualForm: Boolean = false,
    cooldownMs: Long,
    onCheckCodeConflicts: suspend (List<String>, Long?) -> List<Pair<String, String>>,
    onRegisterProduct: (
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
    onSelectProduct: (Long) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedAreaId by remember { mutableStateOf(initialFilterAreaId) }
    var showInactive by remember { mutableStateOf(false) }
    var showCreateForm by remember { mutableStateOf(openManualForm || !prefillBarcode.isNullOrBlank()) }
    var pendingScanCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    LaunchedEffect(prefillBarcode, openManualForm) {
        if (!prefillBarcode.isNullOrBlank() || openManualForm) {
            showCreateForm = true
        }
    }

    val filtered = remember(products, searchQuery, showInactive, selectedAreaId) {
        val q = searchQuery.trim().lowercase()
        products.filter { p ->
            val matchesActive = showInactive || p.isActive
            val matchesArea = selectedAreaId == null ||
                p.preferredAreaId == selectedAreaId ||
                p.stocksByArea.any { it.areaId == selectedAreaId && (it.quantityBase > 0.0001 || it.minStockBase != null) }
            val matchesQuery = q.isEmpty() ||
                p.name.lowercase().contains(q) ||
                p.internalCode.lowercase().contains(q) ||
                p.category.lowercase().contains(q) ||
                p.allCodes.any { it.lowercase().contains(q) }
            matchesActive && matchesArea && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Productos (${filtered.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        pendingScanCallback = { code -> searchQuery = code }
                    }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Buscar por escáner")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateForm = true },
                modifier = Modifier.testTag("add_product_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo producto")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Button(
                onClick = { showCreateForm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("products_add_manual_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddBusiness, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Agregar producto manualmente", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("products_search_input"),
                placeholder = { Text("Buscar por nombre, código o categoría...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAreaId == null,
                    onClick = { selectedAreaId = null },
                    label = { Text("Todas las áreas") }
                )
                areas.sortedBy { it.sortOrder }.forEach { area ->
                    FilterChip(
                        selected = selectedAreaId == area.id,
                        onClick = { selectedAreaId = area.id },
                        label = { Text(area.name) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showInactive,
                    onClick = { showInactive = false },
                    label = { Text("Solo activos") }
                )
                FilterChip(
                    selected = showInactive,
                    onClick = { showInactive = true },
                    label = { Text("Incluir inactivos") }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = if (products.isEmpty()) "Aún no hay productos registrados." else "Sin coincidencias para la búsqueda.",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Toca 'Agregar producto manualmente' o '+' para registrar un producto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filtered, key = { it.productId }) { product ->
                        val areaToShow = selectedAreaId?.let { aId ->
                            product.stocksByArea.firstOrNull { it.areaId == aId }
                        }
                        val stockDisplay = areaToShow?.quantityBase ?: product.totalStockBase
                        val hasAlert = product.stocksByArea.any { it.isLowStock }
                        val largestPres = product.presentations.maxByOrNull { it.baseUnitsPerPresentation }
                        val prefArea = areas.firstOrNull { it.id == product.preferredAreaId }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProduct(product.productId) }
                                .testTag("product_card_${product.productId}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (product.isActive) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (hasAlert && product.isActive) StatusWarning.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = product.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!product.isActive) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = StatusError.copy(alpha = 0.14f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        "INACTIVO",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = StatusError,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = buildString {
                                                append(product.category)
                                                append(" • ")
                                                append(product.internalCode)
                                                product.primaryBarcode?.let { append(" • $it") }
                                            },
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (prefArea != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            AreaBadge(areaId = prefArea.id, areaName = prefArea.name)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = QuantityFormatter.formatReadableWithPresentation(
                                                quantityBase = stockDisplay,
                                                baseUnitKey = product.baseUnit,
                                                largestPresentationName = largestPres?.takeIf { !it.isBase }?.name,
                                                largestFactor = largestPres?.takeIf { !it.isBase }?.baseUnitsPerPresentation
                                            ),
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = if (selectedAreaId != null) "En área" else "${product.presentations.size} pres.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

    if (showCreateForm) {
        ProductFormDialog(
            initialProduct = null,
            prefillCode = prefillBarcode,
            areas = areas,
            initialPreferredAreaId = selectedAreaId ?: AreaId.ALMACEN_GENERAL.id,
            onCheckCodeConflicts = onCheckCodeConflicts,
            onRequestBarcodeScan = { callback -> pendingScanCallback = callback },
            onSaveNew = { name, primary, additional, desc, cat, unit, dec, frac, minStock, extras, overrideDup, customInternal, prefAreaId, initQty ->
                onRegisterProduct(
                    name, primary, additional, desc, cat, unit, dec, frac, minStock, extras, overrideDup, customInternal, prefAreaId, initQty
                )
                showCreateForm = false
            },
            onUpdateExisting = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            onDismiss = { showCreateForm = false }
        )
    }

    pendingScanCallback?.let { callback ->
        InlineScannerDialog(
            title = "Escanear código de producto",
            cooldownMs = cooldownMs,
            onScanned = { scanned ->
                callback(scanned)
                pendingScanCallback = null
            },
            onDismiss = { pendingScanCallback = null }
        )
    }
}
