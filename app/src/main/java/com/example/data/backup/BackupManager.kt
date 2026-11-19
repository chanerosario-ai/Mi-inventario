package com.example.data.backup

import android.content.Context
import android.net.Uri
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
import com.example.data.local.SyncConflictEntity
import com.example.data.local.SyncLedgerEntity
import com.example.data.local.SyncQueueEntity
import com.example.domain.InventoryValidationException
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val cryptoManager: KeystoreCryptoManager
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val canonicalJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun backupsDir(): File = File(context.filesDir, "backups").apply {
        if (!exists()) mkdirs()
    }

    suspend fun listLocalBackups(): List<BackupFileInfo> = withContext(Dispatchers.IO) {
        backupsDir()
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                BackupFileInfo(
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    createdAt = file.lastModified(),
                    sizeBytes = file.length()
                )
            } ?: emptyList()
    }

    suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) {
        val areas = database.areaDao().getAllAreas().ifEmpty { AppDatabase.defaultAreas() }
        val products = database.productDao().getAllProducts()
        val productCodes = database.productDao().getAllProductCodes()
        val presentations = database.productDao().getAllPresentations()
        val equivalences = database.productDao().getAllEquivalences()
        val stocks = database.stockDao().getAllStocks()
        val movements = database.movementDao().getAllMovements()
        val initialInventories = database.initialInventoryDao().getAllSessions()
        val initialItems = database.initialInventoryDao().getAllItems()
        val adjustments = database.adjustmentDao().getAllAdjustments()
        val configs = database.configDao().getAllConfig()
            // Avoid exporting device-bound Keystore cipher blobs if they shouldn't break another device,
            // or mark them safely. We keep PIN configuration only for local restores on same device.
            .map { cfg ->
                BackupConfig(
                    key = cfg.key,
                    value = cfg.value,
                    isEncrypted = cfg.isEncrypted,
                    updatedAt = cfg.updatedAt
                )
            }

        val syncQueue = database.syncDao().getAllQueue()
        val syncLedger = database.syncDao().getAllLedger()
        val syncConflicts = database.syncDao().getAllConflicts()

        val payload = BackupPayload(
            areas = areas.map { BackupArea(it.id, it.code, it.name, it.description, it.colorHex, it.sortOrder) },
            products = products.map {
                BackupProduct(
                    id = it.id,
                    internalCode = it.internalCode,
                    name = it.name,
                    description = it.description,
                    category = it.category,
                    baseUnit = it.baseUnit,
                    allowsDecimals = it.allowsDecimals,
                    allowFractionalPresentations = it.allowFractionalPresentations,
                    defaultMinStock = it.defaultMinStock,
                    preferredAreaId = it.preferredAreaId,
                    isActive = it.isActive,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            },
            productCodes = productCodes.map {
                BackupProductCode(it.id, it.productId, it.code, it.isPrimary, it.label)
            },
            presentations = presentations.map {
                BackupPresentation(it.id, it.productId, it.name, it.isBase, it.allowsFractions, it.isActive)
            },
            equivalences = equivalences.map {
                BackupEquivalence(it.id, it.productId, it.presentationId, it.baseUnitsPerPresentation)
            },
            areaStocks = stocks.map {
                BackupAreaStock(it.id, it.productId, it.areaId, it.quantityBase, it.minStockBase, it.updatedAt)
            },
            movements = movements.map {
                BackupMovement(
                    id = it.id,
                    referenceKey = it.referenceKey,
                    productId = it.productId,
                    movementType = it.movementType,
                    quantityInput = it.quantityInput,
                    presentationName = it.presentationName,
                    conversionFactor = it.conversionFactor,
                    quantityBase = it.quantityBase,
                    sourceAreaId = it.sourceAreaId,
                    destinationAreaId = it.destinationAreaId,
                    notes = it.notes,
                    initialInventoryId = it.initialInventoryId,
                    timestamp = it.timestamp
                )
            },
            initialInventories = initialInventories.map {
                BackupInitialInventory(it.id, it.areaId, it.openedAt, it.closedAt, it.status, it.notes)
            },
            initialInventoryItems = initialItems.map {
                BackupInitialInventoryItem(
                    id = it.id,
                    initialInventoryId = it.initialInventoryId,
                    productId = it.productId,
                    countedQuantityInput = it.countedQuantityInput,
                    presentationName = it.presentationName,
                    conversionFactor = it.conversionFactor,
                    countedQuantityBase = it.countedQuantityBase,
                    updatedAt = it.updatedAt
                )
            },
            adjustments = adjustments.map {
                BackupAdjustment(
                    id = it.id,
                    productId = it.productId,
                    areaId = it.areaId,
                    previousQuantityBase = it.previousQuantityBase,
                    newQuantityBase = it.newQuantityBase,
                    deltaBase = it.deltaBase,
                    reason = it.reason,
                    movementId = it.movementId,
                    timestamp = it.timestamp
                )
            },
            configs = configs,
            syncQueue = syncQueue.map {
                BackupSyncQueueItem(
                    id = it.id,
                    idempotencyKey = it.idempotencyKey,
                    entityType = it.entityType,
                    entityKey = it.entityKey,
                    entityId = it.entityId,
                    payloadJson = it.payloadJson,
                    entityUpdatedAt = it.entityUpdatedAt,
                    status = it.status,
                    retryCount = it.retryCount,
                    lastError = it.lastError,
                    createdAt = it.createdAt,
                    syncedAt = it.syncedAt
                )
            },
            syncLedger = syncLedger.map {
                BackupSyncLedgerItem(
                    idempotencyKey = it.idempotencyKey,
                    entityType = it.entityType,
                    entityKey = it.entityKey,
                    entityUpdatedAt = it.entityUpdatedAt,
                    payloadJson = it.payloadJson,
                    syncedAt = it.syncedAt
                )
            },
            syncConflicts = syncConflicts.map {
                BackupSyncConflictItem(
                    id = it.id,
                    queueItemId = it.queueItemId,
                    entityType = it.entityType,
                    entityKey = it.entityKey,
                    localPayloadJson = it.localPayloadJson,
                    remotePayloadJson = it.remotePayloadJson,
                    localTimestamp = it.localTimestamp,
                    remoteTimestamp = it.remoteTimestamp,
                    reason = it.reason,
                    resolutionStatus = it.resolutionStatus,
                    createdAt = it.createdAt,
                    resolvedAt = it.resolvedAt
                )
            }
        )

        val payloadString = canonicalJson.encodeToString(payload)
        val checksum = cryptoManager.sha256(payloadString)
        val now = System.currentTimeMillis()
        val envelope = BackupEnvelope(
            createdAt = now,
            checksumSha256 = checksum,
            payload = payload
        )
        json.encodeToString(envelope)
    }

    suspend fun createLocalBackup(): Result<BackupFileInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val content = buildBackupJson()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(backupsDir(), "mi_inventario_respaldo_$stamp.json")
            file.writeText(content, Charsets.UTF_8)
            BackupFileInfo(
                fileName = file.name,
                absolutePath = file.absolutePath,
                createdAt = file.lastModified(),
                sizeBytes = file.length()
            )
        }
    }

    suspend fun exportBackupToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val content = buildBackupJson()
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: throw InventoryValidationException("No se pudo abrir la ubicación de destino para exportar.")
            outputStream.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }

    suspend fun restoreFromLocalFile(filePath: String): Result<BackupSummaryStats> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                throw InventoryValidationException("El archivo de copia de seguridad no existe o no se puede leer.")
            }
            val raw = file.readText(Charsets.UTF_8)
            restoreFromJsonString(raw).getOrThrow()
        }
    }

    suspend fun restoreFromUri(uri: Uri): Result<BackupSummaryStats> = withContext(Dispatchers.IO) {
        runCatching {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw InventoryValidationException("No se pudo leer el archivo seleccionado.")
            val raw = inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            restoreFromJsonString(raw).getOrThrow()
        }
    }

    suspend fun validateBackupJson(rawJson: String): Result<BackupEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            if (rawJson.isBlank()) {
                throw InventoryValidationException("El archivo de respaldo está vacío.")
            }
            val envelope = try {
                json.decodeFromString<BackupEnvelope>(rawJson)
            } catch (e: Exception) {
                throw InventoryValidationException("Formato de copia de seguridad inválido o corrupto: ${e.message}")
            }

            if (envelope.format != "MI_INVENTARIO_BACKUP") {
                throw InventoryValidationException("El archivo no corresponde a una copia de seguridad de Mi Inventario.")
            }
            if (envelope.schemaVersion < 1 || envelope.schemaVersion > BackupEnvelope.CURRENT_SCHEMA_VERSION) {
                throw InventoryValidationException("Versión de copia de seguridad no compatible (v${envelope.schemaVersion}).")
            }

            val expectedHash = cryptoManager.sha256(canonicalJson.encodeToString(envelope.payload))
            if (!expectedHash.equals(envelope.checksumSha256, ignoreCase = true)) {
                throw InventoryValidationException("La verificación de integridad (SHA-256) falló: el archivo fue alterado o está dañado.")
            }

            validateReferentialIntegrity(envelope.payload)
            envelope
        }
    }

    private fun validateReferentialIntegrity(payload: BackupPayload) {
        if (payload.areas.isEmpty()) {
            throw InventoryValidationException("La copia de seguridad no contiene las áreas del sistema.")
        }
        val areaIds = payload.areas.map { it.id }.toSet()
        val productIds = payload.products.map { it.id }.toSet()
        val presentationIds = payload.presentations.map { it.id }.toSet()
        val sessionIds = payload.initialInventories.map { it.id }.toSet()
        val movementIds = payload.movements.map { it.id }.toSet()

        if (payload.productCodes.any { it.productId !in productIds }) {
            throw InventoryValidationException("Integridad inválida: existen códigos asociados a productos inexistentes.")
        }
        if (payload.presentations.any { it.productId !in productIds }) {
            throw InventoryValidationException("Integridad inválida: existen presentaciones sin producto válido.")
        }
        if (payload.equivalences.any { it.productId !in productIds || it.presentationId !in presentationIds || it.baseUnitsPerPresentation <= 0.0 }) {
            throw InventoryValidationException("Integridad inválida: existen equivalencias inconsistentes.")
        }
        if (payload.areaStocks.any { it.productId !in productIds || it.areaId !in areaIds || it.quantityBase < -1e-6 }) {
            throw InventoryValidationException("Integridad inválida: existen existencias negativas o referencias dañadas.")
        }
        if (payload.movements.any {
                it.productId !in productIds ||
                    (it.sourceAreaId != null && it.sourceAreaId !in areaIds) ||
                    (it.destinationAreaId != null && it.destinationAreaId !in areaIds)
            }
        ) {
            throw InventoryValidationException("Integridad inválida: existen movimientos con referencias dañadas.")
        }
        if (payload.initialInventories.any { it.areaId !in areaIds }) {
            throw InventoryValidationException("Integridad inválida: existen inventarios iniciales con áreas desconocidas.")
        }
        if (payload.initialInventoryItems.any { it.initialInventoryId !in sessionIds || it.productId !in productIds }) {
            throw InventoryValidationException("Integridad inválida: existen conteos iniciales huérfanos.")
        }
        if (payload.adjustments.any { it.productId !in productIds || it.areaId !in areaIds || it.movementId !in movementIds }) {
            throw InventoryValidationException("Integridad inválida: existen ajustes sin movimiento válido.")
        }
    }

    suspend fun restoreFromJsonString(rawJson: String): Result<BackupSummaryStats> = withContext(Dispatchers.IO) {
        runCatching {
            val envelope = validateBackupJson(rawJson).getOrThrow()
            val payload = envelope.payload

            database.withTransaction {
                database.clearAllInventoryTablesForRestore()

                database.areaDao().upsertAll(
                    payload.areas.map {
                        AreaEntity(it.id, it.code, it.name, it.description, it.colorHex, it.sortOrder)
                    }
                )
                database.productDao().upsertProducts(
                    payload.products.map {
                        ProductEntity(
                            id = it.id,
                            internalCode = it.internalCode,
                            name = it.name,
                            description = it.description,
                            category = it.category,
                            baseUnit = it.baseUnit,
                            allowsDecimals = it.allowsDecimals,
                            allowFractionalPresentations = it.allowFractionalPresentations,
                            defaultMinStock = it.defaultMinStock,
                            preferredAreaId = it.preferredAreaId,
                            isActive = it.isActive,
                            createdAt = it.createdAt,
                            updatedAt = it.updatedAt
                        )
                    }
                )
                database.productDao().insertProductCodes(
                    payload.productCodes.map {
                        ProductCodeEntity(it.id, it.productId, it.code, it.isPrimary, it.label)
                    }
                )
                database.productDao().upsertPresentations(
                    payload.presentations.map {
                        PresentationEntity(it.id, it.productId, it.name, it.isBase, it.allowsFractions, it.isActive)
                    }
                )
                database.productDao().upsertEquivalences(
                    payload.equivalences.map {
                        EquivalenceEntity(it.id, it.productId, it.presentationId, it.baseUnitsPerPresentation)
                    }
                )
                database.stockDao().upsertStocks(
                    payload.areaStocks.map {
                        AreaStockEntity(it.id, it.productId, it.areaId, it.quantityBase, it.minStockBase, it.updatedAt)
                    }
                )
                database.movementDao().upsertMovements(
                    payload.movements.map {
                        MovementEntity(
                            id = it.id,
                            referenceKey = it.referenceKey,
                            productId = it.productId,
                            movementType = it.movementType,
                            quantityInput = it.quantityInput,
                            presentationName = it.presentationName,
                            conversionFactor = it.conversionFactor,
                            quantityBase = it.quantityBase,
                            sourceAreaId = it.sourceAreaId,
                            destinationAreaId = it.destinationAreaId,
                            notes = it.notes,
                            initialInventoryId = it.initialInventoryId,
                            timestamp = it.timestamp
                        )
                    }
                )
                database.initialInventoryDao().upsertSessions(
                    payload.initialInventories.map {
                        InitialInventoryEntity(it.id, it.areaId, it.openedAt, it.closedAt, it.status, it.notes)
                    }
                )
                database.initialInventoryDao().upsertItems(
                    payload.initialInventoryItems.map {
                        InitialInventoryItemEntity(
                            id = it.id,
                            initialInventoryId = it.initialInventoryId,
                            productId = it.productId,
                            countedQuantityInput = it.countedQuantityInput,
                            presentationName = it.presentationName,
                            conversionFactor = it.conversionFactor,
                            countedQuantityBase = it.countedQuantityBase,
                            updatedAt = it.updatedAt
                        )
                    }
                )
                database.adjustmentDao().upsertAdjustments(
                    payload.adjustments.map {
                        AdjustmentEntity(
                            id = it.id,
                            productId = it.productId,
                            areaId = it.areaId,
                            previousQuantityBase = it.previousQuantityBase,
                            newQuantityBase = it.newQuantityBase,
                            deltaBase = it.deltaBase,
                            reason = it.reason,
                            movementId = it.movementId,
                            timestamp = it.timestamp
                        )
                    }
                )
                // Restore configs; if an encrypted config cannot be decrypted on this device (e.g. different Keystore),
                // omit the unreadable PIN blob so the admin isn't permanently locked out.
                val validConfigs = payload.configs.mapNotNull { cfg ->
                    if (cfg.isEncrypted && cryptoManager.decrypt(cfg.value) == null) {
                        null
                    } else {
                        AppConfigEntity(cfg.key, cfg.value, cfg.isEncrypted, cfg.updatedAt)
                    }
                }
                database.configDao().upsertConfigs(validConfigs)
                database.syncDao().upsertQueueItems(
                    payload.syncQueue.map {
                        SyncQueueEntity(
                            id = it.id,
                            idempotencyKey = it.idempotencyKey,
                            entityType = it.entityType,
                            entityKey = it.entityKey,
                            entityId = it.entityId,
                            payloadJson = it.payloadJson,
                            entityUpdatedAt = it.entityUpdatedAt,
                            status = it.status,
                            retryCount = it.retryCount,
                            lastError = it.lastError,
                            createdAt = it.createdAt,
                            syncedAt = it.syncedAt
                        )
                    }
                )
                database.syncDao().upsertLedgers(
                    payload.syncLedger.map {
                        SyncLedgerEntity(
                            idempotencyKey = it.idempotencyKey,
                            entityType = it.entityType,
                            entityKey = it.entityKey,
                            entityUpdatedAt = it.entityUpdatedAt,
                            payloadJson = it.payloadJson,
                            syncedAt = it.syncedAt
                        )
                    }
                )
                database.syncDao().upsertConflicts(
                    payload.syncConflicts.map {
                        SyncConflictEntity(
                            id = it.id,
                            queueItemId = it.queueItemId,
                            entityType = it.entityType,
                            entityKey = it.entityKey,
                            localPayloadJson = it.localPayloadJson,
                            remotePayloadJson = it.remotePayloadJson,
                            localTimestamp = it.localTimestamp,
                            remoteTimestamp = it.remoteTimestamp,
                            reason = it.reason,
                            resolutionStatus = it.resolutionStatus,
                            createdAt = it.createdAt,
                            resolvedAt = it.resolvedAt
                        )
                    }
                )
            }

            BackupSummaryStats(
                productsCount = payload.products.size,
                stocksCount = payload.areaStocks.size,
                movementsCount = payload.movements.size,
                initialInventoriesCount = payload.initialInventories.size
            )
        }
    }
}

data class BackupSummaryStats(
    val productsCount: Int,
    val stocksCount: Int,
    val movementsCount: Int,
    val initialInventoriesCount: Int
)
