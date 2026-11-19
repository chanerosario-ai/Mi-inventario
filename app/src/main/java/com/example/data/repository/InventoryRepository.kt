package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AdjustmentEntity
import com.example.data.local.AppConfigEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AreaEntity
import com.example.data.local.AreaStockEntity
import com.example.data.local.EquivalenceEntity
import com.example.data.local.InitialInventoryEntity
import com.example.data.local.InitialInventoryItemEntity
import com.example.data.local.MovementEntity
import com.example.data.local.PresentationEntity
import com.example.data.local.ProductCodeEntity
import com.example.data.local.ProductEntity
import com.example.data.local.SyncQueueEntity
import com.example.domain.AreaId
import com.example.domain.AreaSummary
import com.example.domain.BaseUnit
import com.example.domain.DuplicateCodeException
import com.example.domain.DuplicateMovementException
import com.example.domain.InitialInventoryItemDetail
import com.example.domain.InitialInventorySessionDetail
import com.example.domain.InitialInventoryStatus
import com.example.domain.InsufficientStockException
import com.example.domain.InventoryValidationException
import com.example.domain.LowStockAlertItem
import com.example.domain.MovementRecord
import com.example.domain.MovementType
import com.example.domain.PresentationOption
import com.example.domain.ProductSummary
import com.example.domain.QuantityFormatter
import com.example.domain.StockInArea
import com.example.domain.TransferRules
import com.example.domain.UnauthorizedTransferException
import com.example.domain.UnitConverter
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.util.UUID

class InventoryRepository(
    private val database: AppDatabase,
    private val cryptoManager: KeystoreCryptoManager
) {
    private val areaDao = database.areaDao()
    private val productDao = database.productDao()
    private val stockDao = database.stockDao()
    private val movementDao = database.movementDao()
    private val initialDao = database.initialInventoryDao()
    private val adjustmentDao = database.adjustmentDao()
    private val configDao = database.configDao()
    private val syncDao = database.syncDao()

    companion object {
        const val CONFIG_PIN_HASH = "admin_pin_hash"
        const val CONFIG_PIN_ENABLED = "admin_pin_enabled"
        const val CONFIG_SCAN_COOLDOWN_MS = "scan_cooldown_ms"
        private const val DUPLICATE_MOVEMENT_WINDOW_MS = 4_000L
        private const val EPSILON = 1e-6
    }

    suspend fun ensureInitialized() {
        val existingAreas = areaDao.getAllAreas()
        if (existingAreas.size < 4) {
            areaDao.insertAll(AppDatabase.defaultAreas())
        }
    }

    // --- Reactive Streams ---

    val areasFlow: Flow<List<AreaEntity>> = areaDao.observeAreas()

    val productsFlow: Flow<List<ProductSummary>> = combine(
        areaDao.observeAreas(),
        productDao.observeAllProducts(),
        productDao.observeAllProductCodes(),
        productDao.observeAllPresentations(),
        productDao.observeAllEquivalences(),
        stockDao.observeAllStocks()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val areas = values[0] as List<AreaEntity>
        @Suppress("UNCHECKED_CAST")
        val products = values[1] as List<ProductEntity>
        @Suppress("UNCHECKED_CAST")
        val codes = values[2] as List<ProductCodeEntity>
        @Suppress("UNCHECKED_CAST")
        val presentations = values[3] as List<PresentationEntity>
        @Suppress("UNCHECKED_CAST")
        val equivalences = values[4] as List<EquivalenceEntity>
        @Suppress("UNCHECKED_CAST")
        val stocks = values[5] as List<AreaStockEntity>

        val codesByProduct = codes.groupBy { it.productId }
        val presByProduct = presentations.groupBy { it.productId }
        val eqByPresId = equivalences.associateBy { it.presentationId }
        val stockByKey = stocks.associateBy { it.productId to it.areaId }

        products.map { product ->
            val productCodes = codesByProduct[product.id].orEmpty()
                .sortedWith(compareByDescending<ProductCodeEntity> { it.isPrimary }.thenBy { it.id })
            val productPres = presByProduct[product.id].orEmpty()
                .filter { it.isActive }
                .sortedWith(compareByDescending<PresentationEntity> { it.isBase }.thenBy { it.id })
                .map { pres ->
                    val factor = if (pres.isBase) 1.0 else (eqByPresId[pres.id]?.baseUnitsPerPresentation ?: 1.0)
                    PresentationOption(
                        presentationId = pres.id,
                        name = pres.name,
                        isBase = pres.isBase,
                        allowsFractions = pres.allowsFractions,
                        baseUnitsPerPresentation = factor
                    )
                }
            val areaStocks = areas.sortedBy { it.sortOrder }.map { area ->
                val stock = stockByKey[product.id to area.id]
                StockInArea(
                    areaId = area.id,
                    areaName = area.name,
                    areaColorHex = area.colorHex,
                    quantityBase = stock?.quantityBase ?: 0.0,
                    minStockBase = stock?.minStockBase ?: product.defaultMinStock
                )
            }
            ProductSummary(
                productId = product.id,
                internalCode = product.internalCode,
                name = product.name,
                description = product.description,
                category = product.category,
                baseUnit = product.baseUnit,
                allowsDecimals = product.allowsDecimals,
                allowFractionalPresentations = product.allowFractionalPresentations,
                defaultMinStock = product.defaultMinStock,
                preferredAreaId = product.preferredAreaId,
                isActive = product.isActive,
                primaryBarcode = productCodes.firstOrNull { it.isPrimary }?.code ?: productCodes.firstOrNull()?.code,
                allCodes = productCodes.map { it.code },
                presentations = productPres,
                stocksByArea = areaStocks
            )
        }
    }

    val areaSummariesFlow: Flow<List<AreaSummary>> = combine(
        areaDao.observeAreas(),
        productsFlow
    ) { areas, products ->
        val activeProducts = products.filter { it.isActive }
        areas.sortedBy { it.sortOrder }.map { area ->
            val stocksInArea = activeProducts.mapNotNull { product ->
                product.stocksByArea.firstOrNull { it.areaId == area.id }?.let { product to it }
            }
            val registeredInArea = stocksInArea.count { (p, s) ->
                s.quantityBase > EPSILON || s.minStockBase != null || p.preferredAreaId == area.id
            }
            val totalUnits = stocksInArea.sumOf { (_, s) -> s.quantityBase }
            val lowAlerts = stocksInArea.count { (_, s) ->
                s.minStockBase != null && s.quantityBase <= s.minStockBase + EPSILON
            }
            val outOfStock = stocksInArea.count { (_, s) ->
                s.quantityBase <= EPSILON && (s.minStockBase != null || stocksInArea.any { it.second.quantityBase > EPSILON })
            }
            AreaSummary(
                areaId = area.id,
                code = area.code,
                name = area.name,
                description = area.description,
                colorHex = area.colorHex,
                registeredProductsCount = registeredInArea,
                totalUnitsAvailable = totalUnits,
                lowStockAlertsCount = lowAlerts,
                outOfStockCount = outOfStock
            )
        }
    }

    val lowStockAlertsFlow: Flow<List<LowStockAlertItem>> = productsFlow.let { flow ->
        combine(flow, areaDao.observeAreas()) { products, _ ->
            products
                .filter { it.isActive }
                .flatMap { product ->
                    product.stocksByArea.mapNotNull { stock ->
                        val min = stock.minStockBase
                        val isLow = min != null && stock.quantityBase <= min + EPSILON
                        val isOut = stock.quantityBase <= EPSILON && (min != null || product.totalStockBase > EPSILON)
                        if (isLow || isOut) {
                            LowStockAlertItem(
                                productId = product.productId,
                                productName = product.name,
                                baseUnit = product.baseUnit,
                                areaId = stock.areaId,
                                areaName = stock.areaName,
                                areaColorHex = stock.areaColorHex,
                                currentStockBase = stock.quantityBase,
                                minStockBase = min,
                                isOutOfStock = stock.quantityBase <= EPSILON
                            )
                        } else {
                            null
                        }
                    }
                }
                .sortedWith(compareByDescending<LowStockAlertItem> { it.isOutOfStock }.thenBy { it.areaId }.thenBy { it.productName })
        }
    }

    val movementsFlow: Flow<List<MovementRecord>> = combine(
        movementDao.observeAllMovements(),
        productDao.observeAllProducts(),
        areaDao.observeAreas()
    ) { movements, products, areas ->
        val productMap = products.associateBy { it.id }
        val areaMap = areas.associateBy { it.id }
        movements.map { m ->
            val p = productMap[m.productId]
            MovementRecord(
                id = m.id,
                productId = m.productId,
                productName = p?.name ?: "Producto #${m.productId}",
                baseUnit = p?.baseUnit ?: BaseUnit.UNIDAD.key,
                movementType = MovementType.fromKey(m.movementType),
                quantityInput = m.quantityInput,
                presentationName = m.presentationName,
                conversionFactor = m.conversionFactor,
                quantityBase = m.quantityBase,
                sourceAreaId = m.sourceAreaId,
                sourceAreaName = m.sourceAreaId?.let { areaMap[it]?.name },
                destinationAreaId = m.destinationAreaId,
                destinationAreaName = m.destinationAreaId?.let { areaMap[it]?.name },
                notes = m.notes,
                timestamp = m.timestamp
            )
        }
    }

    val initialInventorySessionsFlow: Flow<List<InitialInventorySessionDetail>> = combine(
        initialDao.observeAllSessions(),
        initialDao.observeAllItems(),
        productDao.observeAllProducts(),
        productDao.observeAllProductCodes(),
        areaDao.observeAreas()
    ) { sessions, items, products, codes, areas ->
        val productMap = products.associateBy { it.id }
        val areaMap = areas.associateBy { it.id }
        val primaryCodeMap = codes
            .groupBy { it.productId }
            .mapValues { (_, list) -> list.firstOrNull { it.isPrimary }?.code ?: list.firstOrNull()?.code }
        val itemsBySession = items.groupBy { it.initialInventoryId }

        sessions.map { session ->
            val area = areaMap[session.areaId]
            val sessionItems = itemsBySession[session.id].orEmpty().map { item ->
                val prod = productMap[item.productId]
                InitialInventoryItemDetail(
                    id = item.id,
                    initialInventoryId = item.initialInventoryId,
                    productId = item.productId,
                    productName = prod?.name ?: "Producto #${item.productId}",
                    primaryCode = primaryCodeMap[item.productId],
                    baseUnit = prod?.baseUnit ?: BaseUnit.UNIDAD.key,
                    countedQuantityInput = item.countedQuantityInput,
                    presentationName = item.presentationName,
                    conversionFactor = item.conversionFactor,
                    countedQuantityBase = item.countedQuantityBase,
                    updatedAt = item.updatedAt
                )
            }
            InitialInventorySessionDetail(
                id = session.id,
                areaId = session.areaId,
                areaName = area?.name ?: "Área #${session.areaId}",
                areaColorHex = area?.colorHex ?: 0xFF1D4ED8,
                openedAt = session.openedAt,
                closedAt = session.closedAt,
                status = if (session.status == InitialInventoryStatus.CERRADO.key) {
                    InitialInventoryStatus.CERRADO
                } else {
                    InitialInventoryStatus.ABIERTO
                },
                notes = session.notes,
                items = sessionItems
            )
        }
    }

    // --- Lookups & Product Management ---

    suspend fun findProductByCode(rawCode: String): ProductSummary? {
        val clean = rawCode.trim()
        if (clean.isEmpty()) return null
        val entries = productDao.getEntriesByCode(clean)
        val matchedProductId = entries.firstOrNull()?.productId
            ?: productDao.getProductByInternalCode(clean)?.id
            ?: return null
        return getProductSummary(matchedProductId)
    }

    suspend fun getProductSummary(productId: Long): ProductSummary? {
        val product = productDao.getProductById(productId) ?: return null
        val areas = areaDao.getAllAreas().ifEmpty { AppDatabase.defaultAreas() }
        val codes = productDao.getCodesForProduct(productId)
        val presentations = productDao.getPresentationsForProduct(productId).filter { it.isActive }
        val equivalences = productDao.getEquivalencesForProduct(productId).associateBy { it.presentationId }
        val stocks = stockDao.getStocksForProduct(productId).associateBy { it.areaId }

        return ProductSummary(
            productId = product.id,
            internalCode = product.internalCode,
            name = product.name,
            description = product.description,
            category = product.category,
            baseUnit = product.baseUnit,
            allowsDecimals = product.allowsDecimals,
            allowFractionalPresentations = product.allowFractionalPresentations,
            defaultMinStock = product.defaultMinStock,
            preferredAreaId = product.preferredAreaId,
            isActive = product.isActive,
            primaryBarcode = codes.firstOrNull { it.isPrimary }?.code ?: codes.firstOrNull()?.code,
            allCodes = codes.map { it.code },
            presentations = presentations.map { pres ->
                val factor = if (pres.isBase) 1.0 else (equivalences[pres.id]?.baseUnitsPerPresentation ?: 1.0)
                PresentationOption(
                    presentationId = pres.id,
                    name = pres.name,
                    isBase = pres.isBase,
                    allowsFractions = pres.allowsFractions,
                    baseUnitsPerPresentation = factor
                )
            },
            stocksByArea = areas.sortedBy { it.sortOrder }.map { area ->
                val s = stocks[area.id]
                StockInArea(
                    areaId = area.id,
                    areaName = area.name,
                    areaColorHex = area.colorHex,
                    quantityBase = s?.quantityBase ?: 0.0,
                    minStockBase = s?.minStockBase ?: product.defaultMinStock
                )
            }
        )
    }

    suspend fun checkCodeConflicts(codes: List<String>, excludeProductId: Long? = null): List<Pair<String, String>> {
        val conflicts = mutableListOf<Pair<String, String>>()
        for (raw in codes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val matches = productDao.getEntriesByCode(raw)
            for (match in matches) {
                if (excludeProductId == null || match.productId != excludeProductId) {
                    val other = productDao.getProductById(match.productId)
                    if (other != null) {
                        conflicts.add(raw to other.name)
                    }
                }
            }
        }
        return conflicts
    }

    data class CustomPresentationDraft(
        val name: String,
        val baseUnitsPerPresentation: Double,
        val allowsFractions: Boolean = false
    )

    private suspend fun enqueueSyncItem(
        idempotencyKey: String,
        entityType: String,
        entityKey: String,
        entityId: Long,
        payloadJson: String,
        entityUpdatedAt: Long
    ) {
        syncDao.insertQueueItem(
            SyncQueueEntity(
                idempotencyKey = idempotencyKey,
                entityType = entityType,
                entityKey = entityKey,
                entityId = entityId,
                payloadJson = payloadJson,
                entityUpdatedAt = entityUpdatedAt,
                status = "PENDING",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun registerProduct(
        name: String,
        primaryCode: String = "",
        additionalCodes: List<String> = emptyList(),
        description: String = "",
        category: String = "General",
        baseUnit: BaseUnit = BaseUnit.UNIDAD,
        allowsDecimals: Boolean = baseUnit.defaultAllowsDecimals,
        allowFractionalPresentations: Boolean = false,
        defaultMinStock: Double? = null,
        extraPresentations: List<CustomPresentationDraft> = emptyList(),
        allowDuplicateCodeOverride: Boolean = false,
        customInternalCode: String? = null,
        preferredAreaId: Long = AreaId.ALMACEN_GENERAL.id,
        initialQuantityInput: Double? = null
    ): Result<Long> = runCatching {
        ensureInitialized()
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            throw InventoryValidationException("El nombre del producto es obligatorio.")
        }
        if (defaultMinStock != null && defaultMinStock < 0.0) {
            throw InventoryValidationException("El stock mínimo no puede ser negativo.")
        }
        areaDao.getAreaById(preferredAreaId)
            ?: throw InventoryValidationException("Área seleccionada inválida.")

        if (initialQuantityInput != null && initialQuantityInput < 0.0) {
            throw InventoryValidationException("La existencia inicial no puede ser negativa.")
        }
        if (initialQuantityInput != null && initialQuantityInput > 0.0) {
            UnitConverter.validateInputQuantity(
                quantityInput = initialQuantityInput,
                allowsDecimals = allowsDecimals,
                isPackPresentation = false,
                allowsFractionalPresentation = allowFractionalPresentations
            ).getOrThrow()
        }

        val resolvedInternalCode = customInternalCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: "PRD-${UUID.randomUUID().toString().take(8).uppercase()}"

        val existingInternal = productDao.getProductByInternalCode(resolvedInternalCode)
        if (existingInternal != null) {
            throw DuplicateCodeException(
                existingProductName = existingInternal.name,
                message = "El código interno '$resolvedInternalCode' ya está asignado a '${existingInternal.name}'."
            )
        }

        val allNormalizedCodes = (listOf(primaryCode) + additionalCodes)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (!allowDuplicateCodeOverride && allNormalizedCodes.isNotEmpty()) {
            val conflicts = checkCodeConflicts(allNormalizedCodes, excludeProductId = null)
            if (conflicts.isNotEmpty()) {
                val first = conflicts.first()
                throw DuplicateCodeException(
                    existingProductName = first.second,
                    message = "El código '${first.first}' ya está asociado al producto '${first.second}'."
                )
            }
        }

        // Validate presentations & equivalences before writing anything
        val seenPresNames = mutableSetOf(baseUnit.singular.lowercase(), baseUnit.plural.lowercase())
        for (draft in extraPresentations) {
            UnitConverter.validateEquivalence(
                presentationName = draft.name,
                baseUnit = baseUnit,
                baseUnitsPerPresentation = draft.baseUnitsPerPresentation
            ).getOrThrow()
            val key = draft.name.trim().lowercase()
            if (!seenPresNames.add(key)) {
                throw InventoryValidationException("Presentación duplicada: '${draft.name}'.")
            }
        }

        val now = System.currentTimeMillis()

        database.withTransaction {
            val productId = productDao.insertProduct(
                ProductEntity(
                    internalCode = resolvedInternalCode,
                    name = cleanName,
                    description = description.trim(),
                    category = category.trim().ifEmpty { "General" },
                    baseUnit = baseUnit.key,
                    allowsDecimals = allowsDecimals,
                    allowFractionalPresentations = allowFractionalPresentations,
                    defaultMinStock = defaultMinStock,
                    preferredAreaId = preferredAreaId,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now
                )
            )

            val codeEntities = allNormalizedCodes.mapIndexed { index, code ->
                ProductCodeEntity(
                    productId = productId,
                    code = code,
                    isPrimary = index == 0 && primaryCode.trim().isNotEmpty(),
                    label = if (index == 0) "Código principal" else "Código adicional"
                )
            }
            if (codeEntities.isNotEmpty()) {
                productDao.insertProductCodes(codeEntities)
            }

            // Always create base presentation (1:1)
            val basePresId = productDao.insertPresentation(
                PresentationEntity(
                    productId = productId,
                    name = baseUnit.singular,
                    isBase = true,
                    allowsFractions = allowsDecimals,
                    isActive = true
                )
            )
            productDao.upsertEquivalence(
                EquivalenceEntity(
                    productId = productId,
                    presentationId = basePresId,
                    baseUnitsPerPresentation = 1.0
                )
            )

            // Additional presentations
            for (draft in extraPresentations) {
                val presId = productDao.insertPresentation(
                    PresentationEntity(
                        productId = productId,
                        name = draft.name.trim(),
                        isBase = false,
                        allowsFractions = allowFractionalPresentations || draft.allowsFractions,
                        isActive = true
                    )
                )
                productDao.upsertEquivalence(
                    EquivalenceEntity(
                        productId = productId,
                        presentationId = presId,
                        baseUnitsPerPresentation = draft.baseUnitsPerPresentation
                    )
                )
            }

            // Ensure area stock association exists for the selected area (and apply initial quantity if provided)
            val startQty = initialQuantityInput ?: 0.0
            stockDao.upsertStock(
                AreaStockEntity(
                    productId = productId,
                    areaId = preferredAreaId,
                    quantityBase = startQty,
                    minStockBase = defaultMinStock,
                    updatedAt = now
                )
            )
            if (startQty > 0.0) {
                val movRefKey = UUID.randomUUID().toString()
                val movId = movementDao.insertMovement(
                    MovementEntity(
                        referenceKey = movRefKey,
                        productId = productId,
                        movementType = MovementType.INVENTARIO_INICIAL.key,
                        quantityInput = startQty,
                        presentationName = baseUnit.singular,
                        conversionFactor = 1.0,
                        quantityBase = startQty,
                        sourceAreaId = null,
                        destinationAreaId = preferredAreaId,
                        notes = "Registro manual inicial en área",
                        timestamp = now
                    )
                )
                enqueueSyncItem(
                    idempotencyKey = "MOV:$movRefKey",
                    entityType = "MOVEMENT",
                    entityKey = movRefKey,
                    entityId = movId,
                    payloadJson = JSONObject().apply {
                        put("referenceKey", movRefKey)
                        put("productInternalCode", resolvedInternalCode)
                        put("movementType", MovementType.INVENTARIO_INICIAL.key)
                        put("quantityBase", startQty)
                        put("destinationAreaId", preferredAreaId)
                        put("timestamp", now)
                    }.toString(),
                    entityUpdatedAt = now
                )
            }

            enqueueSyncItem(
                idempotencyKey = "PRD:$resolvedInternalCode:CREATE",
                entityType = "PRODUCT",
                entityKey = resolvedInternalCode,
                entityId = productId,
                payloadJson = JSONObject().apply {
                    put("internalCode", resolvedInternalCode)
                    put("name", cleanName)
                    put("primaryCode", primaryCode.trim())
                    put("category", category.trim().ifEmpty { "General" })
                    put("baseUnit", baseUnit.key)
                    put("preferredAreaId", preferredAreaId)
                    put("isActive", true)
                    put("updatedAt", now)
                }.toString(),
                entityUpdatedAt = now
            )

            productId
        }
    }

    suspend fun updateProduct(
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
        allowDuplicateCodeOverride: Boolean = false,
        customInternalCode: String? = null,
        preferredAreaId: Long? = null
    ): Result<Unit> = runCatching {
        val existing = productDao.getProductById(productId)
            ?: throw InventoryValidationException("El producto no existe.")
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            throw InventoryValidationException("El nombre del producto es obligatorio.")
        }
        val nextInternalCode = customInternalCode?.trim()?.takeIf { it.isNotEmpty() } ?: existing.internalCode
        if (nextInternalCode != existing.internalCode) {
            val collision = productDao.getProductByInternalCode(nextInternalCode)
            if (collision != null && collision.id != productId) {
                throw DuplicateCodeException(
                    existingProductName = collision.name,
                    message = "El código interno '$nextInternalCode' ya pertenece a '${collision.name}'."
                )
            }
        }
        val nextAreaId = preferredAreaId ?: existing.preferredAreaId
        areaDao.getAreaById(nextAreaId) ?: throw InventoryValidationException("Área inválida.")

        val allNormalizedCodes = (listOf(primaryCode) + additionalCodes)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (!allowDuplicateCodeOverride && allNormalizedCodes.isNotEmpty()) {
            val conflicts = checkCodeConflicts(allNormalizedCodes, excludeProductId = productId)
            if (conflicts.isNotEmpty()) {
                val first = conflicts.first()
                throw DuplicateCodeException(
                    existingProductName = first.second,
                    message = "El código '${first.first}' ya está asociado a '${first.second}'."
                )
            }
        }

        val now = System.currentTimeMillis()
        database.withTransaction {
            productDao.updateProduct(
                existing.copy(
                    internalCode = nextInternalCode,
                    name = cleanName,
                    description = description.trim(),
                    category = category.trim().ifEmpty { "General" },
                    allowsDecimals = allowsDecimals,
                    allowFractionalPresentations = allowFractionalPresentations,
                    defaultMinStock = defaultMinStock,
                    preferredAreaId = nextAreaId,
                    isActive = isActive,
                    updatedAt = now
                )
            )
            productDao.deleteCodesForProduct(productId)
            val codeEntities = allNormalizedCodes.mapIndexed { index, code ->
                ProductCodeEntity(
                    productId = productId,
                    code = code,
                    isPrimary = index == 0 && primaryCode.trim().isNotEmpty(),
                    label = if (index == 0) "Código principal" else "Código adicional"
                )
            }
            if (codeEntities.isNotEmpty()) {
                productDao.insertProductCodes(codeEntities)
            }
            enqueueSyncItem(
                idempotencyKey = "PRD:$nextInternalCode:UPD:$now",
                entityType = "PRODUCT",
                entityKey = nextInternalCode,
                entityId = productId,
                payloadJson = JSONObject().apply {
                    put("internalCode", nextInternalCode)
                    put("name", cleanName)
                    put("primaryCode", primaryCode.trim())
                    put("category", category.trim().ifEmpty { "General" })
                    put("preferredAreaId", nextAreaId)
                    put("isActive", isActive)
                    put("updatedAt", now)
                }.toString(),
                entityUpdatedAt = now
            )
        }
    }

    suspend fun setProductActive(productId: Long, active: Boolean): Result<Unit> = runCatching {
        val existing = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")
        val now = System.currentTimeMillis()
        database.withTransaction {
            productDao.updateProduct(
                existing.copy(isActive = active, updatedAt = now)
            )
            enqueueSyncItem(
                idempotencyKey = "PRD:${existing.internalCode}:ACT:$now",
                entityType = "PRODUCT",
                entityKey = existing.internalCode,
                entityId = productId,
                payloadJson = JSONObject().apply {
                    put("internalCode", existing.internalCode)
                    put("name", existing.name)
                    put("isActive", active)
                    put("updatedAt", now)
                }.toString(),
                entityUpdatedAt = now
            )
        }
    }

    suspend fun addPresentationToProduct(
        productId: Long,
        presentationName: String,
        baseUnitsPerPresentation: Double,
        allowsFractions: Boolean = false
    ): Result<Long> = runCatching {
        val product = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")
        val baseUnit = BaseUnit.fromKey(product.baseUnit)
        UnitConverter.validateEquivalence(presentationName, baseUnit, baseUnitsPerPresentation).getOrThrow()

        val existingPresentations = productDao.getPresentationsForProduct(productId)
        val cleanName = presentationName.trim()
        if (existingPresentations.any { it.name.equals(cleanName, ignoreCase = true) && it.isActive }) {
            throw InventoryValidationException("Ya existe una presentación llamada '$cleanName'.")
        }

        database.withTransaction {
            val presId = productDao.insertPresentation(
                PresentationEntity(
                    productId = productId,
                    name = cleanName,
                    isBase = false,
                    allowsFractions = allowsFractions || product.allowFractionalPresentations,
                    isActive = true
                )
            )
            productDao.upsertEquivalence(
                EquivalenceEntity(
                    productId = productId,
                    presentationId = presId,
                    baseUnitsPerPresentation = baseUnitsPerPresentation
                )
            )
            presId
        }
    }

    suspend fun setAreaMinStock(productId: Long, areaId: Long, minStockBase: Double?): Result<Unit> = runCatching {
        if (minStockBase != null && minStockBase < 0.0) {
            throw InventoryValidationException("El stock mínimo no puede ser negativo.")
        }
        database.withTransaction {
            val existing = stockDao.getStock(productId, areaId)
            val now = System.currentTimeMillis()
            if (existing != null) {
                stockDao.upsertStock(existing.copy(minStockBase = minStockBase, updatedAt = now))
            } else {
                stockDao.upsertStock(
                    AreaStockEntity(
                        productId = productId,
                        areaId = areaId,
                        quantityBase = 0.0,
                        minStockBase = minStockBase,
                        updatedAt = now
                    )
                )
            }
        }
    }

    // --- Core Inventory Operations (Transactional) ---

    private suspend fun resolveConversion(
        product: ProductEntity,
        presentationId: Long?,
        quantityInput: Double
    ): Triple<String, Double, Double> {
        val presentations = productDao.getPresentationsForProduct(product.id).filter { it.isActive }
        val selectedPres = if (presentationId != null) {
            presentations.firstOrNull { it.id == presentationId }
                ?: throw InventoryValidationException("Presentación no válida para este producto.")
        } else {
            presentations.firstOrNull { it.isBase }
                ?: throw InventoryValidationException("Unidad base no configurada para el producto.")
        }

        val factor = if (selectedPres.isBase) {
            1.0
        } else {
            productDao.getEquivalenceForPresentation(selectedPres.id)?.baseUnitsPerPresentation
                ?: throw InventoryValidationException("No existe equivalencia registrada para '${selectedPres.name}'.")
        }

        val quantityBase = UnitConverter.convertToBase(
            quantityInput = quantityInput,
            conversionFactor = factor,
            productAllowsDecimals = product.allowsDecimals,
            isPackPresentation = !selectedPres.isBase,
            presentationAllowsFractions = selectedPres.allowsFractions || product.allowFractionalPresentations
        ).getOrThrow()

        return Triple(selectedPres.name, factor, quantityBase)
    }

    private suspend fun guardDuplicateMovement(
        referenceKey: String?,
        productId: Long,
        movementType: MovementType,
        quantityBase: Double,
        sourceAreaId: Long?,
        destinationAreaId: Long?,
        allowDuplicateOverride: Boolean
    ): String {
        val key = referenceKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        if (movementDao.getByReferenceKey(key) != null) {
            throw DuplicateMovementException("Esta operación ya fue registrada anteriormente.")
        }
        if (!allowDuplicateOverride) {
            val since = System.currentTimeMillis() - DUPLICATE_MOVEMENT_WINDOW_MS
            val recent = movementDao.findRecentDuplicate(
                productId = productId,
                movementType = movementType.key,
                quantityBase = quantityBase,
                sourceAreaId = sourceAreaId,
                destinationAreaId = destinationAreaId,
                sinceTimestamp = since
            )
            if (recent != null) {
                throw DuplicateMovementException(
                    "Se detectó un movimiento idéntico reciente. Espera unos segundos o confirma si deseas registrarlo nuevamente."
                )
            }
        }
        return key
    }

    suspend fun registrarEntrada(
        productId: Long,
        destinationAreaId: Long,
        quantityInput: Double,
        presentationId: Long? = null,
        notes: String = "",
        referenceKey: String? = null,
        allowDuplicateOverride: Boolean = false
    ): Result<Long> = runCatching {
        ensureInitialized()
        val product = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")
        if (!product.isActive) {
            throw InventoryValidationException("El producto '${product.name}' está inactivo.")
        }
        areaDao.getAreaById(destinationAreaId)
            ?: throw InventoryValidationException("Área de destino inválida.")

        val (presName, factor, quantityBase) = resolveConversion(product, presentationId, quantityInput)

        database.withTransaction {
            val refKey = guardDuplicateMovement(
                referenceKey = referenceKey,
                productId = productId,
                movementType = MovementType.ENTRADA,
                quantityBase = quantityBase,
                sourceAreaId = null,
                destinationAreaId = destinationAreaId,
                allowDuplicateOverride = allowDuplicateOverride
            )
            val now = System.currentTimeMillis()
            val currentStock = stockDao.getStock(productId, destinationAreaId)
            val newQty = (currentStock?.quantityBase ?: 0.0) + quantityBase
            stockDao.upsertStock(
                AreaStockEntity(
                    id = currentStock?.id ?: 0L,
                    productId = productId,
                    areaId = destinationAreaId,
                    quantityBase = newQty,
                    minStockBase = currentStock?.minStockBase,
                    updatedAt = now
                )
            )
            val movId = movementDao.insertMovement(
                MovementEntity(
                    referenceKey = refKey,
                    productId = productId,
                    movementType = MovementType.ENTRADA.key,
                    quantityInput = quantityInput,
                    presentationName = presName,
                    conversionFactor = factor,
                    quantityBase = quantityBase,
                    sourceAreaId = null,
                    destinationAreaId = destinationAreaId,
                    notes = notes.trim(),
                    timestamp = now
                )
            )
            enqueueSyncItem(
                idempotencyKey = "MOV:$refKey",
                entityType = "MOVEMENT",
                entityKey = refKey,
                entityId = movId,
                payloadJson = JSONObject().apply {
                    put("referenceKey", refKey)
                    put("productInternalCode", product.internalCode)
                    put("movementType", MovementType.ENTRADA.key)
                    put("quantityBase", quantityBase)
                    put("destinationAreaId", destinationAreaId)
                    put("timestamp", now)
                }.toString(),
                entityUpdatedAt = now
            )
            movId
        }
    }

    suspend fun registrarSalida(
        productId: Long,
        sourceAreaId: Long,
        quantityInput: Double,
        presentationId: Long? = null,
        notes: String = "",
        referenceKey: String? = null,
        allowDuplicateOverride: Boolean = false
    ): Result<Long> = runCatching {
        ensureInitialized()
        val product = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")
        if (!product.isActive) {
            throw InventoryValidationException("El producto '${product.name}' está inactivo.")
        }
        val area = areaDao.getAreaById(sourceAreaId)
            ?: throw InventoryValidationException("Área de origen inválida.")

        val (presName, factor, quantityBase) = resolveConversion(product, presentationId, quantityInput)

        database.withTransaction {
            val refKey = guardDuplicateMovement(
                referenceKey = referenceKey,
                productId = productId,
                movementType = MovementType.SALIDA,
                quantityBase = quantityBase,
                sourceAreaId = sourceAreaId,
                destinationAreaId = null,
                allowDuplicateOverride = allowDuplicateOverride
            )
            val currentStock = stockDao.getStock(productId, sourceAreaId)
            val available = currentStock?.quantityBase ?: 0.0
            if (available + EPSILON < quantityBase) {
                throw InsufficientStockException(
                    "Existencias insuficientes en ${area.name}. Disponible: ${QuantityFormatter.formatBaseQuantity(available, product.baseUnit)}, solicitado: ${QuantityFormatter.formatBaseQuantity(quantityBase, product.baseUnit)}."
                )
            }
            val now = System.currentTimeMillis()
            val remaining = (available - quantityBase).coerceAtLeast(0.0)
            stockDao.upsertStock(
                AreaStockEntity(
                    id = currentStock?.id ?: 0L,
                    productId = productId,
                    areaId = sourceAreaId,
                    quantityBase = remaining,
                    minStockBase = currentStock?.minStockBase,
                    updatedAt = now
                )
            )
            val movId = movementDao.insertMovement(
                MovementEntity(
                    referenceKey = refKey,
                    productId = productId,
                    movementType = MovementType.SALIDA.key,
                    quantityInput = quantityInput,
                    presentationName = presName,
                    conversionFactor = factor,
                    quantityBase = quantityBase,
                    sourceAreaId = sourceAreaId,
                    destinationAreaId = null,
                    notes = notes.trim(),
                    timestamp = now
                )
            )
            enqueueSyncItem(
                idempotencyKey = "MOV:$refKey",
                entityType = "MOVEMENT",
                entityKey = refKey,
                entityId = movId,
                payloadJson = JSONObject().apply {
                    put("referenceKey", refKey)
                    put("productInternalCode", product.internalCode)
                    put("movementType", MovementType.SALIDA.key)
                    put("quantityBase", quantityBase)
                    put("sourceAreaId", sourceAreaId)
                    put("timestamp", now)
                }.toString(),
                entityUpdatedAt = now
            )
            movId
        }
    }

    suspend fun realizarTransferencia(
        productId: Long,
        sourceAreaId: Long,
        destinationAreaId: Long,
        quantityInput: Double,
        presentationId: Long? = null,
        notes: String = "",
        referenceKey: String? = null,
        allowDuplicateOverride: Boolean = false
    ): Result<Long> = runCatching {
        ensureInitialized()
        if (sourceAreaId == destinationAreaId) {
            throw InventoryValidationException("El área de origen y destino no pueden ser iguales.")
        }
        if (!TransferRules.isAuthorized(sourceAreaId, destinationAreaId)) {
            throw UnauthorizedTransferException(
                "Ruta de transferencia no autorizada. Rutas permitidas: Almacén general → Despensa y Almacén de licores → Bar."
            )
        }

        val product = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")
        if (!product.isActive) {
            throw InventoryValidationException("El producto '${product.name}' está inactivo.")
        }
        val sourceArea = areaDao.getAreaById(sourceAreaId)
            ?: throw InventoryValidationException("Área de origen inválida.")
        areaDao.getAreaById(destinationAreaId)
            ?: throw InventoryValidationException("Área de destino inválida.")

        val (presName, factor, quantityBase) = resolveConversion(product, presentationId, quantityInput)

        database.withTransaction {
            val refKey = guardDuplicateMovement(
                referenceKey = referenceKey,
                productId = productId,
                movementType = MovementType.TRANSFERENCIA,
                quantityBase = quantityBase,
                sourceAreaId = sourceAreaId,
                destinationAreaId = destinationAreaId,
                allowDuplicateOverride = allowDuplicateOverride
            )
            val sourceStock = stockDao.getStock(productId, sourceAreaId)
            val available = sourceStock?.quantityBase ?: 0.0
            if (available + EPSILON < quantityBase) {
                throw InsufficientStockException(
                    "Existencias insuficientes en ${sourceArea.name} para transferir. Disponible: ${QuantityFormatter.formatBaseQuantity(available, product.baseUnit)}."
                )
            }

            val now = System.currentTimeMillis()
            val newSourceQty = (available - quantityBase).coerceAtLeast(0.0)
            stockDao.upsertStock(
                AreaStockEntity(
                    id = sourceStock?.id ?: 0L,
                    productId = productId,
                    areaId = sourceAreaId,
                    quantityBase = newSourceQty,
                    minStockBase = sourceStock?.minStockBase,
                    updatedAt = now
                )
            )

            val destStock = stockDao.getStock(productId, destinationAreaId)
            val newDestQty = (destStock?.quantityBase ?: 0.0) + quantityBase
            stockDao.upsertStock(
                AreaStockEntity(
                    id = destStock?.id ?: 0L,
                    productId = productId,
                    areaId = destinationAreaId,
                    quantityBase = newDestQty,
                    minStockBase = destStock?.minStockBase,
                    updatedAt = now
                )
            )

            val movId = movementDao.insertMovement(
                MovementEntity(
                    referenceKey = refKey,
                    productId = productId,
                    movementType = MovementType.TRANSFERENCIA.key,
                    quantityInput = quantityInput,
                    presentationName = presName,
                    conversionFactor = factor,
                    quantityBase = quantityBase,
                    sourceAreaId = sourceAreaId,
                    destinationAreaId = destinationAreaId,
                    notes = notes.trim(),
                    timestamp = now
                )
            )
            enqueueSyncItem(
                idempotencyKey = "MOV:$refKey",
                entityType = "MOVEMENT",
                entityKey = refKey,
                entityId = movId,
                payloadJson = JSONObject().apply {
                    put("referenceKey", refKey)
                    put("productInternalCode", product.internalCode)
                    put("movementType", MovementType.TRANSFERENCIA.key)
                    put("quantityBase", quantityBase)
                    put("sourceAreaId", sourceAreaId)
                    put("destinationAreaId", destinationAreaId)
                    put("timestamp", now)
                }.toString(),
                entityUpdatedAt = now
            )
            movId
        }
    }

    suspend fun registrarAjuste(
        productId: Long,
        areaId: Long,
        newQuantityInput: Double,
        presentationId: Long? = null,
        reason: String
    ): Result<Long> = runCatching {
        ensureInitialized()
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) {
            throw InventoryValidationException("Debes indicar el motivo del ajuste.")
        }
        val product = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")
        areaDao.getAreaById(areaId)
            ?: throw InventoryValidationException("Área inválida.")

        val (presName, factor, newQuantityBase) = if (newQuantityInput == 0.0) {
            val baseName = BaseUnit.fromKey(product.baseUnit).singular
            Triple(baseName, 1.0, 0.0)
        } else {
            resolveConversion(product, presentationId, newQuantityInput)
        }
        if (newQuantityBase < 0.0) {
            throw InventoryValidationException("La existencia ajustada no puede ser negativa.")
        }

        database.withTransaction {
            val currentStock = stockDao.getStock(productId, areaId)
            val previousQty = currentStock?.quantityBase ?: 0.0
            val delta = newQuantityBase - previousQty
            val now = System.currentTimeMillis()

            stockDao.upsertStock(
                AreaStockEntity(
                    id = currentStock?.id ?: 0L,
                    productId = productId,
                    areaId = areaId,
                    quantityBase = newQuantityBase,
                    minStockBase = currentStock?.minStockBase,
                    updatedAt = now
                )
            )

            val movRefKey = UUID.randomUUID().toString()
            val movementId = movementDao.insertMovement(
                MovementEntity(
                    referenceKey = movRefKey,
                    productId = productId,
                    movementType = MovementType.AJUSTE.key,
                    quantityInput = newQuantityInput,
                    presentationName = presName,
                    conversionFactor = factor,
                    quantityBase = kotlin.math.abs(delta),
                    sourceAreaId = if (delta < 0) areaId else null,
                    destinationAreaId = if (delta >= 0) areaId else null,
                    notes = "Ajuste (${QuantityFormatter.formatNumber(previousQty)} → ${QuantityFormatter.formatNumber(newQuantityBase)}): $cleanReason",
                    timestamp = now
                )
            )

            adjustmentDao.insertAdjustment(
                AdjustmentEntity(
                    productId = productId,
                    areaId = areaId,
                    previousQuantityBase = previousQty,
                    newQuantityBase = newQuantityBase,
                    deltaBase = delta,
                    reason = cleanReason,
                    movementId = movementId,
                    timestamp = now
                )
            )
            enqueueSyncItem(
                idempotencyKey = "MOV:$movRefKey",
                entityType = "MOVEMENT",
                entityKey = movRefKey,
                entityId = movementId,
                payloadJson = JSONObject().apply {
                    put("referenceKey", movRefKey)
                    put("productInternalCode", product.internalCode)
                    put("movementType", MovementType.AJUSTE.key)
                    put("areaId", areaId)
                    put("newQuantityBase", newQuantityBase)
                    put("timestamp", now)
                }.toString(),
                entityUpdatedAt = now
            )
            movementId
        }
    }

    // --- Initial Inventory Module ---

    suspend fun abrirInventarioInicial(areaId: Long, notes: String = ""): Result<Long> = runCatching {
        ensureInitialized()
        areaDao.getAreaById(areaId) ?: throw InventoryValidationException("Área inválida.")
        val existingOpen = initialDao.getOpenSessionForArea(areaId)
        if (existingOpen != null) {
            return@runCatching existingOpen.id
        }
        initialDao.insertSession(
            InitialInventoryEntity(
                areaId = areaId,
                openedAt = System.currentTimeMillis(),
                closedAt = null,
                status = InitialInventoryStatus.ABIERTO.key,
                notes = notes.trim()
            )
        )
    }

    suspend fun registrarConteoInicial(
        sessionId: Long,
        productId: Long,
        countedQuantityInput: Double,
        presentationId: Long? = null
    ): Result<Unit> = runCatching {
        val session = initialDao.getSessionById(sessionId)
            ?: throw InventoryValidationException("Sesión de inventario inicial no encontrada.")
        if (session.status != InitialInventoryStatus.ABIERTO.key) {
            throw InventoryValidationException("El inventario inicial ya fue cerrado. Cualquier corrección debe registrarse como ajuste.")
        }
        val product = productDao.getProductById(productId)
            ?: throw InventoryValidationException("Producto no encontrado.")

        val (presName, factor, quantityBase) = if (countedQuantityInput == 0.0) {
            Triple(BaseUnit.fromKey(product.baseUnit).singular, 1.0, 0.0)
        } else {
            resolveConversion(product, presentationId, countedQuantityInput)
        }

        val existingItem = initialDao.getItem(sessionId, productId)
        initialDao.upsertItem(
            InitialInventoryItemEntity(
                id = existingItem?.id ?: 0L,
                initialInventoryId = sessionId,
                productId = productId,
                countedQuantityInput = countedQuantityInput,
                presentationName = presName,
                conversionFactor = factor,
                countedQuantityBase = quantityBase,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun eliminarItemConteoInicial(itemId: Long): Result<Unit> = runCatching {
        initialDao.deleteItemById(itemId)
    }

    suspend fun cerrarInventarioInicial(sessionId: Long): Result<Int> = runCatching {
        database.withTransaction {
            val session = initialDao.getSessionById(sessionId)
                ?: throw InventoryValidationException("Sesión de inventario inicial no encontrada.")
            if (session.status == InitialInventoryStatus.CERRADO.key) {
                throw InventoryValidationException("Este inventario inicial ya está cerrado.")
            }
            val items = initialDao.getItemsForSession(sessionId)
            if (items.isEmpty()) {
                throw InventoryValidationException("Agrega al menos un producto contado antes de cerrar el inventario inicial.")
            }

            val now = System.currentTimeMillis()
            for (item in items) {
                val existingStock = stockDao.getStock(item.productId, session.areaId)
                stockDao.upsertStock(
                    AreaStockEntity(
                        id = existingStock?.id ?: 0L,
                        productId = item.productId,
                        areaId = session.areaId,
                        quantityBase = item.countedQuantityBase,
                        minStockBase = existingStock?.minStockBase,
                        updatedAt = now
                    )
                )
                val initRefKey = "INIT-${sessionId}-${item.productId}-$now"
                val movId = movementDao.insertMovement(
                    MovementEntity(
                        referenceKey = initRefKey,
                        productId = item.productId,
                        movementType = MovementType.INVENTARIO_INICIAL.key,
                        quantityInput = item.countedQuantityInput,
                        presentationName = item.presentationName,
                        conversionFactor = item.conversionFactor,
                        quantityBase = item.countedQuantityBase,
                        sourceAreaId = null,
                        destinationAreaId = session.areaId,
                        notes = "Cierre de inventario inicial #$sessionId",
                        initialInventoryId = sessionId,
                        timestamp = now
                    )
                )
                enqueueSyncItem(
                    idempotencyKey = "MOV:$initRefKey",
                    entityType = "MOVEMENT",
                    entityKey = initRefKey,
                    entityId = movId,
                    payloadJson = JSONObject().apply {
                        put("referenceKey", initRefKey)
                        put("productId", item.productId)
                        put("movementType", MovementType.INVENTARIO_INICIAL.key)
                        put("destinationAreaId", session.areaId)
                        put("quantityBase", item.countedQuantityBase)
                        put("timestamp", now)
                    }.toString(),
                    entityUpdatedAt = now
                )
            }

            initialDao.updateSession(
                session.copy(
                    closedAt = now,
                    status = InitialInventoryStatus.CERRADO.key
                )
            )
            items.size
        }
    }

    // --- Security & Configuration ---

    suspend fun isPinConfigured(): Boolean {
        val hash = configDao.getConfig(CONFIG_PIN_HASH)?.value
        return !hash.isNullOrBlank()
    }

    suspend fun isPinLockEnabled(): Boolean {
        val cfg = configDao.getConfig(CONFIG_PIN_ENABLED)?.value
        return cfg == "true"
    }

    suspend fun setPin(pin: String): Result<Unit> = runCatching {
        val clean = pin.trim()
        if (clean.length !in 4..6 || !clean.all { it.isDigit() }) {
            throw InventoryValidationException("El PIN debe tener entre 4 y 6 dígitos numéricos.")
        }
        val protectedHash = cryptoManager.hashPinWithSalt(clean)
        val now = System.currentTimeMillis()
        database.withTransaction {
            configDao.setConfig(
                AppConfigEntity(
                    key = CONFIG_PIN_HASH,
                    value = protectedHash,
                    isEncrypted = true,
                    updatedAt = now
                )
            )
            configDao.setConfig(
                AppConfigEntity(
                    key = CONFIG_PIN_ENABLED,
                    value = "true",
                    isEncrypted = false,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val stored = configDao.getConfig(CONFIG_PIN_HASH)?.value ?: return false
        return cryptoManager.verifyPin(pin.trim(), stored)
    }

    suspend fun setPinLockEnabled(enabled: Boolean) {
        configDao.setConfig(
            AppConfigEntity(
                key = CONFIG_PIN_ENABLED,
                value = enabled.toString(),
                isEncrypted = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getScanCooldownMs(): Long {
        return configDao.getConfig(CONFIG_SCAN_COOLDOWN_MS)?.value?.toLongOrNull() ?: 2500L
    }

    suspend fun setScanCooldownMs(cooldownMs: Long) {
        configDao.setConfig(
            AppConfigEntity(
                key = CONFIG_SCAN_COOLDOWN_MS,
                value = cooldownMs.coerceIn(1000L, 10000L).toString(),
                isEncrypted = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
