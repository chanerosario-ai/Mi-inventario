package com.example

import android.app.Application
import com.example.data.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.repository.InventoryRepository
import com.example.data.sync.ConnectivityObserver
import com.example.data.sync.SyncEngine
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiInventarioApp : Application() {
    private val appExceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appExceptionHandler)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val cryptoManager: KeystoreCryptoManager by lazy { KeystoreCryptoManager() }
    val repository: InventoryRepository by lazy { InventoryRepository(database, cryptoManager) }
    val backupManager: BackupManager by lazy { BackupManager(this, database, cryptoManager) }
    val connectivityObserver: ConnectivityObserver by lazy { ConnectivityObserver(this) }
    val syncEngine: SyncEngine by lazy { SyncEngine(database, connectivityObserver, cryptoManager) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { repository.ensureInitialized() }
            runCatching { syncEngine.syncPendingChanges() }
        }
    }
}
