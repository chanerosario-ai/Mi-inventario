package com.example.data.sync

import androidx.room.withTransaction
import com.example.data.local.AppConfigEntity
import com.example.data.local.AppDatabase
import com.example.data.local.SyncConflictEntity
import com.example.data.local.SyncLedgerEntity
import com.example.data.local.SyncQueueEntity
import com.example.domain.SyncConflictDetail
import com.example.domain.SyncStatusSummary
import com.example.domain.SyncVisualStatus
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class SyncRunResult(
    val processedCount: Int,
    val syncedCount: Int,
    val skippedDuplicateCount: Int,
    val conflictCount: Int,
    val failedCount: Int,
    val message: String
)

/**
 * Reliable offline-first sync queue processor.
 * - Guarantees zero data loss offline (all writes go to Room + sync_queue in the same transaction).
 * - Deduplicates products and movements via deterministic idempotency keys.
 * - Never overwrites newer records with older payloads (creates SyncConflictEntity instead).
 * - Automatically syncs pending items whenever ConnectivityObserver transitions stably to online.
 * - Never crashes the process on Wi-Fi loss, socket failures, or unexpected runtime errors.
 */
class SyncEngine(
    private val database: AppDatabase,
    private val connectivityObserver: ConnectivityObserver,
    private val cryptoManager: KeystoreCryptoManager
) {
    private val syncDao = database.syncDao()
    private val configDao = database.configDao()

    private val _isSyncing = MutableStateFlow(false)
    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    private val _lastSyncMessage = MutableStateFlow<String?>(null)
    private val _remoteUrl = MutableStateFlow("")
    private val _manualOffline = MutableStateFlow(false)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        _isSyncing.value = false
        _lastSyncMessage.value = "Error en segundo plano: ${throwable.localizedMessage ?: "reintento pendiente"}"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val syncMutex = Mutex()
    private val configLoaded = CompletableDeferred<Unit>()

    private var autoSyncJob: Job? = null

    companion object {
        const val CONFIG_SYNC_REMOTE_URL = "sync_remote_url"
        const val CONFIG_SYNC_REMOTE_TOKEN = "sync_remote_token"
        const val CONFIG_SYNC_LAST_AT = "sync_last_at"
        const val CONFIG_SYNC_MANUAL_OFFLINE = "sync_manual_offline"
        private const val NETWORK_DEBOUNCE_MS = 250L
    }

    init {
        scope.launch {
            loadPersistedSyncConfig()
        }
        startAutoSyncObserver()
    }

    private suspend fun loadPersistedSyncConfig() {
        try {
            _remoteUrl.value = configDao.getConfig(CONFIG_SYNC_REMOTE_URL)?.value.orEmpty()
            _lastSyncAt.value = configDao.getConfig(CONFIG_SYNC_LAST_AT)?.value?.toLongOrNull()
            val manualOffline = configDao.getConfig(CONFIG_SYNC_MANUAL_OFFLINE)?.value == "true"
            _manualOffline.value = manualOffline
            if (manualOffline) {
                connectivityObserver.setManualOfflineMode(true)
            }
        } catch (_: Throwable) {
            // Never crash startup if config table isn't ready yet
        } finally {
            configLoaded.complete(Unit)
        }
    }

    @OptIn(FlowPreview::class)
    private fun startAutoSyncObserver() {
        autoSyncJob?.cancel()
        autoSyncJob = scope.launch {
            configLoaded.await()
            combine(
                connectivityObserver.isOnline,
                syncDao.observeAllQueue().map { list ->
                    list.count { it.status == "PENDING" || it.status == "FAILED" }
                }
            ) { online, pending -> online to pending }
                .distinctUntilChanged()
                .debounce(NETWORK_DEBOUNCE_MS)
                .catch { /* Keep observer resilient across DB/lifecycle transitions */ }
                .collectLatest { (online, pending) ->
                    if (online && pending > 0 && connectivityObserver.isOnline.value) {
                        syncPendingChanges()
                    }
                }
        }
    }

    val openConflictsFlow: Flow<List<SyncConflictDetail>> = syncDao.observeOpenConflicts().map { list ->
        list.map {
            SyncConflictDetail(
                id = it.id,
                queueItemId = it.queueItemId,
                entityType = it.entityType,
                entityKey = it.entityKey,
                localPayloadJson = it.localPayloadJson,
                remotePayloadJson = it.remotePayloadJson,
                localTimestamp = it.localTimestamp,
                remoteTimestamp = it.remoteTimestamp,
                reason = it.reason,
                createdAt = it.createdAt
            )
        }
    }

    val syncStatusFlow: Flow<SyncStatusSummary> = combine(
        connectivityObserver.isOnline,
        syncDao.observeAllQueue(),
        syncDao.observeOpenConflicts(),
        _isSyncing,
        _lastSyncAt,
        _lastSyncMessage,
        _remoteUrl
    ) { values ->
        val online = values[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val queue = values[1] as List<SyncQueueEntity>
        @Suppress("UNCHECKED_CAST")
        val conflicts = values[2] as List<SyncConflictEntity>
        val syncing = values[3] as Boolean
        val lastAt = values[4] as Long?
        val lastMsg = values[5] as String?
        val remoteUrl = values[6] as String

        val pendingCount = queue.count { it.status == "PENDING" || it.status == "FAILED" }
        val syncedCount = queue.count { it.status == "SYNCED" }
        val conflictCount = conflicts.size

        val visual = when {
            syncing -> SyncVisualStatus.SINCRONIZANDO
            conflictCount > 0 -> SyncVisualStatus.CONFLICTO
            !online -> SyncVisualStatus.SIN_CONEXION
            pendingCount > 0 -> SyncVisualStatus.PENDIENTE
            else -> SyncVisualStatus.SINCRONIZADO
        }

        SyncStatusSummary(
            visualStatus = visual,
            isOnline = online,
            pendingCount = pendingCount,
            openConflictsCount = conflictCount,
            syncedCount = syncedCount,
            lastSyncAt = lastAt,
            lastSyncMessage = lastMsg,
            remoteUrlConfigured = remoteUrl.isNotBlank()
        )
    }

    fun isManualOfflineMode(): Flow<Boolean> = _manualOffline

    suspend fun setManualOfflineMode(enabled: Boolean) {
        _manualOffline.value = enabled
        connectivityObserver.setManualOfflineMode(enabled)
        runCatching {
            configDao.setConfig(
                AppConfigEntity(
                    key = CONFIG_SYNC_MANUAL_OFFLINE,
                    value = enabled.toString(),
                    isEncrypted = false,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        if (!enabled && connectivityObserver.isOnline.value) {
            syncPendingChanges()
        }
    }

    suspend fun configureRemoteEndpoint(url: String, token: String) {
        val cleanUrl = url.trim()
        val cleanToken = token.trim()
        val now = System.currentTimeMillis()
        _remoteUrl.value = cleanUrl
        configDao.setConfig(
            AppConfigEntity(
                key = CONFIG_SYNC_REMOTE_URL,
                value = cleanUrl,
                isEncrypted = false,
                updatedAt = now
            )
        )
        if (cleanToken.isNotEmpty()) {
            configDao.setConfig(
                AppConfigEntity(
                    key = CONFIG_SYNC_REMOTE_TOKEN,
                    value = cryptoManager.encrypt(cleanToken),
                    isEncrypted = true,
                    updatedAt = now
                )
            )
        } else {
            configDao.deleteConfig(CONFIG_SYNC_REMOTE_TOKEN)
        }
    }

    suspend fun getConfiguredRemoteUrl(): String =
        runCatching { configDao.getConfig(CONFIG_SYNC_REMOTE_URL)?.value.orEmpty() }.getOrDefault("")

    suspend fun getConfiguredRemoteToken(): String {
        val raw = runCatching { configDao.getConfig(CONFIG_SYNC_REMOTE_TOKEN)?.value }.getOrNull() ?: return ""
        return cryptoManager.decrypt(raw).orEmpty()
    }

    suspend fun syncPendingChanges(): SyncRunResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                configLoaded.await()

                if (!connectivityObserver.isOnline.value) {
                    val pending = runCatching { syncDao.getPendingQueueItems().size }.getOrDefault(0)
                    val msg = "Sin conexión a internet ($pending cambios pendientes en cola local)."
                    _lastSyncMessage.value = msg
                    return@withContext SyncRunResult(0, 0, 0, 0, 0, msg)
                }

                val pendingItems = syncDao.getPendingQueueItems()
                if (pendingItems.isEmpty()) {
                    val now = System.currentTimeMillis()
                    _lastSyncAt.value = now
                    val msg = "Todo sincronizado al día."
                    _lastSyncMessage.value = msg
                    return@withContext SyncRunResult(0, 0, 0, 0, 0, msg)
                }

                _isSyncing.value = true
                var synced = 0
                var skippedDup = 0
                var conflicts = 0
                var failed = 0
                var processed = 0
                var interruptedOffline = false

                val remoteUrl = getConfiguredRemoteUrl()
                val remoteToken = getConfiguredRemoteToken()

                for (item in pendingItems) {
                    // Stop immediately if Wi-Fi/connectivity dropped mid-batch; do NOT try to sync offline
                    if (!connectivityObserver.isOnline.value) {
                        interruptedOffline = true
                        break
                    }

                    processed++
                    val outcome = if (remoteUrl.isNotBlank()) {
                        pushItemToRemoteServer(item, remoteUrl, remoteToken)
                    } else {
                        reconcileWithLocalLedger(item)
                    }

                    when (outcome) {
                        is ItemSyncOutcome.Synced -> {
                            synced++
                            val now = System.currentTimeMillis()
                            database.withTransaction {
                                syncDao.updateQueueItem(
                                    item.copy(status = "SYNCED", lastError = null, syncedAt = now)
                                )
                                syncDao.upsertLedger(
                                    SyncLedgerEntity(
                                        idempotencyKey = item.idempotencyKey,
                                        entityType = item.entityType,
                                        entityKey = item.entityKey,
                                        entityUpdatedAt = item.entityUpdatedAt,
                                        payloadJson = item.payloadJson,
                                        syncedAt = now
                                    )
                                )
                            }
                        }

                        is ItemSyncOutcome.DuplicateSkipped -> {
                            skippedDup++
                            val now = System.currentTimeMillis()
                            syncDao.updateQueueItem(
                                item.copy(status = "SYNCED", lastError = null, syncedAt = now)
                            )
                        }

                        is ItemSyncOutcome.Conflict -> {
                            conflicts++
                            val now = System.currentTimeMillis()
                            database.withTransaction {
                                syncDao.updateQueueItem(
                                    item.copy(
                                        status = "CONFLICT",
                                        lastError = outcome.reason
                                    )
                                )
                                syncDao.insertConflict(
                                    SyncConflictEntity(
                                        queueItemId = item.id,
                                        entityType = item.entityType,
                                        entityKey = item.entityKey,
                                        localPayloadJson = item.payloadJson,
                                        remotePayloadJson = outcome.remotePayloadJson,
                                        localTimestamp = item.entityUpdatedAt,
                                        remoteTimestamp = outcome.remoteTimestamp,
                                        reason = outcome.reason,
                                        resolutionStatus = "OPEN",
                                        createdAt = now
                                    )
                                )
                            }
                        }

                        is ItemSyncOutcome.TransientError -> {
                            failed++
                            syncDao.updateQueueItem(
                                item.copy(
                                    status = "FAILED",
                                    retryCount = item.retryCount + 1,
                                    lastError = outcome.error
                                )
                            )
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (!interruptedOffline) {
                    _lastSyncAt.value = now
                    runCatching {
                        configDao.setConfig(
                            AppConfigEntity(
                                key = CONFIG_SYNC_LAST_AT,
                                value = now.toString(),
                                isEncrypted = false,
                                updatedAt = now
                            )
                        )
                    }
                }

                val summaryMsg = buildString {
                    if (interruptedOffline) {
                        append("Conexión perdida. Guardado local activo (Sincronizados: $synced)")
                    } else {
                        append("Sincronizados: $synced")
                    }
                    if (skippedDup > 0) append(" • Duplicados omitidos: $skippedDup")
                    if (conflicts > 0) append(" • Conflictos: $conflicts")
                    if (failed > 0) append(" • Pendientes por reintentar: $failed")
                }
                _lastSyncMessage.value = summaryMsg

                SyncRunResult(
                    processedCount = processed,
                    syncedCount = synced,
                    skippedDuplicateCount = skippedDup,
                    conflictCount = conflicts,
                    failedCount = failed,
                    message = summaryMsg
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val safeMsg = "Sincronización pausada (${t.localizedMessage ?: "sin conexión"}). Datos locales seguros."
                _lastSyncMessage.value = safeMsg
                SyncRunResult(0, 0, 0, 0, 1, safeMsg)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private sealed interface ItemSyncOutcome {
        data object Synced : ItemSyncOutcome
        data object DuplicateSkipped : ItemSyncOutcome
        data class Conflict(
            val remotePayloadJson: String,
            val remoteTimestamp: Long,
            val reason: String
        ) : ItemSyncOutcome
        data class TransientError(val error: String) : ItemSyncOutcome
    }

    private suspend fun reconcileWithLocalLedger(item: SyncQueueEntity): ItemSyncOutcome {
        // 1. Idempotency deduplication: same idempotencyKey already synced -> skip without duplicating
        val exactMatch = syncDao.getLedgerByKey(item.idempotencyKey)
        if (exactMatch != null) {
            return ItemSyncOutcome.DuplicateSkipped
        }

        // 2. Timestamp conflict guard: never overwrite a newer synced record with older offline data
        val latestForKey = syncDao.getLatestLedgerForEntity(item.entityType, item.entityKey)
        if (latestForKey != null && item.entityUpdatedAt < latestForKey.entityUpdatedAt) {
            return ItemSyncOutcome.Conflict(
                remotePayloadJson = latestForKey.payloadJson,
                remoteTimestamp = latestForKey.entityUpdatedAt,
                reason = "Existe una versión más reciente sincronizada (${latestForKey.entityUpdatedAt}); no se sobrescribió con datos anteriores (${item.entityUpdatedAt})."
            )
        }

        // 3. Movement deduplication guard: never sync a movement key twice
        if (item.entityType == "MOVEMENT" && latestForKey != null) {
            return ItemSyncOutcome.DuplicateSkipped
        }

        return ItemSyncOutcome.Synced
    }

    private fun pushItemToRemoteServer(
        item: SyncQueueEntity,
        remoteUrl: String,
        token: String
    ): ItemSyncOutcome {
        if (!connectivityObserver.isOnline.value) {
            return ItemSyncOutcome.TransientError("Sin conexión a internet.")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(remoteUrl)
            if (url.protocol.lowercase() !in listOf("http", "https")) {
                return ItemSyncOutcome.TransientError("Protocolo de servidor inválido (usa http o https).")
            }
            conn = (url.openConnection() as? HttpURLConnection)
                ?: return ItemSyncOutcome.TransientError("No se pudo abrir conexión HTTP.")
            conn.apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val body = JSONObject().apply {
                put("idempotencyKey", item.idempotencyKey)
                put("entityType", item.entityType)
                put("entityKey", item.entityKey)
                put("entityId", item.entityId)
                put("entityUpdatedAt", item.entityUpdatedAt)
                put("payload", JSONObject(item.payloadJson))
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

            when (code) {
                in 200..299 -> {
                    val jsonResp = runCatching { JSONObject(responseText) }.getOrNull()
                    when (jsonResp?.optString("status")?.uppercase()) {
                        "DUPLICATE" -> ItemSyncOutcome.DuplicateSkipped
                        "CONFLICT" -> ItemSyncOutcome.Conflict(
                            remotePayloadJson = jsonResp.optJSONObject("remotePayload")?.toString() ?: responseText,
                            remoteTimestamp = jsonResp.optLong("remoteTimestamp", System.currentTimeMillis()),
                            reason = jsonResp.optString("reason", "Conflicto reportado por el servidor remoto.")
                        )
                        else -> ItemSyncOutcome.Synced
                    }
                }
                409 -> {
                    val jsonResp = runCatching { JSONObject(responseText) }.getOrNull()
                    ItemSyncOutcome.Conflict(
                        remotePayloadJson = jsonResp?.optJSONObject("remotePayload")?.toString() ?: responseText,
                        remoteTimestamp = jsonResp?.optLong("remoteTimestamp", System.currentTimeMillis())
                            ?: System.currentTimeMillis(),
                        reason = jsonResp?.optString("reason", "Conflicto de versión o duplicado en servidor (HTTP 409).")
                            ?: "Conflicto de versión en servidor (HTTP 409)."
                    )
                }
                else -> ItemSyncOutcome.TransientError("Error HTTP $code del servidor de sincronización.")
            }
        } catch (t: Throwable) {
            ItemSyncOutcome.TransientError(t.localizedMessage ?: "Sin conexión al servidor remoto.")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Record an incoming newer remote change to verify conflict detection & review without data loss.
     */
    suspend fun recordIncomingRemoteConflictForReview(
        entityType: String,
        entityKey: String,
        remotePayloadJson: String,
        remoteTimestamp: Long,
        localPayloadJson: String,
        localTimestamp: Long,
        reason: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            syncDao.upsertLedger(
                SyncLedgerEntity(
                    idempotencyKey = "REMOTE-$entityType-$entityKey-$remoteTimestamp",
                    entityType = entityType,
                    entityKey = entityKey,
                    entityUpdatedAt = remoteTimestamp,
                    payloadJson = remotePayloadJson,
                    syncedAt = now
                )
            )
            syncDao.insertConflict(
                SyncConflictEntity(
                    queueItemId = 0L,
                    entityType = entityType,
                    entityKey = entityKey,
                    localPayloadJson = localPayloadJson,
                    remotePayloadJson = remotePayloadJson,
                    localTimestamp = localTimestamp,
                    remoteTimestamp = remoteTimestamp,
                    reason = reason,
                    resolutionStatus = "OPEN",
                    createdAt = now
                )
            )
        }
    }

    suspend fun resolveConflictKeepLocal(conflictId: Long) {
        val conflict = syncDao.getConflictById(conflictId) ?: return
        val now = System.currentTimeMillis()
        database.withTransaction {
            syncDao.updateConflict(
                conflict.copy(resolutionStatus = "KEPT_LOCAL", resolvedAt = now)
            )
            val queueItem = syncDao.getQueueItemByKey("CONFLICT_RESOLVE_${conflict.id}")
            if (queueItem == null) {
                syncDao.upsertLedger(
                    SyncLedgerEntity(
                        idempotencyKey = "RESOLVED_LOCAL_${conflict.id}_$now",
                        entityType = conflict.entityType,
                        entityKey = conflict.entityKey,
                        entityUpdatedAt = maxOf(conflict.localTimestamp, conflict.remoteTimestamp) + 1,
                        payloadJson = conflict.localPayloadJson,
                        syncedAt = now
                    )
                )
            }
            if (conflict.queueItemId > 0L) {
                val pending = syncDao.getAllQueue().firstOrNull { it.id == conflict.queueItemId }
                if (pending != null) {
                    syncDao.updateQueueItem(pending.copy(status = "SYNCED", lastError = null, syncedAt = now))
                }
            }
        }
    }

    suspend fun resolveConflictMarkReviewed(conflictId: Long) {
        val conflict = syncDao.getConflictById(conflictId) ?: return
        val now = System.currentTimeMillis()
        database.withTransaction {
            syncDao.updateConflict(
                conflict.copy(resolutionStatus = "ACCEPTED_REMOTE", resolvedAt = now)
            )
            if (conflict.queueItemId > 0L) {
                val pending = syncDao.getAllQueue().firstOrNull { it.id == conflict.queueItemId }
                if (pending != null) {
                    syncDao.updateQueueItem(pending.copy(status = "SYNCED", lastError = null, syncedAt = now))
                }
            }
        }
    }

    fun shutdown() {
        autoSyncJob?.cancel()
        scope.cancel()
    }
}
