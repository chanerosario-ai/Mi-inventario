package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import com.example.data.local.AppDatabase
import com.example.data.local.DatabaseMigrations
import com.example.data.local.SyncQueueEntity
import com.example.data.repository.InventoryRepository
import com.example.data.sync.ConnectivityObserver
import com.example.data.sync.SyncEngine
import com.example.domain.AreaId
import com.example.domain.BaseUnit
import com.example.domain.MovementType
import com.example.domain.SyncVisualStatus
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActualizacionOfflineSyncRobolectricTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var database: AppDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var repository: InventoryRepository
    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "test_offline_sync_${System.nanoTime()}.db")
        database = buildDb(dbFile)
        crypto = KeystoreCryptoManager()
        repository = InventoryRepository(database, crypto)
        connectivityObserver = ConnectivityObserver(context)
        connectivityObserver.setTestOnlineState(true)
        syncEngine = SyncEngine(database, connectivityObserver, crypto)
        repository.ensureInitialized()
    }

    private fun buildDb(file: File): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() {
        syncEngine.shutdown()
        connectivityObserver.unregister()
        database.close()
        dbFile.delete()
    }

    @Test
    fun `1 - Registro manual sin codigo de barras, codigo interno editable, area elegida y edicion posterior`() = runBlocking {
        val productId = repository.registerProduct(
            name = "Hielo en Cubos Artesanal",
            primaryCode = "", // Sin código de barras
            additionalCodes = emptyList(),
            description = "Bolsa de 5 kg para servicio de bar",
            category = "Insumos",
            baseUnit = BaseUnit.KILOGRAMO,
            customInternalCode = "BAR-HIELO-01",
            preferredAreaId = AreaId.BAR.id,
            initialQuantityInput = 25.0
        ).getOrThrow()

        val created = repository.getProductSummary(productId)!!
        assertEquals("Hielo en Cubos Artesanal", created.name)
        assertEquals("BAR-HIELO-01", created.internalCode)
        assertNull(created.primaryBarcode)
        assertTrue(created.allCodes.isEmpty())
        assertEquals(AreaId.BAR.id, created.preferredAreaId)
        assertEquals("Bolsa de 5 kg para servicio de bar", created.description)

        val barStock = created.stocksByArea.first { it.areaId == AreaId.BAR.id }.quantityBase
        assertEquals(25.0, barStock, 0.001)

        // También debe poder buscarse por código interno
        val byInternal = repository.findProductByCode("BAR-HIELO-01")
        assertNotNull(byInternal)
        assertEquals(productId, byInternal?.productId)

        // Editar nombre, código interno, descripción y área sin perder existencias ni historial
        repository.updateProduct(
            productId = productId,
            name = "Hielo Purificado Premium",
            primaryCode = "",
            additionalCodes = emptyList(),
            description = "Actualizado para cocina y bar",
            category = "Insumos",
            allowsDecimals = true,
            allowFractionalPresentations = false,
            defaultMinStock = 10.0,
            isActive = true,
            customInternalCode = "DESP-HIELO-02",
            preferredAreaId = AreaId.DESPENSA.id
        ).getOrThrow()

        val updated = repository.getProductSummary(productId)!!
        assertEquals("Hielo Purificado Premium", updated.name)
        assertEquals("DESP-HIELO-02", updated.internalCode)
        assertEquals(AreaId.DESPENSA.id, updated.preferredAreaId)
        assertEquals(25.0, updated.stocksByArea.first { it.areaId == AreaId.BAR.id }.quantityBase, 0.001)
    }

    @Test
    fun `2, 3, 4 y 5 - Trabajo completo sin internet, persistencia al reiniciar y sincronizacion automatica al reconectar`() = runBlocking {
        // Simular modo sin conexión antes de operar
        connectivityObserver.setTestOnlineState(false)
        delay(80)

        val statusOfflineInitial = syncEngine.syncStatusFlow.first()
        assertEquals(SyncVisualStatus.SIN_CONEXION, statusOfflineInitial.visualStatus)

        // Registrar producto manualmente sin internet y sin código de barras
        val productId = repository.registerProduct(
            name = "Jugo de Limón Natural",
            primaryCode = "",
            description = "Exprimido diario",
            category = "Bebidas",
            baseUnit = BaseUnit.LITRO,
            customInternalCode = "DESP-LIMON-01",
            preferredAreaId = AreaId.DESPENSA.id
        ).getOrThrow()

        // Entradas y salidas en áreas independientes sin internet
        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.DESPENSA.id,
            quantityInput = 12.0,
            notes = "Compra matutina sin red"
        ).getOrThrow()

        repository.registrarSalida(
            productId = productId,
            sourceAreaId = AreaId.DESPENSA.id,
            quantityInput = 4.5,
            notes = "Consumo barra sin red"
        ).getOrThrow()

        // Consultar las 4 áreas y el historial sin internet
        val summaries = repository.areaSummariesFlow.first()
        assertEquals(4, summaries.size)
        val despensaSummary = summaries.first { it.areaId == AreaId.DESPENSA.id }
        assertEquals(7.5, despensaSummary.totalUnitsAvailable, 0.001)
        assertEquals(0.0, summaries.first { it.areaId == AreaId.ALMACEN_GENERAL.id }.totalUnitsAvailable, 0.001)

        val history = repository.movementsFlow.first()
        assertEquals(2, history.size)
        assertTrue(history.any { it.movementType == MovementType.ENTRADA && it.quantityBase == 12.0 })
        assertTrue(history.any { it.movementType == MovementType.SALIDA && it.quantityBase == 4.5 })

        val pendingWhileOffline = syncEngine.syncStatusFlow.first()
        assertEquals(SyncVisualStatus.SIN_CONEXION, pendingWhileOffline.visualStatus)
        assertTrue("Debe haber cambios en cola local", pendingWhileOffline.pendingCount >= 3)

        // Simular cierre y reinicio completo de la app (cerrar BD y reabrir el archivo)
        connectivityObserver.unregister()
        database.close()

        val reopenedDb = buildDb(dbFile)
        val reopenedRepo = InventoryRepository(reopenedDb, crypto)
        val reopenedConn = ConnectivityObserver(context)
        reopenedConn.setTestOnlineState(false)
        val reopenedSync = SyncEngine(reopenedDb, reopenedConn, crypto)

        try {
            val persistedProduct = reopenedRepo.getProductSummary(productId)
            assertNotNull(persistedProduct)
            assertEquals("Jugo de Limón Natural", persistedProduct?.name)
            assertEquals(
                7.5,
                persistedProduct!!.stocksByArea.first { it.areaId == AreaId.DESPENSA.id }.quantityBase,
                0.001
            )
            assertEquals(2, reopenedRepo.movementsFlow.first().size)

            val statusAfterRestartOffline = reopenedSync.syncStatusFlow.first()
            assertEquals(SyncVisualStatus.SIN_CONEXION, statusAfterRestartOffline.visualStatus)
            assertTrue(statusAfterRestartOffline.pendingCount >= 3)

            // Recuperar conexión a internet -> sincronización automática (incluyendo debounce)
            reopenedConn.setTestOnlineState(true)
            var synced = false
            repeat(30) {
                delay(100)
                val st = reopenedSync.syncStatusFlow.first()
                if (st.visualStatus == SyncVisualStatus.SINCRONIZADO && st.pendingCount == 0) {
                    synced = true
                    return@repeat
                }
            }
            assertTrue("Al volver internet debe sincronizarse automáticamente", synced)

            val finalStatus = reopenedSync.syncStatusFlow.first()
            assertEquals(SyncVisualStatus.SINCRONIZADO, finalStatus.visualStatus)
            assertEquals(0, finalStatus.pendingCount)
            assertTrue(finalStatus.syncedCount >= 3)
        } finally {
            reopenedSync.shutdown()
            reopenedConn.unregister()
            reopenedDb.close()
        }
    }

    @Test
    fun `6 - Deduplicacion de productos y movimientos, y proteccion contra sobrescritura antigua con revision de conflicto`() = runBlocking {
        // Iniciar offline para controlar el momento exacto de la sincronización y evitar carrera con el observador automático
        connectivityObserver.setTestOnlineState(false)
        delay(80)

        // Registrar producto y movimiento offline
        val prodId = repository.registerProduct(
            name = "Tequila Reposado",
            primaryCode = "7501112223334",
            category = "Licores",
            baseUnit = BaseUnit.BOTELLA,
            customInternalCode = "LIC-TEQ-01",
            preferredAreaId = AreaId.ALMACEN_LICORES.id
        ).getOrThrow()

        repository.registrarEntrada(
            productId = prodId,
            destinationAreaId = AreaId.ALMACEN_LICORES.id,
            quantityInput = 10.0,
            notes = "Ingreso inicial"
        ).getOrThrow()

        // Habilitar red y sincronizar explícitamente (y esperar cualquier ciclo automático en curso)
        connectivityObserver.setTestOnlineState(true)
        delay(150)
        syncEngine.syncPendingChanges()
        val syncedAfterFirst = syncEngine.syncStatusFlow.first()
        assertEquals(SyncVisualStatus.SINCRONIZADO, syncedAfterFirst.visualStatus)
        assertEquals(0, syncedAfterFirst.pendingCount)
        assertEquals(2, syncedAfterFirst.syncedCount)

        connectivityObserver.setTestOnlineState(false)
        delay(80)

        val productQueueBefore = database.syncDao().getAllQueue().first { it.entityType == "PRODUCT" }
        val movQueueBefore = database.syncDao().getAllQueue().first { it.entityType == "MOVEMENT" }

        // A) Intentar re-encolar el mismo movimiento con distinta clave idempotencyKey -> debe deduplicarse sin duplicar en ledger
        database.syncDao().insertQueueItem(
            SyncQueueEntity(
                idempotencyKey = "MOV:${movQueueBefore.entityKey}:RETRY",
                entityType = "MOVEMENT",
                entityKey = movQueueBefore.entityKey,
                entityId = movQueueBefore.entityId,
                payloadJson = movQueueBefore.payloadJson,
                entityUpdatedAt = movQueueBefore.entityUpdatedAt + 100,
                status = "PENDING",
                createdAt = System.currentTimeMillis()
            )
        )
        connectivityObserver.setTestOnlineState(true)
        val dedupResult = syncEngine.syncPendingChanges()
        assertTrue(dedupResult.skippedDuplicateCount >= 1 || syncEngine.syncStatusFlow.first().syncedCount == 3)
        assertEquals(1, database.syncDao().getAllQueue().count { it.entityType == "MOVEMENT" && it.idempotencyKey == movQueueBefore.idempotencyKey })

        // B) Intentar sincronizar información antigua del producto (timestamp menor al último sincronizado) -> no debe sobrescribir, debe crear conflicto
        connectivityObserver.setTestOnlineState(false)
        delay(80)
        database.syncDao().insertQueueItem(
            SyncQueueEntity(
                idempotencyKey = "PRD:${productQueueBefore.entityKey}:STALE_UPDATE",
                entityType = "PRODUCT",
                entityKey = productQueueBefore.entityKey,
                entityId = prodId,
                payloadJson = """{"internalCode":"${productQueueBefore.entityKey}","name":"Nombre Antiguo"}""",
                entityUpdatedAt = productQueueBefore.entityUpdatedAt - 5000L, // Más antiguo!
                status = "PENDING",
                createdAt = System.currentTimeMillis()
            )
        )

        connectivityObserver.setTestOnlineState(true)
        delay(150)
        syncEngine.syncPendingChanges()

        val statusWithConflict = syncEngine.syncStatusFlow.first()
        assertEquals(SyncVisualStatus.CONFLICTO, statusWithConflict.visualStatus)
        assertEquals(1, statusWithConflict.openConflictsCount)

        val openConflicts = syncEngine.openConflictsFlow.first()
        assertEquals(1, openConflicts.size)

        // Resolver conflicto conservando los datos revisados
        syncEngine.resolveConflictMarkReviewed(openConflicts.first().id)
        val afterResolve = syncEngine.syncStatusFlow.first()
        assertEquals(0, afterResolve.openConflictsCount)
        assertEquals(SyncVisualStatus.SINCRONIZADO, afterResolve.visualStatus)

        // Verificar que los datos reales del inventario siguen intactos
        val stillIntact = repository.getProductSummary(prodId)!!
        assertEquals("Tequila Reposado", stillIntact.name)
        assertEquals(
            10.0,
            stillIntact.stocksByArea.first { it.areaId == AreaId.ALMACEN_LICORES.id }.quantityBase,
            0.001
        )
    }

    @Test
    fun `Migracion no destructiva v1 a v2 conserva inventario y movimientos existentes`() = runBlocking {
        val v1DbFile = File(context.cacheDir, "test_migration_v1_${System.nanoTime()}.db")
        // Crear manualmente una base SQLite con el esquema exacto v1 y datos existentes
        val sqlite = SQLiteDatabase.openOrCreateDatabase(v1DbFile, null)
        try {
            sqlite.execSQL("CREATE TABLE `areas` (`id` INTEGER NOT NULL, `code` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `colorHex` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_areas_code` ON `areas` (`code`)")
            sqlite.execSQL("CREATE TABLE `products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `internalCode` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `baseUnit` TEXT NOT NULL, `allowsDecimals` INTEGER NOT NULL, `allowFractionalPresentations` INTEGER NOT NULL, `defaultMinStock` REAL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_products_internalCode` ON `products` (`internalCode`)")
            sqlite.execSQL("CREATE TABLE `product_codes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `code` TEXT NOT NULL, `isPrimary` INTEGER NOT NULL, `label` TEXT NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            sqlite.execSQL("CREATE INDEX `index_product_codes_productId` ON `product_codes` (`productId`)")
            sqlite.execSQL("CREATE INDEX `index_product_codes_code` ON `product_codes` (`code`)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_product_codes_productId_code` ON `product_codes` (`productId`, `code`)")
            sqlite.execSQL("CREATE TABLE `presentations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `name` TEXT NOT NULL, `isBase` INTEGER NOT NULL, `allowsFractions` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            sqlite.execSQL("CREATE INDEX `index_presentations_productId` ON `presentations` (`productId`)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_presentations_productId_name` ON `presentations` (`productId`, `name`)")
            sqlite.execSQL("CREATE TABLE `equivalences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `presentationId` INTEGER NOT NULL, `baseUnitsPerPresentation` REAL NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`presentationId`) REFERENCES `presentations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            sqlite.execSQL("CREATE INDEX `index_equivalences_productId` ON `equivalences` (`productId`)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_equivalences_presentationId` ON `equivalences` (`presentationId`)")
            sqlite.execSQL("CREATE TABLE `area_stocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `areaId` INTEGER NOT NULL, `quantityBase` REAL NOT NULL, `minStockBase` REAL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`areaId`) REFERENCES `areas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            sqlite.execSQL("CREATE INDEX `index_area_stocks_productId` ON `area_stocks` (`productId`)")
            sqlite.execSQL("CREATE INDEX `index_area_stocks_areaId` ON `area_stocks` (`areaId`)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_area_stocks_productId_areaId` ON `area_stocks` (`productId`, `areaId`)")
            sqlite.execSQL("CREATE TABLE `movements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `referenceKey` TEXT NOT NULL, `productId` INTEGER NOT NULL, `movementType` TEXT NOT NULL, `quantityInput` REAL NOT NULL, `presentationName` TEXT NOT NULL, `conversionFactor` REAL NOT NULL, `quantityBase` REAL NOT NULL, `sourceAreaId` INTEGER, `destinationAreaId` INTEGER, `notes` TEXT NOT NULL, `initialInventoryId` INTEGER, `timestamp` INTEGER NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`sourceAreaId`) REFERENCES `areas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`destinationAreaId`) REFERENCES `areas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            sqlite.execSQL("CREATE INDEX `index_movements_productId` ON `movements` (`productId`)")
            sqlite.execSQL("CREATE INDEX `index_movements_sourceAreaId` ON `movements` (`sourceAreaId`)")
            sqlite.execSQL("CREATE INDEX `index_movements_destinationAreaId` ON `movements` (`destinationAreaId`)")
            sqlite.execSQL("CREATE INDEX `index_movements_timestamp` ON `movements` (`timestamp`)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_movements_referenceKey` ON `movements` (`referenceKey`)")
            sqlite.execSQL("CREATE TABLE `initial_inventories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `areaId` INTEGER NOT NULL, `openedAt` INTEGER NOT NULL, `closedAt` INTEGER, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, FOREIGN KEY(`areaId`) REFERENCES `areas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            sqlite.execSQL("CREATE INDEX `index_initial_inventories_areaId` ON `initial_inventories` (`areaId`)")
            sqlite.execSQL("CREATE INDEX `index_initial_inventories_openedAt` ON `initial_inventories` (`openedAt`)")
            sqlite.execSQL("CREATE TABLE `initial_inventory_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `initialInventoryId` INTEGER NOT NULL, `productId` INTEGER NOT NULL, `countedQuantityInput` REAL NOT NULL, `presentationName` TEXT NOT NULL, `conversionFactor` REAL NOT NULL, `countedQuantityBase` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`initialInventoryId`) REFERENCES `initial_inventories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            sqlite.execSQL("CREATE INDEX `index_initial_inventory_items_initialInventoryId` ON `initial_inventory_items` (`initialInventoryId`)")
            sqlite.execSQL("CREATE INDEX `index_initial_inventory_items_productId` ON `initial_inventory_items` (`productId`)")
            sqlite.execSQL("CREATE UNIQUE INDEX `index_initial_inventory_items_initialInventoryId_productId` ON `initial_inventory_items` (`initialInventoryId`, `productId`)")
            sqlite.execSQL("CREATE TABLE `adjustments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `areaId` INTEGER NOT NULL, `previousQuantityBase` REAL NOT NULL, `newQuantityBase` REAL NOT NULL, `deltaBase` REAL NOT NULL, `reason` TEXT NOT NULL, `movementId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`areaId`) REFERENCES `areas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`movementId`) REFERENCES `movements`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            sqlite.execSQL("CREATE INDEX `index_adjustments_productId` ON `adjustments` (`productId`)")
            sqlite.execSQL("CREATE INDEX `index_adjustments_areaId` ON `adjustments` (`areaId`)")
            sqlite.execSQL("CREATE INDEX `index_adjustments_movementId` ON `adjustments` (`movementId`)")
            sqlite.execSQL("CREATE TABLE `app_config` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `isEncrypted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")

            sqlite.execSQL("INSERT INTO areas VALUES (1, 'ALMACEN_GENERAL', 'Almacén general', '', 0, 1)")
            sqlite.execSQL("INSERT INTO areas VALUES (2, 'DESPENSA', 'Despensa', '', 0, 2)")
            sqlite.execSQL("INSERT INTO areas VALUES (3, 'ALMACEN_LICORES', 'Almacén de licores', '', 0, 3)")
            sqlite.execSQL("INSERT INTO areas VALUES (4, 'BAR', 'Bar', '', 0, 4)")
            sqlite.execSQL("INSERT INTO products VALUES (10, 'PRD-V1-001', 'Café Grano 1Kg', 'Tueste medio', 'Alimentos', 'kilogramo', 1, 0, 5.0, 1, 1000, 1000)")
            sqlite.execSQL("INSERT INTO presentations VALUES (100, 10, 'kilogramo', 1, 1, 1)")
            sqlite.execSQL("INSERT INTO equivalences VALUES (200, 10, 100, 1.0)")
            sqlite.execSQL("INSERT INTO area_stocks VALUES (300, 10, 2, 18.0, 5.0, 1000)")
            sqlite.execSQL("PRAGMA user_version = 1")
        } finally {
            sqlite.close()
        }

        val migratedDb = buildDb(v1DbFile)
        val migratedRepo = InventoryRepository(migratedDb, crypto)
        try {
            val summary = migratedRepo.getProductSummary(10L)
            assertNotNull("El producto v1 debe conservarse intacto tras migrar a v2", summary)
            assertEquals("Café Grano 1Kg", summary?.name)
            assertEquals(AreaId.ALMACEN_GENERAL.id, summary?.preferredAreaId)
            assertEquals(
                18.0,
                summary!!.stocksByArea.first { it.areaId == AreaId.DESPENSA.id }.quantityBase,
                0.001
            )
        } finally {
            migratedDb.close()
            v1DbFile.delete()
        }
    }

    @Test
    fun `7 - Desactivar Wi-Fi mediante NetworkCallback no cierra la app, detiene sync y reanuda al reconectar sin duplicados`() = runBlocking {
        // Usar un observer sin override de test para ejercitar directamente los callbacks reales de Android
        val realObserver = ConnectivityObserver(context)
        val realSync = SyncEngine(database, realObserver, crypto)
        val callback = realObserver.getNetworkCallbackForTesting()

        try {
            val wifiNetwork: Network = ShadowNetwork.newInstance(101)
            val validatedWifiCaps = ShadowNetworkCapabilities.newInstance().apply {
                org.robolectric.Shadows.shadowOf(this).apply {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
            val unvalidatedWifiCaps = ShadowNetworkCapabilities.newInstance().apply {
                org.robolectric.Shadows.shadowOf(this).apply {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }

            // 1. Conectar Wi-Fi válido
            callback.onAvailable(wifiNetwork)
            callback.onCapabilitiesChanged(wifiNetwork, validatedWifiCaps)
            delay(50)
            assertTrue("Wi-Fi validado debe marcar en línea", realObserver.isOnline.value)

            // 2. Simular desactivación brusca de Wi-Fi (pérdida de validación + onLost + onUnavailable)
            callback.onCapabilitiesChanged(wifiNetwork, unvalidatedWifiCaps)
            callback.onLost(wifiNetwork)
            callback.onLost(wifiNetwork) // Llamada repetida defensiva
            callback.onUnavailable()
            delay(50)

            assertTrue("Al desactivar Wi-Fi debe pasar a sin conexión sin cerrarse", !realObserver.isOnline.value)
            val statusOff = realSync.syncStatusFlow.first()
            assertEquals(SyncVisualStatus.SIN_CONEXION, statusOff.visualStatus)

            // 3. Configurar URL remota inalcanzable y operar offline: NO debe intentar sincronizar ni fallar
            realSync.configureRemoteEndpoint("https://127.0.0.1:59999/sync", "token-demo")

            val prodId = repository.registerProduct(
                name = "Cerveza Artesanal",
                primaryCode = "",
                description = "Registrada tras apagar Wi-Fi",
                category = "Bebidas",
                baseUnit = BaseUnit.BOTELLA,
                customInternalCode = "BAR-CERV-01",
                preferredAreaId = AreaId.BAR.id
            ).getOrThrow()

            repository.registrarEntrada(
                productId = prodId,
                destinationAreaId = AreaId.BAR.id,
                quantityInput = 24.0,
                notes = "Entrada sin Wi-Fi"
            ).getOrThrow()

            repository.registrarSalida(
                productId = prodId,
                sourceAreaId = AreaId.BAR.id,
                quantityInput = 6.0,
                notes = "Salida sin Wi-Fi"
            ).getOrThrow()

            // Intentar sincronizar manualmente sin red -> NO debe intentar conexión HTTP y debe responder rápido sin fallar
            val syncAttemptWhileOff = realSync.syncPendingChanges()
            assertEquals(0, syncAttemptWhileOff.processedCount)
            assertEquals(0, syncAttemptWhileOff.failedCount)
            assertTrue(realSync.syncStatusFlow.first().pendingCount >= 3)

            // Limpiar endpoint remoto para completar reconciliación local al reconectar Wi-Fi
            realSync.configureRemoteEndpoint("", "")

            // 4. Simular micro-cortes (flapping rápido) y luego reconexión estable de Wi-Fi
            val wifiNetwork2: Network = ShadowNetwork.newInstance(102)
            callback.onAvailable(wifiNetwork2)
            callback.onCapabilitiesChanged(wifiNetwork2, unvalidatedWifiCaps)
            callback.onLost(wifiNetwork2)
            callback.onAvailable(wifiNetwork2)
            callback.onCapabilitiesChanged(wifiNetwork2, validatedWifiCaps)

            var resumedAndSynced = false
            repeat(30) {
                delay(100)
                val st = realSync.syncStatusFlow.first()
                if (st.visualStatus == SyncVisualStatus.SINCRONIZADO && st.pendingCount == 0 && st.syncedCount >= 3) {
                    resumedAndSynced = true
                    return@repeat
                }
            }
            assertTrue("Al volver el Wi-Fi debe reanudar y completar la sincronización automáticamente", resumedAndSynced)

            // Verificar cero duplicados y existencias íntegras
            val product = repository.getProductSummary(prodId)!!
            assertEquals(18.0, product.stocksByArea.first { it.areaId == AreaId.BAR.id }.quantityBase, 0.001)
            assertEquals(2, repository.movementsFlow.first().size)
            assertEquals(3, database.syncDao().getAllLedger().size)
        } finally {
            realSync.shutdown()
            realObserver.unregister()
        }
    }
}
