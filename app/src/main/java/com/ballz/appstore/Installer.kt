package com.ballz.appstore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

data class InstallResult(val status: Int, val message: String?)

/** Install outcomes are delivered here by [InstallReceiver]. */
object InstallEvents {
    val flow = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
}

object Installer {
    private const val ACTION = "com.ballz.appstore.INSTALL_RESULT"
    private val http = OkHttpClient()

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val out = File(ctx.cacheDir, "download.apk").apply { delete() }
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Download failed (${r.code})")
                val body = r.body ?: error("Empty response")
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            out
        }

    /** SHA-256 fingerprints (lowercase hex) of the APK's signing certificates. */
    @Suppress("DEPRECATION")
    private fun apkCertHashes(ctx: Context, file: File): Pair<String?, Set<String>> {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        } ?: return null to emptySet()

        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val hashes = sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
        return info.packageName to hashes
    }

    /** Returns an error message, or null if the APK matches what the catalog expects. */
    fun verify(ctx: Context, file: File, app: CatalogApp): String? {
        val (pkg, hashes) = apkCertHashes(ctx, file)
        if (pkg == null) return "Downloaded file isn't a valid APK."
        if (pkg != app.packageName) return "Package mismatch (got $pkg)."
        if (app.certSha256.isEmpty()) return "No signing certificate is pinned for this app."
        if (hashes.none { it in app.certSha256.map(String::lowercase) })
            return "Signing certificate doesn't match the catalog. Install blocked."
        return null
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            InstallEvents.flow.tryEmit(InstallResult(status, message))
        }
    }
}
