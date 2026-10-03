package com.ballz.appstore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class CatalogRepo(private val ctx: Context) {

    companion object {
        // TODO: set to your GitHub user/repo once pushed.
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/SpaceBallz2k8/ballz-appstore/refs/heads/master/catalog/store.json"
        private const val CACHE = "catalog_cache.json"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Cached copy if present, otherwise the catalog bundled in the APK. */
    fun loadLocal(): Catalog {
        val cache = File(ctx.filesDir, CACHE)
        if (cache.exists()) {
            runCatching { return json.decodeFromString<Catalog>(cache.readText()) }
        }
        return runCatching {
            json.decodeFromString<Catalog>(
                ctx.assets.open("store.json").bufferedReader().use { it.readText() }
            )
        }.getOrDefault(Catalog())
    }

    suspend fun fetchRemote(): Catalog? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$CATALOG_URL?t=${System.currentTimeMillis()}").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val body = r.body?.string() ?: return@runCatching null
                val parsed = json.decodeFromString<Catalog>(body)
                File(ctx.filesDir, CACHE).writeText(body)
                parsed
            }
        }.getOrNull()
    }

    suspend fun latestRelease(repo: String): GhRelease = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub returned ${r.code}")
            json.decodeFromString<GhRelease>(r.body!!.string())
        }
    }
}
