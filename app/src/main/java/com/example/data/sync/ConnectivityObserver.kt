package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes real device network availability safely via Android ConnectivityManager.
 * - Never invokes synchronous ConnectivityManager IPC inside NetworkCallback teardown methods.
 * - Tracks per-Network validated internet capability to avoid Wi-Fi disconnect flapping/crashes.
 * - Wraps all ConnectivityThread callbacks in runCatching so system/OEM IPC issues never crash the app.
 * - Supports explicit manual offline mode and deterministic test overrides without blocking local storage.
 */
class ConnectivityObserver(context: Context) {
    private val connectivityManager =
        runCatching {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

    private val networkValidationMap = ConcurrentHashMap<Network, Boolean>()
    @Volatile
    private var activeDefaultNetwork: Network? = null
    @Volatile
    private var usingDefaultCallback: Boolean = false

    private val _systemOnline = MutableStateFlow(checkCurrentSystemOnline())
    private val _manualOfflineOverride = MutableStateFlow<Boolean?>(null)
    private val _isOnline = MutableStateFlow(computeEffectiveOnline())

    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runCatching {
                if (usingDefaultCallback) {
                    activeDefaultNetwork = network
                }
                // Wait for onCapabilitiesChanged to confirm NET_CAPABILITY_VALIDATED unless already recorded
                if (networkValidationMap[network] == true) {
                    _systemOnline.value = true
                    recompute()
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            runCatching {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val usable = hasInternet && isValidated
                networkValidationMap[network] = usable
                if (usingDefaultCallback) {
                    activeDefaultNetwork = network
                    _systemOnline.value = usable
                } else {
                    _systemOnline.value = networkValidationMap.values.any { it }
                }
                recompute()
            }
        }

        override fun onLost(network: Network) {
            runCatching {
                networkValidationMap.remove(network)
                if (usingDefaultCallback) {
                    if (activeDefaultNetwork == network) {
                        activeDefaultNetwork = null
                        _systemOnline.value = false
                    }
                } else {
                    _systemOnline.value = networkValidationMap.values.any { it }
                }
                recompute()
            }
        }

        override fun onUnavailable() {
            runCatching {
                activeDefaultNetwork = null
                networkValidationMap.clear()
                _systemOnline.value = false
                recompute()
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            runCatching {
                if (blocked) {
                    networkValidationMap[network] = false
                    if (usingDefaultCallback && activeDefaultNetwork == network) {
                        _systemOnline.value = false
                    } else if (!usingDefaultCallback) {
                        _systemOnline.value = networkValidationMap.values.any { it }
                    }
                    recompute()
                }
            }
        }
    }

    init {
        registerCallback()
    }

    private fun registerCallback() {
        val cm = connectivityManager ?: return
        val registeredDefault = runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
            true
        }.getOrDefault(false)

        if (registeredDefault) {
            usingDefaultCallback = true
        } else {
            usingDefaultCallback = false
            runCatching {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
            }
        }
    }

    private fun checkCurrentSystemOnline(): Boolean {
        return runCatching {
            val cm = connectivityManager ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    fun setManualOfflineMode(forceOffline: Boolean) {
        _manualOfflineOverride.value = if (forceOffline) true else null
        if (!forceOffline) {
            _systemOnline.value = checkCurrentSystemOnline() || networkValidationMap.values.any { it }
        }
        recompute()
    }

    fun setTestOnlineState(online: Boolean?) {
        _manualOfflineOverride.value = if (online == null) null else !online
        if (online != null) {
            _systemOnline.value = online
        } else {
            _systemOnline.value = checkCurrentSystemOnline()
        }
        recompute()
    }

    /**
     * Exposes callback hook safely for automated unit tests simulating system Wi-Fi loss/reconnection.
     */
    internal fun getNetworkCallbackForTesting(): ConnectivityManager.NetworkCallback = networkCallback

    private fun computeEffectiveOnline(): Boolean {
        return when (_manualOfflineOverride.value) {
            true -> false
            false -> true
            null -> _systemOnline.value
        }
    }

    private fun recompute() {
        _isOnline.value = computeEffectiveOnline()
    }

    fun unregister() {
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
        networkValidationMap.clear()
        activeDefaultNetwork = null
    }
}
