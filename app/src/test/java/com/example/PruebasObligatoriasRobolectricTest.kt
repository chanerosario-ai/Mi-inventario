package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.repository.InventoryRepository
import com.example.domain.AreaId
import com.example.domain.BaseUnit
import com.example.domain.DuplicateCodeException
import com.example.domain.InsufficientStockException
import com.example.domain.InventoryValidationException
import com.example.domain.MovementType
import com.example.domain.UnauthorizedTransferException
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PruebasObligatoriasRobolectricTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var database: AppDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var repository: InventoryRepository
    private lateinit var backupManager: BackupManager

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "test_mi_inventario_${System.nanoTime()}.db")
        database = buildDb(dbFile)
        crypto = KeystoreCryptoManager()
        repository = InventoryRepository(database, crypto)
        backupManager = BackupManager(context, database, crypto)
        repository.ensureInitialized()
    }

    private fun buildDb(file: File): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() {
        database.close()
        dbFile.delete()
    }

    @Test
    fun `PRUEBA 1 - Registrar producto nuevo y asociarle codigo`() = runTest {
        val productId = repository.registerProduct(
            name = "Agua Mineral 600ml",
            primaryCode = "7501000123456",
            additionalCodes = listOf("AGUA-600"),
            category = "Bebidas",
            baseUnit = BaseUnit.BOTELLA
        ).getOrThrow()

        val foundByBarcode = repository.findProductByCode("7501000123456")
        val foundByAdditional = repository.findProductByCode("AGUA-600")
        assertNotNull(foundByBarcode)
        assertEquals(productId, foundByBarcode?.productId)
        assertEquals("Agua Mineral 600ml", foundByAdditional?.name)

        // Duplicate code warning check
        val dupResult = repository.registerProduct(
            name = "Otro Producto",
            primaryCode = "7501000123456",
            baseUnit = BaseUnit.BOTELLA
        )
        assertTrue(dupResult.exceptionOrNull() is DuplicateCodeException)
    }

    @Test
    fun `PRUEBA 2 y 3 - Registrar 100 botellas en Almacen general y transferir 20 a Despensa`() = runTest {
        val productId = repository.registerProduct(
            name = "Agua Purificada",
            primaryCode = "7501000999001",
            baseUnit = BaseUnit.BOTELLA
        ).getOrThrow()

        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_GENERAL.id,
            quantityInput = 100.0
        ).getOrThrow()

        repository.realizarTransferencia(
            productId = productId,
            sourceAreaId = AreaId.ALMACEN_GENERAL.id,
            destinationAreaId = AreaId.DESPENSA.id,
            quantityInput = 20.0
        ).getOrThrow()

        val summary = repository.getProductSummary(productId)!!
        val general = summary.stocksByArea.first { it.areaId == AreaId.ALMACEN_GENERAL.id }.quantityBase
        val despensa = summary.stocksByArea.first { it.areaId == AreaId.DESPENSA.id }.quantityBase

        assertEquals(80.0, general, 0.001)
        assertEquals(20.0, despensa, 0.001)
    }

    @Test
    fun `PRUEBA 4 y 5 - Registrar 50 botellas en Almacen de licores y transferir 12 al Bar`() = runTest {
        val productId = repository.registerProduct(
            name = "Ron Añejo 750ml",
            primaryCode = "7502000111222",
            category = "Licores",
            baseUnit = BaseUnit.BOTELLA
        ).getOrThrow()

        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_LICORES.id,
            quantityInput = 50.0
        ).getOrThrow()

        repository.realizarTransferencia(
            productId = productId,
            sourceAreaId = AreaId.ALMACEN_LICORES.id,
            destinationAreaId = AreaId.BAR.id,
            quantityInput = 12.0
        ).getOrThrow()

        val summary = repository.getProductSummary(productId)!!
        val licores = summary.stocksByArea.first { it.areaId == AreaId.ALMACEN_LICORES.id }.quantityBase
        val bar = summary.stocksByArea.first { it.areaId == AreaId.BAR.id }.quantityBase

        assertEquals(38.0, licores, 0.001)
        assertEquals(12.0, bar, 0.001)
    }

    @Test
    fun `PRUEBA 6 - Intentar retirar mas productos de los disponibles debe fallar sin dejar saldos negativos`() = runTest {
        val productId = repository.registerProduct(
            name = "Tequila Reposado",
            primaryCode = "7503000333444",
            baseUnit = BaseUnit.BOTELLA
        ).getOrThrow()

        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.BAR.id,
            quantityInput = 10.0
        ).getOrThrow()

        val exitFail = repository.registrarSalida(
            productId = productId,
            sourceAreaId = AreaId.BAR.id,
            quantityInput = 15.0
        )
        assertTrue(exitFail.isFailure)
        assertTrue(exitFail.exceptionOrNull() is InsufficientStockException)

        val transferFail = repository.realizarTransferencia(
            productId = productId,
            sourceAreaId = AreaId.ALMACEN_LICORES.id,
            destinationAreaId = AreaId.BAR.id,
            quantityInput = 5.0
        )
        assertTrue(transferFail.isFailure)
        assertTrue(transferFail.exceptionOrNull() is InsufficientStockException)

        // Stock in Bar must remain 10
        val barStock = repository.getProductSummary(productId)!!
            .stocksByArea.first { it.areaId == AreaId.BAR.id }.quantityBase
        assertEquals(10.0, barStock, 0.001)
    }

    @Test
    fun `PRUEBA 7 - Cerrar y abrir la base de datos conserva todos los datos`() = runTest {
        val productId = repository.registerProduct(
            name = "Whisky 12 años",
            primaryCode = "5000267014203",
            baseUnit = BaseUnit.BOTELLA
        ).getOrThrow()

        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_LICORES.id,
            quantityInput = 24.0
        ).getOrThrow()

        // Simulate closing and reopening the app/database
        database.close()
        val reopenedDb = buildDb(dbFile)
        val reopenedRepo = InventoryRepository(reopenedDb, crypto)

        try {
            val found = reopenedRepo.findProductByCode("5000267014203")
            assertNotNull(found)
            val stock = found!!.stocksByArea.first { it.areaId == AreaId.ALMACEN_LICORES.id }.quantityBase
            assertEquals(24.0, stock, 0.001)
            val movements = reopenedRepo.movementsFlow.first()
            assertEquals(1, movements.size)
        } finally {
            reopenedDb.close()
        }
    }

    @Test
    fun `PRUEBA 8 - Crear copia de seguridad y restaurarla conserva productos, cantidades e historial`() = runTest {
        val productId = repository.registerProduct(
            name = "Vino Tinto Reserva",
            primaryCode = "8410000111222",
            baseUnit = BaseUnit.BOTELLA,
            extraPresentations = listOf(InventoryRepository.CustomPresentationDraft("caja", 12.0))
        ).getOrThrow()

        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_LICORES.id,
            quantityInput = 3.0,
            presentationId = repository.getProductSummary(productId)!!.presentations.first { it.name == "caja" }.presentationId
        ).getOrThrow()

        val backupInfo = backupManager.createLocalBackup().getOrThrow()
        assertTrue(File(backupInfo.absolutePath).exists())

        // Tampered backup must be rejected without erasing data
        val badRestore = backupManager.restoreFromJsonString("{\"format\":\"MI_INVENTARIO_BACKUP\",\"schemaVersion\":1,\"checksumSha256\":\"bad\",\"createdAt\":1,\"payload\":{}}")
        assertTrue(badRestore.isFailure)

        // Mutate DB after backup (add extra movement)
        repository.registrarSalida(
            productId = productId,
            sourceAreaId = AreaId.ALMACEN_LICORES.id,
            quantityInput = 6.0
        ).getOrThrow()
        assertEquals(
            30.0,
            repository.getProductSummary(productId)!!.stocksByArea.first { it.areaId == AreaId.ALMACEN_LICORES.id }.quantityBase,
            0.001
        )

        // Restore valid backup -> reverts to 36 bottles and 1 movement
        val stats = backupManager.restoreFromLocalFile(backupInfo.absolutePath).getOrThrow()
        assertEquals(1, stats.productsCount)
        assertEquals(1, stats.movementsCount)

        val restored = repository.findProductByCode("8410000111222")!!
        val restoredQty = restored.stocksByArea.first { it.areaId == AreaId.ALMACEN_LICORES.id }.quantityBase
        assertEquals(36.0, restoredQty, 0.001)
        assertEquals(1, repository.movementsFlow.first().size)
    }

    @Test
    fun `PRUEBA 9 - Cajas se convierten correctamente a unidades base y rechazan fracciones si no estan habilitadas`() = runTest {
        val productId = repository.registerProduct(
            name = "Agua",
            primaryCode = "7501000000001",
            baseUnit = BaseUnit.BOTELLA,
            allowFractionalPresentations = false,
            extraPresentations = listOf(InventoryRepository.CustomPresentationDraft("caja", 24.0))
        ).getOrThrow()

        val summary = repository.getProductSummary(productId)!!
        val cajaPres = summary.presentations.first { it.name == "caja" }
        val botellaPres = summary.presentations.first { it.isBase }

        // 2 cajas = 48 botellas
        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_GENERAL.id,
            quantityInput = 2.0,
            presentationId = cajaPres.presentationId
        ).getOrThrow()

        // + 5 botellas = 53 botellas
        repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_GENERAL.id,
            quantityInput = 5.0,
            presentationId = botellaPres.presentationId
        ).getOrThrow()

        val stock = repository.getProductSummary(productId)!!
            .stocksByArea.first { it.areaId == AreaId.ALMACEN_GENERAL.id }.quantityBase
        assertEquals(53.0, stock, 0.001)

        // Fractional box (1.5 cajas) must fail when disabled
        val fractionalFail = repository.registrarEntrada(
            productId = productId,
            destinationAreaId = AreaId.ALMACEN_GENERAL.id,
            quantityInput = 1.5,
            presentationId = cajaPres.presentationId
        )
        assertTrue(fractionalFail.isFailure)
        assertTrue(fractionalFail.exceptionOrNull() is InventoryValidationException)
    }

    @Test
    fun `PRUEBA 10 - Movimientos, transferencias, inventario inicial, decimales y reglas de seguridad`() = runTest {
        // Decimal product (kg)
        val cafeId = repository.registerProduct(
            name = "Café en grano",
            primaryCode = "7509998887771",
            category = "Alimentos",
            baseUnit = BaseUnit.KILOGRAMO,
            allowsDecimals = true
        ).getOrThrow()

        // Initial inventory in Despensa (prevents duplicate product counting in same session)
        val sessionId = repository.abrirInventarioInicial(AreaId.DESPENSA.id, "Conteo inicial").getOrThrow()
        repository.registrarConteoInicial(sessionId, cafeId, 4.5, null).getOrThrow()
        // Re-scanning/updating same product replaces count (4.5 -> 5.25), does NOT add 4.5 + 5.25!
        repository.registrarConteoInicial(sessionId, cafeId, 5.25, null).getOrThrow()
        repository.cerrarInventarioInicial(sessionId).getOrThrow()

        val afterInit = repository.getProductSummary(cafeId)!!
            .stocksByArea.first { it.areaId == AreaId.DESPENSA.id }.quantityBase
        assertEquals(5.25, afterInit, 0.001)

        // Unauthorized transfer route (Despensa -> Bar) must be rejected
        val badTransfer = repository.realizarTransferencia(
            productId = cafeId,
            sourceAreaId = AreaId.DESPENSA.id,
            destinationAreaId = AreaId.BAR.id,
            quantityInput = 1.0
        )
        assertTrue(badTransfer.exceptionOrNull() is UnauthorizedTransferException)

        // Post-close adjustment creates AJUSTE movement
        repository.registrarAjuste(
            productId = cafeId,
            areaId = AreaId.DESPENSA.id,
            newQuantityInput = 5.0,
            reason = "Merma por tostado"
        ).getOrThrow()

        val movements = repository.movementsFlow.first()
        assertEquals(2, movements.size)
        assertEquals(MovementType.AJUSTE, movements[0].movementType)
        assertEquals(MovementType.INVENTARIO_INICIAL, movements[1].movementType)

        // Keystore PIN setup & verification
        repository.setPin("2580").getOrThrow()
        assertTrue(repository.isPinConfigured())
        assertTrue(repository.verifyPin("2580"))
        assertTrue(!repository.verifyPin("1111"))
    }
}
