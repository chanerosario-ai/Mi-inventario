package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AreaEntity
import com.example.domain.MovementRecord
import com.example.domain.MovementType
import com.example.domain.QuantityFormatter
import com.example.ui.components.AreaBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class DateRangeFilter(val label: String) {
    ALL("Todo"),
    TODAY("Hoy"),
    LAST_7_DAYS("7 días"),
    LAST_30_DAYS("30 días")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    initialAreaId: Long?,
    initialProductId: Long?,
    areas: List<AreaEntity>,
    movements: List<MovementRecord>,
    onBack: () -> Unit
) {
    val dateTimeFormatter = remember { SimpleDateFormat("dd/MM/yyyy • HH:mm:ss", Locale("es", "ES")) }

    var productQuery by remember { mutableStateOf("") }
    var selectedAreaId by remember { mutableStateOf(initialAreaId) }
    var selectedType by remember { mutableStateOf<MovementType?>(null) }
    var selectedDateRange by remember { mutableStateOf(DateRangeFilter.ALL) }

    val filtered = remember(movements, productQuery, selectedAreaId, selectedType, selectedDateRange, initialProductId) {
        val q = productQuery.trim().lowercase()
        val now = System.currentTimeMillis()
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val minTimestamp = when (selectedDateRange) {
            DateRangeFilter.ALL -> 0L
            DateRangeFilter.TODAY -> startOfToday
            DateRangeFilter.LAST_7_DAYS -> now - 7L * 24 * 60 * 60 * 1000
            DateRangeFilter.LAST_30_DAYS -> now - 30L * 24 * 60 * 60 * 1000
        }

        movements.filter { m ->
            val matchesProductId = initialProductId == null || m.productId == initialProductId
            val matchesQuery = q.isEmpty() ||
                m.productName.lowercase().contains(q) ||
                m.notes.lowercase().contains(q)
            val matchesArea = selectedAreaId == null ||
                m.sourceAreaId == selectedAreaId ||
                m.destinationAreaId == selectedAreaId
            val matchesType = selectedType == null || m.movementType == selectedType
            val matchesDate = m.timestamp >= minTimestamp
            matchesProductId && matchesQuery && matchesArea && matchesType && matchesDate
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial (${filtered.size})", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = productQuery,
                onValueChange = { productQuery = it },
                placeholder = { Text("Filtrar por producto o nota...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Area filters
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = selectedAreaId == null,
                    onClick = { selectedAreaId = null },
                    label = { Text("Todas áreas") }
                )
                areas.forEach { area ->
                    FilterChip(
                        selected = selectedAreaId == area.id,
                        onClick = { selectedAreaId = if (selectedAreaId == area.id) null else area.id },
                        label = { Text(area.name) }
                    )
                }
            }

            // Movement type + Date filters
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { selectedType = null },
                    label = { Text("Todos tipos") }
                )
                MovementType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = if (selectedType == type) null else type },
                        label = { Text(type.label) }
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DateRangeFilter.entries.forEach { range ->
                    FilterChip(
                        selected = selectedDateRange == range,
                        onClick = { selectedDateRange = range },
                        label = { Text(range.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No hay movimientos para los filtros seleccionados.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { m ->
                        val typeColor = when (m.movementType) {
                            MovementType.ENTRADA -> StatusSuccess
                            MovementType.SALIDA -> StatusError
                            MovementType.TRANSFERENCIA -> MaterialTheme.colorScheme.primary
                            MovementType.INVENTARIO_INICIAL -> StatusWarning
                            MovementType.AJUSTE -> MaterialTheme.colorScheme.secondary
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = typeColor.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = m.movementType.label.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = typeColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Text(
                                        text = dateTimeFormatter.format(Date(m.timestamp)),
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = m.productName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${QuantityFormatter.formatNumber(m.quantityInput)} ${m.presentationName} (${
                                            QuantityFormatter.formatBaseQuantity(m.quantityBase, m.baseUnit)
                                        })",
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = typeColor
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (m.sourceAreaId != null && m.sourceAreaName != null) {
                                        AreaBadge(areaId = m.sourceAreaId, areaName = m.sourceAreaName)
                                    }
                                    if (m.sourceAreaId != null && m.destinationAreaId != null) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "hacia",
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                    if (m.destinationAreaId != null && m.destinationAreaName != null) {
                                        AreaBadge(areaId = m.destinationAreaId, areaName = m.destinationAreaName)
                                    }
                                }

                                if (m.notes.isNotBlank()) {
                                    Text(
                                        text = m.notes,
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
