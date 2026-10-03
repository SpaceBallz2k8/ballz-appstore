package com.ballz.appstore

import android.os.Build

/** Chooses the right APK from a release's assets for this device's ABI. */
object ApkPicker {
    private val v7Tokens = listOf("armeabi", "v7a", "armv7", "arm32")
    private val a64Tokens = listOf("arm64", "aarch64", "v8a")
    private val otherTokens = listOf("x86", "x64")

    fun pick(assets: List<GhAsset>, assetPattern: String?): GhAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }

        if (!assetPattern.isNullOrBlank()) {
            val re = Regex(assetPattern, RegexOption.IGNORE_CASE)
            apks.firstOrNull { re.containsMatchIn(it.name) }?.let { return it }
        }

        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val device64 = "arm64-v8a" in abis
        val deviceV7 = abis.any { it.startsWith("armeabi") }

        fun score(name: String): Int {
            val n = name.lowercase()
            if ("debug" in n) return -1
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

        return apks.map { it to score(it.name) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}
