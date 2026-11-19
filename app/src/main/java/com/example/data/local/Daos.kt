package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AreaDao {
    @Query("SELECT * FROM areas ORDER BY sortOrder ASC")
    fun observeAreas(): Flow<List<AreaEntity>>

    @Query("SELECT * FROM areas ORDER BY sortOrder ASC")
    suspend fun getAllAreas(): List<AreaEntity>

    @Query("SELECT * FROM areas WHERE id = :id")
    suspend fun getAreaById(id: Long): AreaEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(areas: List<AreaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(areas: List<AreaEntity>)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun observeAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY id ASC")
    suspend fun getAllProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :productId")
    suspend fun getProductById(productId: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE internalCode = :internalCode LIMIT 1")
    suspend fun getProductByInternalCode(internalCode: String): ProductEntity?

    @Insert
    suspend fun insertProduct(product: ProductEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(products: List<ProductEntity>)

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("SELECT * FROM product_codes")
    fun observeAllProductCodes(): Flow<List<ProductCodeEntity>>

    @Query("SELECT * FROM product_codes")
    suspend fun getAllProductCodes(): List<ProductCodeEntity>

    @Query("SELECT * FROM product_codes WHERE productId = :productId ORDER BY isPrimary DESC, id ASC")
    suspend fun getCodesForProduct(productId: Long): List<ProductCodeEntity>

    @Query("SELECT * FROM product_codes WHERE code = :code")
    suspend fun getEntriesByCode(code: String): List<ProductCodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductCodes(codes: List<ProductCodeEntity>)

    @Query("DELETE FROM product_codes WHERE productId = :productId")
    suspend fun deleteCodesForProduct(productId: Long)

    @Query("SELECT * FROM presentations")
    fun observeAllPresentations(): Flow<List<PresentationEntity>>

    @Query("SELECT * FROM presentations")
    suspend fun getAllPresentations(): List<PresentationEntity>

    @Query("SELECT * FROM presentations WHERE productId = :productId ORDER BY isBase DESC, id ASC")
    suspend fun getPresentationsForProduct(productId: Long): List<PresentationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPresentation(presentation: PresentationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPresentations(presentations: List<PresentationEntity>)

    @Update
    suspend fun updatePresentation(presentation: PresentationEntity)

    @Query("DELETE FROM presentations WHERE id = :presentationId AND isBase = 0")
    suspend fun deleteNonBasePresentation(presentationId: Long)

    @Query("SELECT * FROM equivalences")
    fun observeAllEquivalences(): Flow<List<EquivalenceEntity>>

    @Query("SELECT * FROM equivalences")
    suspend fun getAllEquivalences(): List<EquivalenceEntity>

    @Query("SELECT * FROM equivalences WHERE productId = :productId")
    suspend fun getEquivalencesForProduct(productId: Long): List<EquivalenceEntity>

    @Query("SELECT * FROM equivalences WHERE presentationId = :presentationId LIMIT 1")
    suspend fun getEquivalenceForPresentation(presentationId: Long): EquivalenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEquivalence(equivalence: EquivalenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEquivalences(equivalences: List<EquivalenceEntity>)
}

@Dao
interface StockDao {
    @Query("SELECT * FROM area_stocks")
    fun observeAllStocks(): Flow<List<AreaStockEntity>>

    @Query("SELECT * FROM area_stocks ORDER BY id ASC")
    suspend fun getAllStocks(): List<AreaStockEntity>

    @Query("SELECT * FROM area_stocks WHERE productId = :productId AND areaId = :areaId LIMIT 1")
    suspend fun getStock(productId: Long, areaId: Long): AreaStockEntity?

    @Query("SELECT * FROM area_stocks WHERE productId = :productId")
    suspend fun getStocksForProduct(productId: Long): List<AreaStockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(stock: AreaStockEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStocks(stocks: List<AreaStockEntity>)
}

@Dao
interface MovementDao {
    @Query("SELECT * FROM movements ORDER BY timestamp DESC, id DESC")
    fun observeAllMovements(): Flow<List<MovementEntity>>

    @Query("SELECT * FROM movements ORDER BY id ASC")
    suspend fun getAllMovements(): List<MovementEntity>

    @Query("SELECT * FROM movements WHERE referenceKey = :referenceKey LIMIT 1")
    suspend fun getByReferenceKey(referenceKey: String): MovementEntity?

    @Query(
        "SELECT * FROM movements WHERE productId = :productId AND movementType = :movementType " +
            "AND quantityBase = :quantityBase AND COALESCE(sourceAreaId, -1) = COALESCE(:sourceAreaId, -1) " +
            "AND COALESCE(destinationAreaId, -1) = COALESCE(:destinationAreaId, -1) " +
            "AND timestamp >= :sinceTimestamp LIMIT 1"
    )
    suspend fun findRecentDuplicate(
        productId: Long,
        movementType: String,
        quantityBase: Double,
        sourceAreaId: Long?,
        destinationAreaId: Long?,
        sinceTimestamp: Long
    ): MovementEntity?

    @Insert
    suspend fun insertMovement(movement: MovementEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovements(movements: List<MovementEntity>)
}

@Dao
interface InitialInventoryDao {
    @Query("SELECT * FROM initial_inventories ORDER BY openedAt DESC")
    fun observeAllSessions(): Flow<List<InitialInventoryEntity>>

    @Query("SELECT * FROM initial_inventories ORDER BY id ASC")
    suspend fun getAllSessions(): List<InitialInventoryEntity>

    @Query("SELECT * FROM initial_inventories WHERE id = :id")
    suspend fun getSessionById(id: Long): InitialInventoryEntity?

    @Query("SELECT * FROM initial_inventories WHERE areaId = :areaId AND status = 'ABIERTO' LIMIT 1")
    suspend fun getOpenSessionForArea(areaId: Long): InitialInventoryEntity?

    @Insert
    suspend fun insertSession(session: InitialInventoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<InitialInventoryEntity>)

    @Update
    suspend fun updateSession(session: InitialInventoryEntity)

    @Query("SELECT * FROM initial_inventory_items ORDER BY updatedAt DESC")
    fun observeAllItems(): Flow<List<InitialInventoryItemEntity>>

    @Query("SELECT * FROM initial_inventory_items ORDER BY id ASC")
    suspend fun getAllItems(): List<InitialInventoryItemEntity>

    @Query("SELECT * FROM initial_inventory_items WHERE initialInventoryId = :sessionId ORDER BY updatedAt DESC")
    suspend fun getItemsForSession(sessionId: Long): List<InitialInventoryItemEntity>

    @Query("SELECT * FROM initial_inventory_items WHERE initialInventoryId = :sessionId AND productId = :productId LIMIT 1")
    suspend fun getItem(sessionId: Long, productId: Long): InitialInventoryItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: InitialInventoryItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<InitialInventoryItemEntity>)

    @Query("DELETE FROM initial_inventory_items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: Long)
}

@Dao
interface AdjustmentDao {
    @Query("SELECT * FROM adjustments ORDER BY timestamp DESC")
    fun observeAllAdjustments(): Flow<List<AdjustmentEntity>>

    @Query("SELECT * FROM adjustments ORDER BY id ASC")
    suspend fun getAllAdjustments(): List<AdjustmentEntity>

    @Insert
    suspend fun insertAdjustment(adjustment: AdjustmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAdjustments(adjustments: List<AdjustmentEntity>)
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM app_config")
    fun observeAllConfig(): Flow<List<AppConfigEntity>>

    @Query("SELECT * FROM app_config ORDER BY key ASC")
    suspend fun getAllConfig(): List<AppConfigEntity>

    @Query("SELECT * FROM app_config WHERE key = :key LIMIT 1")
    suspend fun getConfig(key: String): AppConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConfig(config: AppConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfigs(configs: List<AppConfigEntity>)

    @Query("DELETE FROM app_config WHERE key = :key")
    suspend fun deleteConfig(key: String)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC, id ASC")
    fun observeAllQueue(): Flow<List<SyncQueueEntity>>

    @Query("SELECT * FROM sync_queue ORDER BY id ASC")
    suspend fun getAllQueue(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAt ASC, id ASC")
    suspend fun getPendingQueueItems(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getQueueItemByKey(key: String): SyncQueueEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQueueItem(item: SyncQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueueItems(items: List<SyncQueueEntity>)

    @Update
    suspend fun updateQueueItem(item: SyncQueueEntity)

    @Query("SELECT * FROM sync_ledger WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getLedgerByKey(key: String): SyncLedgerEntity?

    @Query("SELECT * FROM sync_ledger WHERE entityType = :entityType AND entityKey = :entityKey ORDER BY entityUpdatedAt DESC LIMIT 1")
    suspend fun getLatestLedgerForEntity(entityType: String, entityKey: String): SyncLedgerEntity?

    @Query("SELECT * FROM sync_ledger ORDER BY syncedAt DESC")
    suspend fun getAllLedger(): List<SyncLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLedger(entry: SyncLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLedgers(entries: List<SyncLedgerEntity>)

    @Query("SELECT * FROM sync_conflicts WHERE resolutionStatus = 'OPEN' ORDER BY createdAt DESC")
    fun observeOpenConflicts(): Flow<List<SyncConflictEntity>>

    @Query("SELECT * FROM sync_conflicts ORDER BY id ASC")
    suspend fun getAllConflicts(): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id LIMIT 1")
    suspend fun getConflictById(id: Long): SyncConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConflict(conflict: SyncConflictEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflicts(conflicts: List<SyncConflictEntity>)

    @Update
    suspend fun updateConflict(conflict: SyncConflictEntity)
}
