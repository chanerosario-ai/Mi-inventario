package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.AreaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        AreaEntity::class,
        ProductEntity::class,
        ProductCodeEntity::class,
        PresentationEntity::class,
        EquivalenceEntity::class,
        AreaStockEntity::class,
        MovementEntity::class,
        InitialInventoryEntity::class,
        InitialInventoryItemEntity::class,
        AdjustmentEntity::class,
        AppConfigEntity::class,
        SyncQueueEntity::class,
        SyncLedgerEntity::class,
        SyncConflictEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun areaDao(): AreaDao
    abstract fun productDao(): ProductDao
    abstract fun stockDao(): StockDao
    abstract fun movementDao(): MovementDao
    abstract fun initialInventoryDao(): InitialInventoryDao
    abstract fun adjustmentDao(): AdjustmentDao
    abstract fun configDao(): ConfigDao
    abstract fun syncDao(): SyncDao

    suspend fun clearAllInventoryTablesForRestore() {
        openHelper.writableDatabase.apply {
            execSQL("PRAGMA foreign_keys = OFF")
            try {
                execSQL("DELETE FROM adjustments")
                execSQL("DELETE FROM initial_inventory_items")
                execSQL("DELETE FROM initial_inventories")
                execSQL("DELETE FROM movements")
                execSQL("DELETE FROM area_stocks")
                execSQL("DELETE FROM equivalences")
                execSQL("DELETE FROM presentations")
                execSQL("DELETE FROM product_codes")
                execSQL("DELETE FROM products")
                execSQL("DELETE FROM areas")
                execSQL("DELETE FROM app_config")
                execSQL("DELETE FROM sync_conflicts")
                execSQL("DELETE FROM sync_ledger")
                execSQL("DELETE FROM sync_queue")
            } finally {
                execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun defaultAreas(): List<AreaEntity> = listOf(
            AreaEntity(
                id = AreaId.ALMACEN_GENERAL.id,
                code = AreaId.ALMACEN_GENERAL.code,
                name = AreaId.ALMACEN_GENERAL.displayName,
                description = "Resguardo principal de insumos y bebidas",
                colorHex = AreaId.ALMACEN_GENERAL.colorHex,
                sortOrder = 1
            ),
            AreaEntity(
                id = AreaId.DESPENSA.id,
                code = AreaId.DESPENSA.code,
                name = AreaId.DESPENSA.displayName,
                description = "Existencias operativas para cocina y servicio",
                colorHex = AreaId.DESPENSA.colorHex,
                sortOrder = 2
            ),
            AreaEntity(
                id = AreaId.ALMACEN_LICORES.id,
                code = AreaId.ALMACEN_LICORES.code,
                name = AreaId.ALMACEN_LICORES.displayName,
                description = "Bodega cerrada de destilados, vinos y licores",
                colorHex = AreaId.ALMACEN_LICORES.colorHex,
                sortOrder = 3
            ),
            AreaEntity(
                id = AreaId.BAR.id,
                code = AreaId.BAR.code,
                name = AreaId.BAR.displayName,
                description = "Punto de servicio y despacho en barra",
                colorHex = AreaId.BAR.colorHex,
                sortOrder = 4
            )
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mi_inventario.db"
                )
                    .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.areaDao()?.insertAll(defaultAreas())
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
