package dev.catchthenext.model

data class Place(
    val placeId: String,
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val category: String? = null,
    val type: String? = null,
)
