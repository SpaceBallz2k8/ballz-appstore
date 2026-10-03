package com.ballz.appstore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** catalog/store.json */
@Serializable
data class Catalog(
    val schemaVersion: Int = 1,
    val catalogVersion: Int = 0,
    val updatedAt: String? = null,
    val apps: List<CatalogApp> = emptyList(),
)

@Serializable
data class CatalogApp(
    val id: String,
    val repo: String,                        // "owner/repo"
    val name: String,
    val description: String,
    val category: String = "Other",
    val packageName: String,
    val minSdk: Int = 1,
    val abis: List<String> = listOf("any"),  // ["any"] = no native code
    val certSha256: List<String> = emptyList(),
    val icon: String? = null,
    val assetPattern: String? = null,        // optional regex to force a specific release asset
    val innerApkPattern: String? = null,     // optional regex to pick the APK inside an archive
    val status: String = "active",           // active | deprecated | broken
    val statusNote: String? = null,
)

/** Subset of the GitHub "latest release" API response. */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)
