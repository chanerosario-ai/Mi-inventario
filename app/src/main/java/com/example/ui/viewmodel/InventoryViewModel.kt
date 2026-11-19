package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MiInventarioApp
import com.example.data.backup.BackupFileInfo
import com.example.data.backup.BackupManager
import com.example.data.backup.BackupSummaryStats
import com.example.data.local.AreaEntity
import com.example.data.repository.InventoryRepository
import com.example.data.sync.SyncEngine
import com.example.domain.AreaId
import com.example.domain.AreaSummary
import com.example.domain.BaseUnit
import com.example.domain.InitialInventorySessionDetail
import com.example.domain.LowStockAlertItem
import com.example.domain.MovementRecord
import com.example.domain.ProductSummary
import com.example.domain.SyncConflictDetail
import com.example.domain.SyncStatusSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PinState(
    val isLoaded: Boolean = false,
    val isPinConfigured: Boolean = false,
    val isPinLockEnabled: Boolean = false,
    val isUnlocked: Boolean = false,
    val errorMessage: String? = null
)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MiInventarioApp
    val repository: InventoryRepository = app.repository
    val backupManager: BackupManager = app.backupManager
    val syncEngine: SyncEngine = app.syncEngine

    val areas: StateFlow<List<AreaEntity>> = repository.areasFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val areaSummaries: StateFlow<List<AreaSummary>> = repository.areaSummariesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val products: StateFlow<List<ProductSummary>> = repository.productsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lowStockAlerts: StateFlow<List<LowStockAlertItem>> = repository.lowStockAlertsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movements: StateFlow<List<MovementRecord>> = repository.movementsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val initialSessions: StateFlow<List<InitialInventorySessionDetail>> = repository.initialInventorySessionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncStatus: StateFlow<SyncStatusSummary> = syncEngine.syncStatusFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SyncStatusSummary()
        )

    val openConflicts: StateFlow<List<SyncConflictDetail>> = syncEngine.openConflictsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manualOfflineMode: StateFlow<Boolean> = syncEngine.isManualOfflineMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _pinState = MutableStateFlow(PinState())
    val pinState: StateFlow<PinState> = _pinState.asStateFlow()

    private val _scanCooldownMs = MutableStateFlow(2500L)
    val scanCooldownMs: StateFlow<Long> = _scanCooldownMs.asStateFlow()

    private val _localBackups = MutableStateFlow<List<BackupFileInfo>>(emptyList())
    val localBackups: StateFlow<List<BackupFileInfo>> = _localBackups.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refreshSecurityAndBackups()
    }

    fun refreshSecurityAndBackups() {
        viewModelScope.launch {
            repository.ensureInitialized()
            val configured = repository.isPinConfigured()
            val enabled = repository.isPinLockEnabled()
            val cooldown = repository.getScanCooldownMs()
            _scanCooldownMs.value = cooldown
            _pinState.value = _pinState.value.copy(
                isLoaded = true,
                isPinConfigured = configured,
                isPinLockEnabled = enabled,
                isUnlocked = if (!configured || !enabled) true else _pinState.value.isUnlocked
            )
            _localBackups.value = backupManager.listLocalBackups()
        }
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun showStatus(msg: String) {
        _statusMessage.value = msg
    }

    fun showError(msg: String) {
        _errorMessage.value = msg
    }

    // --- PIN & Security ---

    fun unlockWithPin(pin: String) {
        viewModelScope.launch {
            val ok = repository.verifyPin(pin)
            if (ok) {
                _pinState.value = _pinState.value.copy(isUnlocked = true, errorMessage = null)
            } else {
                _pinState.value = _pinState.value.copy(errorMessage = "PIN incorrecto. Inténtalo nuevamente.")
            }
        }
    }

    fun configureNewPin(pin: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setPin(pin)
                .onSuccess {
                    _pinState.value = PinState(
                        isLoaded = true,
                        isPinConfigured = true,
                        isPinLockEnabled = true,
                        isUnlocked = true,
                        errorMessage = null
                    )
                    _statusMessage.value = "PIN de administrador guardado con protección Android Keystore."
                    onSuccess()
                }
                .onFailure {
                    _pinState.value = _pinState.value.copy(errorMessage = it.message)
                    _errorMessage.value = it.message ?: "Error al guardar el PIN."
                }
        }
    }

    fun togglePinLock(enabled: Boolean) {
        viewModelScope.launch {
            repository.setPinLockEnabled(enabled)
            _pinState.value = _pinState.value.copy(isPinLockEnabled = enabled)
            _statusMessage.value = if (enabled) "Bloqueo con PIN activado." else "Bloqueo con PIN desactivado al iniciar."
        }
    }

    fun lockNow() {
        if (_pinState.value.isPinConfigured) {
            _pinState.value = _pinState.value.copy(isUnlocked = false, errorMessage = null)
        }
    }

    fun updateScanCooldown(ms: Long) {
        viewModelScope.launch {
            repository.setScanCooldownMs(ms)
            _scanCooldownMs.value = ms
            _statusMessage.value = "Tiempo anti-duplicado actualizado a ${ms / 1000.0}s."
        }
    }

    // --- Sync & Connectivity ---

    fun syncNow() {
        viewModelScope.launch {
            runCatching { syncEngine.syncPendingChanges() }
                .onSuccess { result ->
                    if (result.failedCount > 0 || result.conflictCount > 0) {
                        _errorMessage.value = result.message
                    } else {
                        _statusMessage.value = result.message
                    }
                }
                .onFailure {
                    _errorMessage.value = "No se pudo completar la sincronización; los cambios siguen guardados localmente."
                }
        }
    }

    fun setManualOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { syncEngine.setManualOfflineMode(enabled) }
                .onSuccess {
                    _statusMessage.value = if (enabled) {
                        "Modo sin conexión activado. Todos los registros se guardan en cola local."
                    } else {
                        "Modo en línea restaurado. Se sincronizarán los cambios pendientes."
                    }
                }
        }
    }

    fun configureSyncServer(url: String, token: String) {
        viewModelScope.launch {
            runCatching { syncEngine.configureRemoteEndpoint(url, token) }
                .onSuccess {
                    _statusMessage.value = if (url.isBlank()) {
                        "Sincronización usando registro local persistente."
                    } else {
                        "Servidor remoto de sincronización configurado."
                    }
                }
        }
    }

    suspend fun getSyncServerConfig(): Pair<String, String> {
        return syncEngine.getConfiguredRemoteUrl() to syncEngine.getConfiguredRemoteToken()
    }

    fun resolveConflictKeepLocal(conflictId: Long) {
        viewModelScope.launch {
            syncEngine.resolveConflictKeepLocal(conflictId)
            _statusMessage.value = "Conflicto resuelto conservando los datos locales."
        }
    }

    fun resolveConflictMarkReviewed(conflictId: Long) {
        viewModelScope.launch {
            syncEngine.resolveConflictMarkReviewed(conflictId)
            _statusMessage.value = "Conflicto marcado como revisado."
        }
    }

    // --- Products ---

    suspend fun findProductByCode(code: String): ProductSummary? = repository.findProductByCode(code)

    suspend fun checkCodeConflicts(codes: List<String>, excludeProductId: Long? = null): List<Pair<String, String>> =
        repository.checkCodeConflicts(codes, excludeProductId)

    fun registerProduct(
        name: String,
        primaryCode: String,
        additionalCodes: List<String>,
        description: String,
        category: String,
        baseUnit: BaseUnit,
        allowsDecimals: Boolean,
        allowFractionalPresentations: Boolean,
        defaultMinStock: Double?,
        extraPresentations: List<InventoryRepository.CustomPresentationDraft>,
        allowDuplicateCodeOverride: Boolean = false,
        customInternalCode: String? = null,
        preferredAreaId: Long = AreaId.ALMACEN_GENERAL.id,
        initialQuantityInput: Double? = null,
        onCreated: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.registerProduct(
                name = name,
                primaryCode = primaryCode,
                additionalCodes = additionalCodes,
                description = description,
                category = category,
                baseUnit = baseUnit,
                allowsDecimals = allowsDecimals,
                allowFractionalPresentations = allowFractionalPresentations,
                defaultMinStock = defaultMinStock,
                extraPresentations = extraPresentations,
                allowDuplicateCodeOverride = allowDuplicateCodeOverride,
                customInternalCode = customInternalCode,
                preferredAreaId = preferredAreaId,
                initialQuantityInput = initialQuantityInput
            ).onSuccess { id ->
                _statusMessage.value = "Producto '$name' registrado correctamente."
                onCreated(id)
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "No se pudo registrar el producto."
            }
        }
    }

    fun updateProduct(
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
        preferredAreaId: Long? = null,
        onUpdated: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.updateProduct(
                productId = productId,
                name = name,
                primaryCode = primaryCode,
                additionalCodes = additionalCodes,
                description = description,
                category = category,
                allowsDecimals = allowsDecimals,
                allowFractionalPresentations = allowFractionalPresentations,
                defaultMinStock = defaultMinStock,
                isActive = isActive,
                allowDuplicateCodeOverride = allowDuplicateCodeOverride,
                customInternalCode = customInternalCode,
                preferredAreaId = preferredAreaId
            ).onSuccess {
                _statusMessage.value = "Producto actualizado sin alterar su historial."
                onUpdated()
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "No se pudo actualizar el producto."
            }
        }
    }

    fun setProductActive(productId: Long, active: Boolean) {
        viewModelScope.launch {
            repository.setProductActive(productId, active)
                .onSuccess {
                    _statusMessage.value = if (active) "Producto reactivado." else "Producto desactivado (historial conservado)."
                }
                .onFailure { _errorMessage.value = it.message ?: "Error al cambiar estado." }
        }
    }

    fun addPresentation(
        productId: Long,
        name: String,
        baseUnitsPerPresentation: Double,
        allowsFractions: Boolean,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.addPresentationToProduct(productId, name, baseUnitsPerPresentation, allowsFractions)
                .onSuccess {
                    _statusMessage.value = "Presentación '$name' agregada."
                    onSuccess()
                }
                .onFailure { _errorMessage.value = it.message ?: "Equivalencia inválida." }
        }
    }

    fun setAreaMinStock(productId: Long, areaId: Long, minStockBase: Double?) {
        viewModelScope.launch {
            repository.setAreaMinStock(productId, areaId, minStockBase)
                .onSuccess { _statusMessage.value = "Stock mínimo actualizado." }
                .onFailure { _errorMessage.value = it.message ?: "Error al guardar stock mínimo." }
        }
    }

    // --- Operations ---

    fun registrarEntrada(
        productId: Long,
        areaId: Long,
        quantityInput: Double,
        presentationId: Long?,
        notes: String,
        allowDuplicateOverride: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.registrarEntrada(
                productId = productId,
                destinationAreaId = areaId,
                quantityInput = quantityInput,
                presentationId = presentationId,
                notes = notes,
                allowDuplicateOverride = allowDuplicateOverride
            ).onSuccess {
                _statusMessage.value = "Entrada registrada correctamente."
                onSuccess()
            }.onFailure {
                _errorMessage.value = it.message ?: "No se pudo registrar la entrada."
            }
        }
    }

    fun registrarSalida(
        productId: Long,
        areaId: Long,
        quantityInput: Double,
        presentationId: Long?,
        notes: String,
        allowDuplicateOverride: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.registrarSalida(
                productId = productId,
                sourceAreaId = areaId,
                quantityInput = quantityInput,
                presentationId = presentationId,
                notes = notes,
                allowDuplicateOverride = allowDuplicateOverride
            ).onSuccess {
                _statusMessage.value = "Salida registrada correctamente."
                onSuccess()
            }.onFailure {
                _errorMessage.value = it.message ?: "No se pudo registrar la salida."
            }
        }
    }

    fun realizarTransferencia(
        productId: Long,
        sourceAreaId: Long,
        destinationAreaId: Long,
        quantityInput: Double,
        presentationId: Long?,
        notes: String,
        allowDuplicateOverride: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.realizarTransferencia(
                productId = productId,
                sourceAreaId = sourceAreaId,
                destinationAreaId = destinationAreaId,
                quantityInput = quantityInput,
                presentationId = presentationId,
                notes = notes,
                allowDuplicateOverride = allowDuplicateOverride
            ).onSuccess {
                _statusMessage.value = "Transferencia completada de forma atómica."
                onSuccess()
            }.onFailure {
                _errorMessage.value = it.message ?: "No se pudo completar la transferencia."
            }
        }
    }

    fun registrarAjuste(
        productId: Long,
        areaId: Long,
        newQuantityInput: Double,
        presentationId: Long?,
        reason: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.registrarAjuste(
                productId = productId,
                areaId = areaId,
                newQuantityInput = newQuantityInput,
                presentationId = presentationId,
                reason = reason
            ).onSuccess {
                _statusMessage.value = "Ajuste registrado en el historial."
                onSuccess()
            }.onFailure {
                _errorMessage.value = it.message ?: "Error al registrar el ajuste."
            }
        }
    }

    // --- Initial Inventory ---

    fun abrirInventarioInicial(areaId: Long, notes: String = "") {
        viewModelScope.launch {
            repository.abrirInventarioInicial(areaId, notes)
                .onSuccess { _statusMessage.value = "Sesión de inventario inicial abierta." }
                .onFailure { _errorMessage.value = it.message ?: "No se pudo abrir el inventario inicial." }
        }
    }

    fun registrarConteoInicial(
        sessionId: Long,
        productId: Long,
        countedQuantityInput: Double,
        presentationId: Long?,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.registrarConteoInicial(sessionId, productId, countedQuantityInput, presentationId)
                .onSuccess {
                    _statusMessage.value = "Conteo físico actualizado."
                    onSuccess()
                }
                .onFailure { _errorMessage.value = it.message ?: "Error al registrar conteo." }
        }
    }

    fun eliminarItemConteoInicial(itemId: Long) {
        viewModelScope.launch {
            repository.eliminarItemConteoInicial(itemId)
                .onFailure { _errorMessage.value = it.message ?: "Error al eliminar conteo." }
        }
    }

    fun cerrarInventarioInicial(sessionId: Long, onClosed: () -> Unit = {}) {
        viewModelScope.launch {
            repository.cerrarInventarioInicial(sessionId)
                .onSuccess { count ->
                    _statusMessage.value = "Inventario inicial cerrado ($count productos actualizados). Las correcciones posteriores serán ajustes."
                    onClosed()
                }
                .onFailure { _errorMessage.value = it.message ?: "No se pudo cerrar el inventario inicial." }
        }
    }

    // --- Backups ---

    fun createLocalBackup() {
        viewModelScope.launch {
            backupManager.createLocalBackup()
                .onSuccess { info ->
                    _localBackups.value = backupManager.listLocalBackups()
                    _statusMessage.value = "Copia creada: ${info.fileName}"
                }
                .onFailure { _errorMessage.value = it.message ?: "Error al crear copia de seguridad." }
        }
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            backupManager.exportBackupToUri(uri)
                .onSuccess { _statusMessage.value = "Copia de seguridad exportada correctamente." }
                .onFailure { _errorMessage.value = it.message ?: "Error al exportar copia de seguridad." }
        }
    }

    fun restoreFromLocalFile(path: String, onSuccess: (BackupSummaryStats) -> Unit = {}) {
        viewModelScope.launch {
            backupManager.restoreFromLocalFile(path)
                .onSuccess { stats ->
                    refreshSecurityAndBackups()
                    _statusMessage.value = "Restauración completada: ${stats.productsCount} productos y ${stats.movementsCount} movimientos."
                    onSuccess(stats)
                }
                .onFailure { _errorMessage.value = it.message ?: "Restauración cancelada: copia inválida." }
        }
    }

    fun restoreFromUri(uri: Uri, onSuccess: (BackupSummaryStats) -> Unit = {}) {
        viewModelScope.launch {
            backupManager.restoreFromUri(uri)
                .onSuccess { stats ->
                    refreshSecurityAndBackups()
                    _statusMessage.value = "Restauración completada: ${stats.productsCount} productos y ${stats.movementsCount} movimientos."
                    onSuccess(stats)
                }
                .onFailure { _errorMessage.value = it.message ?: "Restauración cancelada: archivo inválido." }
        }
    }
}
