package com.example.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupEnvelope(
    val format: String = "MI_INVENTARIO_BACKUP",
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String = "1.0",
    val createdAt: Long,
    val checksumSha256: String,
    val payload: BackupPayload
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

@Serializable
data class BackupPayload(
    val areas: List<BackupArea>,
    val products: List<BackupProduct>,
    val productCodes: List<BackupProductCode>,
    val presentations: List<BackupPresentation>,
    val equivalences: List<BackupEquivalence>,
    val areaStocks: List<BackupAreaStock>,
    val movements: List<BackupMovement>,
    val initialInventories: List<BackupInitialInventory>,
    val initialInventoryItems: List<BackupInitialInventoryItem>,
    val adjustments: List<BackupAdjustment>,
    val configs: List<BackupConfig>,
    val syncQueue: List<BackupSyncQueueItem> = emptyList(),
    val syncLedger: List<BackupSyncLedgerItem> = emptyList(),
    val syncConflicts: List<BackupSyncConflictItem> = emptyList()
)

@Serializable
data class BackupArea(
    val id: Long,
    val code: String,
    val name: String,
    val description: String,
    val colorHex: Long,
    val sortOrder: Int
)

@Serializable
data class BackupProduct(
    val id: Long,
    val internalCode: String,
    val name: String,
    val description: String,
    val category: String,
    val baseUnit: String,
    val allowsDecimals: Boolean,
    val allowFractionalPresentations: Boolean,
    val defaultMinStock: Double? = null,
    val preferredAreaId: Long = 1L,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BackupProductCode(
    val id: Long,
    val productId: Long,
    val code: String,
    val isPrimary: Boolean,
    val label: String
)

@Serializable
data class BackupPresentation(
    val id: Long,
    val productId: Long,
    val name: String,
    val isBase: Boolean,
    val allowsFractions: Boolean,
    val isActive: Boolean
)

@Serializable
data class BackupEquivalence(
    val id: Long,
    val productId: Long,
    val presentationId: Long,
    val baseUnitsPerPresentation: Double
)

@Serializable
data class BackupAreaStock(
    val id: Long,
    val productId: Long,
    val areaId: Long,
    val quantityBase: Double,
    val minStockBase: Double? = null,
    val updatedAt: Long
)

@Serializable
data class BackupMovement(
    val id: Long,
    val referenceKey: String,
    val productId: Long,
    val movementType: String,
    val quantityInput: Double,
    val presentationName: String,
    val conversionFactor: Double,
    val quantityBase: Double,
    val sourceAreaId: Long? = null,
    val destinationAreaId: Long? = null,
    val notes: String,
    val initialInventoryId: Long? = null,
    val timestamp: Long
)

@Serializable
data class BackupInitialInventory(
    val id: Long,
    val areaId: Long,
    val openedAt: Long,
    val closedAt: Long? = null,
    val status: String,
    val notes: String
)

@Serializable
data class BackupInitialInventoryItem(
    val id: Long,
    val initialInventoryId: Long,
    val productId: Long,
    val countedQuantityInput: Double,
    val presentationName: String,
    val conversionFactor: Double,
    val countedQuantityBase: Double,
    val updatedAt: Long
)

@Serializable
data class BackupAdjustment(
    val id: Long,
    val productId: Long,
    val areaId: Long,
    val previousQuantityBase: Double,
    val newQuantityBase: Double,
    val deltaBase: Double,
    val reason: String,
    val movementId: Long,
    val timestamp: Long
)

@Serializable
data class BackupConfig(
    val key: String,
    val value: String,
    val isEncrypted: Boolean,
    val updatedAt: Long
)

@Serializable
data class BackupSyncQueueItem(
    val id: Long,
    val idempotencyKey: String,
    val entityType: String,
    val entityKey: String,
    val entityId: Long,
    val payloadJson: String,
    val entityUpdatedAt: Long,
    val status: String,
    val retryCount: Int,
    val lastError: String? = null,
    val createdAt: Long,
    val syncedAt: Long? = null
)

@Serializable
data class BackupSyncLedgerItem(
    val idempotencyKey: String,
    val entityType: String,
    val entityKey: String,
    val entityUpdatedAt: Long,
    val payloadJson: String,
    val syncedAt: Long
)

@Serializable
data class BackupSyncConflictItem(
    val id: Long,
    val queueItemId: Long,
    val entityType: String,
    val entityKey: String,
    val localPayloadJson: String,
    val remotePayloadJson: String,
    val localTimestamp: Long,
    val remoteTimestamp: Long,
    val reason: String,
    val resolutionStatus: String,
    val createdAt: Long,
    val resolvedAt: Long? = null
)

data class BackupFileInfo(
    val fileName: String,
    val absolutePath: String,
    val createdAt: Long,
    val sizeBytes: Long
)
