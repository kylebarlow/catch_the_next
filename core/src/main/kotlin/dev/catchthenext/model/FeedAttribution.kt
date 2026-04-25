package dev.catchthenext.model

data class FeedAttribution(
    val feedOnestopId: String,
    val feedName: String?,
    val attributionText: String?,
    val attributionInstructions: String?,
    val useWithoutAttribution: Boolean = true,
    val licenseSpdx: String?,
    val licenseUrl: String?
)
