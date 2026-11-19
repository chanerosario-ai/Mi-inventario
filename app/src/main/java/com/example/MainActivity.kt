package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.navigation.AppRoute
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.InitialInventoryScreen
import com.example.ui.screens.OperationsScreen
import com.example.ui.screens.PinLockScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.ProductsScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.SettingsBackupScreen
import com.example.ui.theme.MiInventarioTheme
import com.example.ui.viewmodel.InventoryViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiInventarioTheme {
                MiInventarioRoot()
            }
        }
    }
}

@Composable
fun MiInventarioRoot(viewModel: InventoryViewModel = viewModel()) {
    val pinState by viewModel.pinState.collectAsStateWithLifecycle()
    val areas by viewModel.areas.collectAsStateWithLifecycle()
    val areaSummaries by viewModel.areaSummaries.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val lowStockAlerts by viewModel.lowStockAlerts.collectAsStateWithLifecycle()
    val movements by viewModel.movements.collectAsStateWithLifecycle()
    val initialSessions by viewModel.initialSessions.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val openConflicts by viewModel.openConflicts.collectAsStateWithLifecycle()
    val manualOfflineMode by viewModel.manualOfflineMode.collectAsStateWithLifecycle()
    val scanCooldownMs by viewModel.scanCooldownMs.collectAsStateWithLifecycle()
    val localBackups by viewModel.localBackups.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar("Error: $it")
            viewModel.clearMessages()
        }
    }

    if (!pinState.isLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!pinState.isUnlocked) {
        PinLockScreen(
            pinState = pinState,
            onUnlock = { viewModel.unlockWithPin(it) },
            onConfigureNewPin = { viewModel.configureNewPin(it) }
        )
        return
    }

    val navController = rememberNavController()

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding().coerceAtMost(0.dp))
        ) {
            NavHost(
                navController = navController,
                startDestination = AppRoute.Dashboard
            ) {
                composable<AppRoute.Dashboard> {
                    DashboardScreen(
                        areaSummaries = areaSummaries,
                        lowStockAlerts = lowStockAlerts,
                        syncStatus = syncStatus,
                        onNavigateToScan = { navController.navigate(AppRoute.Scanner()) },
                        onNavigateToProducts = { areaId ->
                            navController.navigate(AppRoute.Products(filterAreaId = areaId))
                        },
                        onNavigateToManualAddProduct = {
                            navController.navigate(AppRoute.Products(openManualForm = true))
                        },
                        onNavigateToEntries = {
                            navController.navigate(AppRoute.Operations(initialTab = "ENTRADA"))
                        },
                        onNavigateToExits = {
                            navController.navigate(AppRoute.Operations(initialTab = "SALIDA"))
                        },
                        onNavigateToTransfers = {
                            navController.navigate(AppRoute.Operations(initialTab = "TRANSFERENCIA"))
                        },
                        onNavigateToHistory = { areaId ->
                            navController.navigate(AppRoute.History(filterAreaId = areaId))
                        },
                        onNavigateToInitialInventory = {
                            navController.navigate(AppRoute.InitialInventory())
                        },
                        onNavigateToSettings = {
                            navController.navigate(AppRoute.Settings)
                        },
                        onSelectAlertProduct = { productId ->
                            navController.navigate(AppRoute.ProductDetail(productId))
                        }
                    )
                }

                composable<AppRoute.Scanner> {
                    ScannerScreen(
                        products = products,
                        cooldownMs = scanCooldownMs,
                        onLookupCode = { viewModel.findProductByCode(it) },
                        onOpenProductDetail = { id -> navController.navigate(AppRoute.ProductDetail(id)) },
                        onRegisterNewWithCode = { code ->
                            navController.navigate(AppRoute.Products(prefillBarcode = code))
                        },
                        onQuickEntry = { id ->
                            navController.navigate(AppRoute.Operations(initialTab = "ENTRADA", preselectedProductId = id))
                        },
                        onQuickExit = { id ->
                            navController.navigate(AppRoute.Operations(initialTab = "SALIDA", preselectedProductId = id))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<AppRoute.Products> { backStackEntry ->
                    val route = backStackEntry.toRoute<AppRoute.Products>()
                    ProductsScreen(
                        products = products,
                        areas = areas,
                        initialFilterAreaId = route.filterAreaId,
                        prefillBarcode = route.prefillBarcode,
                        openManualForm = route.openManualForm,
                        cooldownMs = scanCooldownMs,
                        onCheckCodeConflicts = { codes, excludeId ->
                            viewModel.checkCodeConflicts(codes, excludeId)
                        },
                        onRegisterProduct = { name, primary, additional, desc, cat, unit, dec, frac, minStock, extras, overrideDup, customInternal, prefAreaId, initQty ->
                            viewModel.registerProduct(
                                name = name,
                                primaryCode = primary,
                                additionalCodes = additional,
                                description = desc,
                                category = cat,
                                baseUnit = unit,
                                allowsDecimals = dec,
                                allowFractionalPresentations = frac,
                                defaultMinStock = minStock,
                                extraPresentations = extras,
                                allowDuplicateCodeOverride = overrideDup,
                                customInternalCode = customInternal,
                                preferredAreaId = prefAreaId,
                                initialQuantityInput = initQty
                            )
                        },
                        onSelectProduct = { id ->
                            navController.navigate(AppRoute.ProductDetail(id))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<AppRoute.ProductDetail> { backStackEntry ->
                    val route = backStackEntry.toRoute<AppRoute.ProductDetail>()
                    val product = products.firstOrNull { it.productId == route.productId }
                    ProductDetailScreen(
                        product = product,
                        areas = areas,
                        cooldownMs = scanCooldownMs,
                        onCheckCodeConflicts = { codes, excludeId ->
                            viewModel.checkCodeConflicts(codes, excludeId)
                        },
                        onUpdateProduct = { id, name, primary, additional, desc, cat, dec, frac, minStock, active, overrideDup, customInternal, prefAreaId ->
                            viewModel.updateProduct(
                                productId = id,
                                name = name,
                                primaryCode = primary,
                                additionalCodes = additional,
                                description = desc,
                                category = cat,
                                allowsDecimals = dec,
                                allowFractionalPresentations = frac,
                                defaultMinStock = minStock,
                                isActive = active,
                                allowDuplicateCodeOverride = overrideDup,
                                customInternalCode = customInternal,
                                preferredAreaId = prefAreaId
                            )
                        },
                        onSetActive = { id, active -> viewModel.setProductActive(id, active) },
                        onAddPresentation = { id, presName, factor, frac ->
                            viewModel.addPresentation(id, presName, factor, frac)
                        },
                        onSetAreaMinStock = { prodId, areaId, min ->
                            viewModel.setAreaMinStock(prodId, areaId, min)
                        },
                        onRegisterAdjustment = { prodId, areaId, newQty, presId, reason ->
                            viewModel.registrarAjuste(prodId, areaId, newQty, presId, reason)
                        },
                        onQuickEntry = { id ->
                            navController.navigate(AppRoute.Operations(initialTab = "ENTRADA", preselectedProductId = id))
                        },
                        onQuickExit = { id ->
                            navController.navigate(AppRoute.Operations(initialTab = "SALIDA", preselectedProductId = id))
                        },
                        onQuickTransfer = { id ->
                            navController.navigate(AppRoute.Operations(initialTab = "TRANSFERENCIA", preselectedProductId = id))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<AppRoute.Operations> { backStackEntry ->
                    val route = backStackEntry.toRoute<AppRoute.Operations>()
                    OperationsScreen(
                        initialTab = route.initialTab,
                        preselectedProductId = route.preselectedProductId,
                        preselectedAreaId = route.preselectedAreaId,
                        areas = areas,
                        products = products,
                        cooldownMs = scanCooldownMs,
                        onLookupCode = { viewModel.findProductByCode(it) },
                        onRegisterEntry = { pId, aId, qty, presId, notes, onDone ->
                            viewModel.registrarEntrada(pId, aId, qty, presId, notes, onSuccess = onDone)
                        },
                        onRegisterExit = { pId, aId, qty, presId, notes, onDone ->
                            viewModel.registrarSalida(pId, aId, qty, presId, notes, onSuccess = onDone)
                        },
                        onRegisterTransfer = { pId, srcId, dstId, qty, presId, notes, onDone ->
                            viewModel.realizarTransferencia(pId, srcId, dstId, qty, presId, notes, onSuccess = onDone)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<AppRoute.InitialInventory> { backStackEntry ->
                    val route = backStackEntry.toRoute<AppRoute.InitialInventory>()
                    InitialInventoryScreen(
                        initialAreaId = route.areaId,
                        areas = areas,
                        products = products,
                        sessions = initialSessions,
                        cooldownMs = scanCooldownMs,
                        onLookupCode = { viewModel.findProductByCode(it) },
                        onOpenSession = { areaId, notes -> viewModel.abrirInventarioInicial(areaId, notes) },
                        onRecordCount = { sessionId, productId, qty, presId, onDone ->
                            viewModel.registrarConteoInicial(sessionId, productId, qty, presId, onDone)
                        },
                        onDeleteCountItem = { itemId -> viewModel.eliminarItemConteoInicial(itemId) },
                        onCloseSession = { sessionId -> viewModel.cerrarInventarioInicial(sessionId) },
                        onRegisterAdjustment = { prodId, areaId, newQty, presId, reason ->
                            viewModel.registrarAjuste(prodId, areaId, newQty, presId, reason)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<AppRoute.History> { backStackEntry ->
                    val route = backStackEntry.toRoute<AppRoute.History>()
                    HistoryScreen(
                        initialAreaId = route.filterAreaId,
                        initialProductId = route.filterProductId,
                        areas = areas,
                        movements = movements,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<AppRoute.Settings> {
                    SettingsBackupScreen(
                        pinState = pinState,
                        scanCooldownMs = scanCooldownMs,
                        localBackups = localBackups,
                        syncStatus = syncStatus,
                        openConflicts = openConflicts,
                        manualOfflineMode = manualOfflineMode,
                        onLoadSyncServerConfig = { viewModel.getSyncServerConfig() },
                        onConfigureSyncServer = { url, token -> viewModel.configureSyncServer(url, token) },
                        onSyncNow = { viewModel.syncNow() },
                        onToggleManualOffline = { viewModel.setManualOfflineMode(it) },
                        onResolveConflictKeepLocal = { viewModel.resolveConflictKeepLocal(it) },
                        onResolveConflictMarkReviewed = { viewModel.resolveConflictMarkReviewed(it) },
                        onConfigurePin = { viewModel.configureNewPin(it) },
                        onTogglePinLock = { viewModel.togglePinLock(it) },
                        onLockNow = { viewModel.lockNow() },
                        onUpdateScanCooldown = { viewModel.updateScanCooldown(it) },
                        onCreateLocalBackup = { viewModel.createLocalBackup() },
                        onExportBackupToUri = { viewModel.exportBackupToUri(it) },
                        onRestoreFromLocalFile = { viewModel.restoreFromLocalFile(it) },
                        onRestoreFromUri = { viewModel.restoreFromUri(it) },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
