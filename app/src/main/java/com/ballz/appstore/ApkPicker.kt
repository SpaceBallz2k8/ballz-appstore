package com.ballz.appstore

import android.os.Build

/** Chooses the right release asset (plain .apk, or an archive containing one) for this device. */
object ApkPicker {
    private val archiveExt = listOf(".zip", ".tar.gz", ".tgz", ".tar")
    private val v7Tokens = listOf("armeabi", "v7a", "armv7", "arm32")
    private val a64Tokens = listOf("arm64", "aarch64", "v8a")
    private val otherTokens = listOf("x86", "x64")

    fun isArchive(name: String): Boolean = archiveExt.any { name.lowercase().endsWith(it) }

    private fun score(name: String): Int {
        val n = name.lowercase()
        if ("debug" in n) return -1
        val isApk = n.endsWith(".apk")
        // Archives only count if they look like Android builds (releases also hold linux/windows/mac files).
        if (!isApk && !(isArchive(n) && ("android" in n || "apk" in n))) return -1

        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val device64 = "arm64-v8a" in abis
        val deviceV7 = abis.any { it.startsWith("armeabi") }
        val isV7 = v7Tokens.any { it in n }
        val is64 = a64Tokens.any { it in n }
        val isOther = otherTokens.any { it in n }
        return when {
            isOther && !isV7 -> -1
            is64 && device64 -> 4
            isV7 && deviceV7 -> 3
            is64 || (isV7 && !deviceV7) -> -1
            "universal" in n -> 2
            else -> 1
        }
    }

    fun pick(assets: List<GhAsset>, assetPattern: String?): GhAsset? {
        if (!assetPattern.isNullOrBlank()) {
            val re = Regex(assetPattern, RegexOption.IGNORE_CASE)
            assets.firstOrNull {
                re.containsMatchIn(it.name) && (it.name.endsWith(".apk", true) || isArchive(it.name))
            }?.let { return it }
        }
        return assets.map { it to score(it.name) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy({ it.second }, { it.first.name.endsWith(".apk", true) }))
            ?.first
    }

    /** Picks which APK inside an archive to install. Returns an index into [names], or null. */
    fun pickInner(names: List<String>, innerPattern: String?): Int? {
        if (!innerPattern.isNullOrBlank()) {
            val re = Regex(innerPattern, RegexOption.IGNORE_CASE)
            names.indexOfFirst { re.containsMatchIn(it) }.takeIf { it >= 0 }?.let { return it }
        }
        return names.withIndex()
            .map { (i, n) -> i to score(n.substringAfterLast('/')) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}
