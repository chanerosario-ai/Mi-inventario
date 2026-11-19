package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Liquor
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.AreaSummary
import com.example.domain.LowStockAlertItem
import com.example.domain.QuantityFormatter
import com.example.domain.SyncStatusSummary
import com.example.ui.components.AreaBadge
import com.example.ui.components.SyncStatusBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.areaAccentColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    areaSummaries: List<AreaSummary>,
    lowStockAlerts: List<LowStockAlertItem>,
    syncStatus: SyncStatusSummary,
    onNavigateToScan: () -> Unit,
    onNavigateToProducts: (Long?) -> Unit,
    onNavigateToManualAddProduct: () -> Unit,
    onNavigateToEntries: () -> Unit,
    onNavigateToExits: () -> Unit,
    onNavigateToTransfers: () -> Unit,
    onNavigateToHistory: (Long?) -> Unit,
    onNavigateToInitialInventory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectAlertProduct: (Long) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Mi Inventario",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Control local • 4 áreas",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("top_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuración")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection & Sync Status Strip
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SyncStatusBadge(
                        syncStatus = syncStatus,
                        onClick = onNavigateToSettings
                    )
                    Text(
                        text = if (syncStatus.isOnline) "En línea" else "Almacenamiento local",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Visible Manual Product Registration CTA
            item {
                Button(
                    onClick = onNavigateToManualAddProduct,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("add_product_manual_btn"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.AddBusiness, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Agregar producto manualmente",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 4 Area Cards (2x2 grid)
            item {
                Text(
                    text = "Áreas de inventario",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                val rows = areaSummaries.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { summary ->
                                AreaSummaryCard(
                                    summary = summary,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onNavigateToProducts(summary.areaId) }
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Main Quick Actions (8 buttons, big ergonomic touch targets)
            item {
                Text(
                    text = "Operaciones principales",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                val actions = listOf(
                    QuickActionItem("Escanear", "Código / QR", Icons.Default.QrCodeScanner, MaterialTheme.colorScheme.primary, "action_scan", onNavigateToScan),
                    QuickActionItem("Productos", "Catálogo", Icons.Default.Inventory2, MaterialTheme.colorScheme.secondary, "action_products") { onNavigateToProducts(null) },
                    QuickActionItem("Entradas", "Sumar stock", Icons.Default.AddCircle, StatusSuccess, "action_entries", onNavigateToEntries),
                    QuickActionItem("Salidas", "Retirar stock", Icons.Default.RemoveCircle, StatusError, "action_exits", onNavigateToExits),
                    QuickActionItem("Transferencias", "Entre áreas", Icons.AutoMirrored.Filled.CompareArrows, Color(0xFF1D4ED8), "action_transfers", onNavigateToTransfers),
                    QuickActionItem("Historial", "Movimientos", Icons.Default.History, Color(0xFF475569), "action_history") { onNavigateToHistory(null) },
                    QuickActionItem("Inv. inicial", "Conteo físico", Icons.Default.Assignment, Color(0xFFB45309), "action_initial_inventory", onNavigateToInitialInventory),
                    QuickActionItem("Configuración", "PIN, sync y copias", Icons.Default.Settings, Color(0xFF64748B), "action_settings", onNavigateToSettings)
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { action ->
                                QuickActionButton(
                                    item = action,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Low Stock & Out-of-Stock Alerts
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = if (lowStockAlerts.isEmpty()) StatusSuccess else StatusWarning,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Alertas de stock (${lowStockAlerts.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (lowStockAlerts.isEmpty()) {
                item {
                    Surface(
                        color = StatusSuccess.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Sin alertas activas. Todas las existencias configuradas se encuentran sobre su mínimo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusSuccess,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(lowStockAlerts, key = { "${it.areaId}_${it.productId}" }) { alert ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAlertProduct(alert.productId) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (alert.isOutOfStock) {
                                StatusError.copy(alpha = 0.08f)
                            } else {
                                StatusWarning.copy(alpha = 0.10f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (alert.isOutOfStock) StatusError.copy(alpha = 0.3f) else StatusWarning.copy(alpha = 0.35f)
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
                                    text = alert.productName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                AreaBadge(areaId = alert.areaId, areaName = alert.areaName)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (alert.isOutOfStock) "AGOTADO" else "STOCK BAJO",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (alert.isOutOfStock) StatusError else StatusWarning
                                )
                                Text(
                                    text = QuantityFormatter.formatBaseQuantity(alert.currentStockBase, alert.baseUnit),
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                alert.minStockBase?.let { min ->
                                    Text(
                                        text = "Mín: ${QuantityFormatter.formatNumber(min)}",
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

@Composable
private fun AreaSummaryCard(
    summary: AreaSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = areaAccentColor(summary.areaId)
    val icon = when (summary.areaId) {
        1L -> Icons.Default.Warehouse
        2L -> Icons.Default.Storefront
        3L -> Icons.Default.Liquor
        4L -> Icons.Default.LocalBar
        else -> Icons.Default.Inventory2
    }
    val totalAlerts = summary.lowStockAlertsCount + summary.outOfStockCount

    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag("area_card_${summary.areaId}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (totalAlerts > 0) {
                    Surface(
                        color = StatusError.copy(alpha = 0.14f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "$totalAlerts !",
                            color = StatusError,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = QuantityFormatter.formatNumber(summary.totalUnitsAvailable),
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = "Existencias disponibles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${summary.registeredProductsCount} prod.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (totalAlerts > 0) "$totalAlerts alertas" else "Al día",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (totalAlerts > 0) StatusWarning else StatusSuccess,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private data class QuickActionItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val testTag: String,
    val onClick: () -> Unit
)

@Composable
private fun QuickActionButton(
    item: QuickActionItem,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = item.onClick)
            .testTag(item.testTag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(item.tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = item.tint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
