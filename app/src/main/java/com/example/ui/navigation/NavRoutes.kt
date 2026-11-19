package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable
    data object Dashboard : AppRoute

    @Serializable
    data class Scanner(
        val returnMode: String = "INSPECT", // INSPECT, ENTRY, EXIT, TRANSFER, INITIAL_COUNT
        val areaId: Long? = null
    ) : AppRoute

    @Serializable
    data class Products(
        val filterAreaId: Long? = null,
        val prefillBarcode: String? = null,
        val openManualForm: Boolean = false
    ) : AppRoute

    @Serializable
    data class ProductDetail(val productId: Long) : AppRoute

    @Serializable
    data class Operations(
        val initialTab: String = "ENTRADA", // ENTRADA, SALIDA, TRANSFERENCIA
        val preselectedProductId: Long? = null,
        val preselectedAreaId: Long? = null
    ) : AppRoute

    @Serializable
    data class InitialInventory(val areaId: Long? = null) : AppRoute

    @Serializable
    data class History(
        val filterAreaId: Long? = null,
        val filterProductId: Long? = null
    ) : AppRoute

    @Serializable
    data object Settings : AppRoute
}
