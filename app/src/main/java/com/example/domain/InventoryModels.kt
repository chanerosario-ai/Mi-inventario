package com.example.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

enum class AreaId(val id: Long, val code: String, val displayName: String, val colorHex: Long) {
    ALMACEN_GENERAL(1L, "ALMACEN_GENERAL", "Almacén general", 0xFF1D4ED8),
    DESPENSA(2L, "DESPENSA", "Despensa", 0xFF047857),
    ALMACEN_LICORES(3L, "ALMACEN_LICORES", "Almacén de licores", 0xFF9F1239),
    BAR(4L, "BAR", "Bar", 0xFFB45309);

    companion object {
        fun fromId(id: Long?): AreaId? = entries.firstOrNull { it.id == id }
    }
}

enum class BaseUnit(
    val key: String,
    val singular: String,
    val plural: String,
    val defaultAllowsDecimals: Boolean,
    val dimension: UnitDimension
) {
    BOTELLA("botella", "botella", "botellas", false, UnitDimension.COUNT),
    UNIDAD("unidad", "unidad", "unidades", false, UnitDimension.COUNT),
    KILOGRAMO("kilogramo", "kilogramo", "kilogramos", true, UnitDimension.MASS),
    GRAMO("gramo", "gramo", "gramos", true, UnitDimension.MASS),
    LITRO("litro", "litro", "litros", true, UnitDimension.VOLUME),
    MILILITRO("mililitro", "mililitro", "mililitros", true, UnitDimension.VOLUME);

    fun labelFor(quantity: Double): String = if (abs(quantity - 1.0) < 0.0001) singular else plural

    companion object {
        fun fromKey(key: String): BaseUnit =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: UNIDAD
    }
}

enum class UnitDimension {
    COUNT, MASS, VOLUME
}

enum class MovementType(val key: String, val label: String) {
    ENTRADA("ENTRADA", "Entrada"),
    SALIDA("SALIDA", "Salida"),
    TRANSFERENCIA("TRANSFERENCIA", "Transferencia"),
    INVENTARIO_INICIAL("INVENTARIO_INICIAL", "Inventario inicial"),
    AJUSTE("AJUSTE", "Ajuste");

    companion object {
        fun fromKey(key: String): MovementType =
            entries.firstOrNull { it.key == key } ?: AJUSTE
    }
}

enum class InitialInventoryStatus(val key: String, val label: String) {
    ABIERTO("ABIERTO", "Abierto"),
    CERRADO("CERRADO", "Cerrado")
}

object TransferRules {
    /**
     * Authorized transfer routes in v1:
     * - Almacén general (1) -> Despensa (2)
     * - Almacén de licores (3) -> Bar (4)
     */
    val authorizedRoutes: List<Pair<Long, Long>> = listOf(
        AreaId.ALMACEN_GENERAL.id to AreaId.DESPENSA.id,
        AreaId.ALMACEN_LICORES.id to AreaId.BAR.id
    )

    fun isAuthorized(sourceAreaId: Long, destinationAreaId: Long): Boolean =
        authorizedRoutes.any { it.first == sourceAreaId && it.second == destinationAreaId }

    fun authorizedDestinationFor(sourceAreaId: Long): Long? =
        authorizedRoutes.firstOrNull { it.first == sourceAreaId }?.second
}

object QuantityFormatter {
    private val symbols = DecimalFormatSymbols(Locale("es", "ES")).apply {
        decimalSeparator = '.'
        groupingSeparator = ','
    }
    private val decimalFormat = DecimalFormat("#,##0.###", symbols)

    fun formatNumber(value: Double): String {
        val clean = if (abs(value) < 1e-6) 0.0 else value
        return decimalFormat.format(clean)
    }

    fun formatBaseQuantity(quantityBase: Double, baseUnitKey: String): String {
        val unit = BaseUnit.fromKey(baseUnitKey)
        return "${formatNumber(quantityBase)} ${unit.labelFor(quantityBase)}"
    }

    fun formatReadableWithPresentation(
        quantityBase: Double,
        baseUnitKey: String,
        largestPresentationName: String?,
        largestFactor: Double?
    ): String {
        val baseFormatted = formatBaseQuantity(quantityBase, baseUnitKey)
        if (largestPresentationName.isNullOrBlank() || largestFactor == null || largestFactor <= 1.0 || quantityBase < largestFactor) {
            return baseFormatted
        }
        val fullPacks = floor((quantityBase + 1e-6) / largestFactor).toLong()
        val remainder = quantityBase - (fullPacks * largestFactor)
        val unit = BaseUnit.fromKey(baseUnitKey)
        return if (fullPacks > 0) {
            val packLabel = if (fullPacks == 1L) largestPresentationName else pluralizePresentation(largestPresentationName)
            if (remainder > 0.0005) {
                "$baseFormatted ($fullPacks $packLabel + ${formatNumber(remainder)} ${unit.labelFor(remainder)})"
            } else {
                "$baseFormatted ($fullPacks $packLabel)"
            }
        } else {
            baseFormatted
        }
    }

    private fun pluralizePresentation(name: String): String {
        val lower = name.trim()
        return when {
            lower.endsWith("s", ignoreCase = true) -> lower
            lower.endsWith("z", ignoreCase = true) -> lower.dropLast(1) + "ces"
            lower.endsWith("a", ignoreCase = true) ||
                lower.endsWith("e", ignoreCase = true) ||
                lower.endsWith("i", ignoreCase = true) ||
                lower.endsWith("o", ignoreCase = true) ||
                lower.endsWith("u", ignoreCase = true) -> "${lower}s"
            else -> "${lower}es"
        }
    }
}

object UnitConverter {
    private const val EPSILON = 1e-6

    fun isWholeNumber(value: Double): Boolean = abs(value - kotlin.math.round(value)) < EPSILON

    fun validateInputQuantity(
        quantityInput: Double,
        allowsDecimals: Boolean,
        isPackPresentation: Boolean,
        allowsFractionalPresentation: Boolean
    ): Result<Double> {
        if (quantityInput.isNaN() || quantityInput.isInfinite() || quantityInput <= 0.0) {
            return Result.failure(InventoryValidationException("La cantidad debe ser mayor que cero."))
        }
        if (isPackPresentation && !allowsFractionalPresentation && !isWholeNumber(quantityInput)) {
            return Result.failure(
                InventoryValidationException(
                    "No se permiten fracciones de esta presentación. Activa fracciones para este producto si deseas registrar decimales."
                )
            )
        }
        if (!isPackPresentation && !allowsDecimals && !isWholeNumber(quantityInput)) {
            return Result.failure(
                InventoryValidationException(
                    "La unidad base de este producto no admite cantidades decimales."
                )
            )
        }
        return Result.success(quantityInput)
    }

    fun convertToBase(
        quantityInput: Double,
        conversionFactor: Double,
        productAllowsDecimals: Boolean,
        isPackPresentation: Boolean,
        presentationAllowsFractions: Boolean
    ): Result<Double> {
        if (conversionFactor.isNaN() || conversionFactor.isInfinite() || conversionFactor <= 0.0) {
            return Result.failure(InventoryValidationException("La equivalencia de conversión debe ser mayor que cero."))
        }
        val validInput = validateInputQuantity(
            quantityInput = quantityInput,
            allowsDecimals = productAllowsDecimals,
            isPackPresentation = isPackPresentation,
            allowsFractionalPresentation = presentationAllowsFractions
        ).getOrElse { return Result.failure(it) }

        val rawBase = validInput * conversionFactor
        val roundedBase = kotlin.math.round(rawBase * 10000.0) / 10000.0
        if (!productAllowsDecimals && !isWholeNumber(roundedBase)) {
            return Result.failure(
                InventoryValidationException(
                    "La conversión produce una cantidad decimal (${QuantityFormatter.formatNumber(roundedBase)}), pero el producto solo admite unidades enteras."
                )
            )
        }
        return Result.success(roundedBase)
    }

    fun validateEquivalence(
        presentationName: String,
        baseUnit: BaseUnit,
        baseUnitsPerPresentation: Double
    ): Result<Double> {
        val clean = presentationName.trim()
        if (clean.isEmpty()) {
            return Result.failure(InventoryValidationException("El nombre de la presentación es obligatorio."))
        }
        if (clean.equals(baseUnit.singular, ignoreCase = true) || clean.equals(baseUnit.plural, ignoreCase = true)) {
            return Result.failure(
                InventoryValidationException("La presentación no puede tener el mismo nombre que la unidad base.")
            )
        }
        if (baseUnitsPerPresentation.isNaN() || baseUnitsPerPresentation.isInfinite() || baseUnitsPerPresentation <= 0.0) {
            return Result.failure(InventoryValidationException("La equivalencia debe ser mayor que cero."))
        }
        if (abs(baseUnitsPerPresentation - 1.0) < EPSILON && clean.lowercase() !in listOf("paquete", "unidad")) {
            // Allow 1:1 only if intentional, still check incompatible names across physical dimensions
        }
        val lower = clean.lowercase()
        val incompatible = when (baseUnit.dimension) {
            UnitDimension.COUNT -> lower in listOf("litro", "litros", "mililitro", "mililitros", "kilogramo", "kilogramos", "gramo", "gramos")
            UnitDimension.MASS -> lower in listOf("litro", "litros", "mililitro", "mililitros", "botella", "botellas")
            UnitDimension.VOLUME -> lower in listOf("kilogramo", "kilogramos", "gramo", "gramos")
        }
        if (incompatible) {
            return Result.failure(
                InventoryValidationException("Equivalencia incompatible entre '$clean' y '${baseUnit.plural}'.")
            )
        }
        return Result.success(baseUnitsPerPresentation)
    }
}

open class InventoryException(message: String) : IllegalStateException(message)
class InventoryValidationException(message: String) : InventoryException(message)
class InsufficientStockException(message: String) : InventoryException(message)
class DuplicateCodeException(val existingProductName: String, message: String) : InventoryException(message)
class DuplicateMovementException(message: String) : InventoryException(message)
class UnauthorizedTransferException(message: String) : InventoryException(message)

data class AreaSummary(
    val areaId: Long,
    val code: String,
    val name: String,
    val description: String,
    val colorHex: Long,
    val registeredProductsCount: Int,
    val totalUnitsAvailable: Double,
    val lowStockAlertsCount: Int,
    val outOfStockCount: Int
)

data class PresentationOption(
    val presentationId: Long,
    val name: String,
    val isBase: Boolean,
    val allowsFractions: Boolean,
    val baseUnitsPerPresentation: Double
)

data class StockInArea(
    val areaId: Long,
    val areaName: String,
    val areaColorHex: Long,
    val quantityBase: Double,
    val minStockBase: Double?
) {
    val isOutOfStock: Boolean get() = quantityBase <= 0.0001
    val isLowStock: Boolean get() = minStockBase != null && quantityBase <= minStockBase + 0.0001
}

data class ProductSummary(
    val productId: Long,
    val internalCode: String,
    val name: String,
    val description: String,
    val category: String,
    val baseUnit: String,
    val allowsDecimals: Boolean,
    val allowFractionalPresentations: Boolean,
    val defaultMinStock: Double?,
    val preferredAreaId: Long = AreaId.ALMACEN_GENERAL.id,
    val isActive: Boolean,
    val primaryBarcode: String?,
    val allCodes: List<String>,
    val presentations: List<PresentationOption>,
    val stocksByArea: List<StockInArea>
) {
    val totalStockBase: Double get() = stocksByArea.sumOf { it.quantityBase }
}

enum class SyncVisualStatus(val label: String) {
    SIN_CONEXION("Sin conexión"),
    PENDIENTE("Pendiente de sincronizar"),
    SINCRONIZANDO("Sincronizando"),
    SINCRONIZADO("Sincronizado"),
    CONFLICTO("Conflicto por revisar")
}

data class SyncStatusSummary(
    val visualStatus: SyncVisualStatus = SyncVisualStatus.SINCRONIZADO,
    val isOnline: Boolean = true,
    val pendingCount: Int = 0,
    val openConflictsCount: Int = 0,
    val syncedCount: Int = 0,
    val lastSyncAt: Long? = null,
    val lastSyncMessage: String? = null,
    val remoteUrlConfigured: Boolean = false
)

data class SyncConflictDetail(
    val id: Long,
    val queueItemId: Long,
    val entityType: String,
    val entityKey: String,
    val localPayloadJson: String,
    val remotePayloadJson: String,
    val localTimestamp: Long,
    val remoteTimestamp: Long,
    val reason: String,
    val createdAt: Long
)

data class LowStockAlertItem(
    val productId: Long,
    val productName: String,
    val baseUnit: String,
    val areaId: Long,
    val areaName: String,
    val areaColorHex: Long,
    val currentStockBase: Double,
    val minStockBase: Double?,
    val isOutOfStock: Boolean
)

data class MovementRecord(
    val id: Long,
    val productId: Long,
    val productName: String,
    val baseUnit: String,
    val movementType: MovementType,
    val quantityInput: Double,
    val presentationName: String,
    val conversionFactor: Double,
    val quantityBase: Double,
    val sourceAreaId: Long?,
    val sourceAreaName: String?,
    val destinationAreaId: Long?,
    val destinationAreaName: String?,
    val notes: String,
    val timestamp: Long
)

data class InitialInventoryItemDetail(
    val id: Long,
    val initialInventoryId: Long,
    val productId: Long,
    val productName: String,
    val primaryCode: String?,
    val baseUnit: String,
    val countedQuantityInput: Double,
    val presentationName: String,
    val conversionFactor: Double,
    val countedQuantityBase: Double,
    val updatedAt: Long
)

data class InitialInventorySessionDetail(
    val id: Long,
    val areaId: Long,
    val areaName: String,
    val areaColorHex: Long,
    val openedAt: Long,
    val closedAt: Long?,
    val status: InitialInventoryStatus,
    val notes: String,
    val items: List<InitialInventoryItemDetail>
)
