package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "areas",
    indices = [Index(value = ["code"], unique = true)]
)
data class AreaEntity(
    @PrimaryKey val id: Long,
    val code: String,
    val name: String,
    val description: String,
    val colorHex: Long,
    val sortOrder: Int
)

@Entity(
    tableName = "products",
    indices = [Index(value = ["internalCode"], unique = true)]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val internalCode: String,
    val name: String,
    val description: String = "",
    val category: String,
    val baseUnit: String,
    val allowsDecimals: Boolean = false,
    val allowFractionalPresentations: Boolean = false,
    val defaultMinStock: Double? = null,
    val preferredAreaId: Long = 1L,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "product_codes",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["code"]),
        Index(value = ["productId", "code"], unique = true)
    ]
)
data class ProductCodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Long,
    val code: String,
    val isPrimary: Boolean,
    val label: String
)

@Entity(
    tableName = "presentations",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["productId", "name"], unique = true)
    ]
)
data class PresentationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Long,
    val name: String,
    val isBase: Boolean,
    val allowsFractions: Boolean,
    val isActive: Boolean = true
)

@Entity(
    tableName = "equivalences",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PresentationEntity::class,
            parentColumns = ["id"],
            childColumns = ["presentationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["presentationId"], unique = true)
    ]
)
data class EquivalenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Long,
    val presentationId: Long,
    val baseUnitsPerPresentation: Double
)

@Entity(
    tableName = "area_stocks",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AreaEntity::class,
            parentColumns = ["id"],
            childColumns = ["areaId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["areaId"]),
        Index(value = ["productId", "areaId"], unique = true)
    ]
)
data class AreaStockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Long,
    val areaId: Long,
    val quantityBase: Double,
    val minStockBase: Double? = null,
    val updatedAt: Long
)

@Entity(
    tableName = "movements",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AreaEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceAreaId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AreaEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationAreaId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["sourceAreaId"]),
        Index(value = ["destinationAreaId"]),
        Index(value = ["timestamp"]),
        Index(value = ["referenceKey"], unique = true)
    ]
)
data class MovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val referenceKey: String,
    val productId: Long,
    val movementType: String,
    val quantityInput: Double,
    val presentationName: String,
    val conversionFactor: Double,
    val quantityBase: Double,
    val sourceAreaId: Long? = null,
    val destinationAreaId: Long? = null,
    val notes: String = "",
    val initialInventoryId: Long? = null,
    val timestamp: Long
)

@Entity(
    tableName = "initial_inventories",
    foreignKeys = [
        ForeignKey(
            entity = AreaEntity::class,
            parentColumns = ["id"],
            childColumns = ["areaId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["areaId"]), Index(value = ["openedAt"])]
)
data class InitialInventoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val areaId: Long,
    val openedAt: Long,
    val closedAt: Long? = null,
    val status: String,
    val notes: String = ""
)

@Entity(
    tableName = "initial_inventory_items",
    foreignKeys = [
        ForeignKey(
            entity = InitialInventoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["initialInventoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["initialInventoryId"]),
        Index(value = ["productId"]),
        Index(value = ["initialInventoryId", "productId"], unique = true)
    ]
)
data class InitialInventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val initialInventoryId: Long,
    val productId: Long,
    val countedQuantityInput: Double,
    val presentationName: String,
    val conversionFactor: Double,
    val countedQuantityBase: Double,
    val updatedAt: Long
)

@Entity(
    tableName = "adjustments",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AreaEntity::class,
            parentColumns = ["id"],
            childColumns = ["areaId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["movementId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["areaId"]),
        Index(value = ["movementId"])
    ]
)
data class AdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Long,
    val areaId: Long,
    val previousQuantityBase: Double,
    val newQuantityBase: Double,
    val deltaBase: Double,
    val reason: String,
    val movementId: Long,
    val timestamp: Long
)

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val key: String,
    val value: String,
    val isEncrypted: Boolean = false,
    val updatedAt: Long
)

@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val idempotencyKey: String,
    val entityType: String,
    val entityKey: String,
    val entityId: Long,
    val payloadJson: String,
    val entityUpdatedAt: Long,
    val status: String = "PENDING",
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val syncedAt: Long? = null
)

@Entity(
    tableName = "sync_ledger",
    indices = [Index(value = ["entityKey"])]
)
data class SyncLedgerEntity(
    @PrimaryKey val idempotencyKey: String,
    val entityType: String,
    val entityKey: String,
    val entityUpdatedAt: Long,
    val payloadJson: String,
    val syncedAt: Long
)

@Entity(
    tableName = "sync_conflicts",
    indices = [
        Index(value = ["resolutionStatus"]),
        Index(value = ["createdAt"])
    ]
)
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val queueItemId: Long,
    val entityType: String,
    val entityKey: String,
    val localPayloadJson: String,
    val remotePayloadJson: String,
    val localTimestamp: Long,
    val remoteTimestamp: Long,
    val reason: String,
    val resolutionStatus: String = "OPEN",
    val createdAt: Long,
    val resolvedAt: Long? = null
)
