package com.ballz.appstore

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Detail(
    val loading: Boolean = false,
    val release: GhRelease? = null,
    val asset: GhAsset? = null,
    val installed: String? = null,
    val upToDate: Boolean = false,
    val busy: String? = null,
    val progress: Float? = null,
    val message: String? = null,
)

enum class Badge { None, Installed, Update }

const val DEFAULT_WARNING =
    "This app makes SYSTEM changes. Use it at your own risk. See the developer's page before use."

fun formatBytes(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val repo = CatalogRepo(app)
    private val latestRepo = LatestRepo(app)
    private var installingTag: String? = null
    private var preparing = false   // true while an APK is being downloaded/unpacked/verified

    var apps by mutableStateOf<List<CatalogApp>>(emptyList())
        private set
    var latest by mutableStateOf<Map<String, LatestApp>>(emptyMap())
        private set
    var installedVersions by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var offline by mutableStateOf(false)
        private set
    var selected by mutableStateOf<CatalogApp?>(null)
        private set
    var detail by mutableStateOf(Detail())
        private set
    var warningText by mutableStateOf(DEFAULT_WARNING)
        private set
    var confirmingInstall by mutableStateOf(false)
        private set
    var cacheBytes by mutableStateOf(0L)
        private set
    var cacheNote by mutableStateOf<String?>(null)
        private set

    init {
        val local = repo.loadLocal()
        apps = local.apps.filter { isCompatible(it) }
        warningText = local.warningText?.takeIf { it.isNotBlank() } ?: DEFAULT_WARNING
        latest = latestRepo.loadLocal()
        refreshInstalled()
        refreshCacheSize(clean = true)   // leftovers from a previous session
        refresh()
        viewModelScope.launch { InstallEvents.flow.collect { onInstallResult(it) } }
    }

    private fun isCompatible(a: CatalogApp): Boolean {
        if (a.minSdk > Build.VERSION.SDK_INT) return false
        return "any" in a.abis || a.abis.any { it in Build.SUPPORTED_ABIS }
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            cacheNote = null
            val remote = repo.fetchRemote()
            if (remote != null) {
                apps = remote.apps.filter { isCompatible(it) }
                warningText = remote.warningText?.takeIf { it.isNotBlank() } ?: DEFAULT_WARNING
                offline = false
            } else offline = true
            latestRepo.fetchRemote()?.let { latest = it }
            refreshInstalled()
            loading = false
        }
    }

    /** Re-reads installed versions (after install, or when returning to the app). */
    fun refreshInstalled() {
        installedVersions = apps.mapNotNull { a ->
            UpdateChecker.installedVersion(ctx, a.packageName)?.let { a.packageName to it }
        }.toMap()
        // Forget "installed by us" tags for apps that have since been uninstalled.
        apps.filter { it.packageName !in installedVersions }
            .forEach { UpdateChecker.clearRecorded(ctx, it.packageName) }
    }

    fun onResume() {
        refreshInstalled()
        refreshCacheSize()
        val a = selected ?: return
        val inst = UpdateChecker.installedVersion(ctx, a.packageName)
        detail = detail.copy(
            installed = inst,
            upToDate = detail.release?.let { UpdateChecker.isCurrent(ctx, a, inst, it.tagName) } ?: false,
        )
    }

    private fun refreshCacheSize(clean: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (clean) Installer.cleanTemp(ctx)
            cacheBytes = Installer.cacheBytes(ctx)
        }
    }

    fun clearCache() {
        if (preparing) {
            cacheNote = "An install is still preparing – try again in a moment."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val freed = Installer.clearCache(ctx)
            cacheNote = if (freed > 0) "Freed ${formatBytes(freed)}" else "Nothing to clean"
            cacheBytes = Installer.cacheBytes(ctx)
        }
    }

    fun badge(app: CatalogApp): Badge {
        val inst = installedVersions[app.packageName] ?: return Badge.None
        val tag = latest[app.id]?.tag ?: return Badge.Installed
        return if (UpdateChecker.isCurrent(ctx, app, inst, tag)) Badge.Installed else Badge.Update
    }

    fun updates(): List<CatalogApp> = apps.filter { badge(it) == Badge.Update }

    fun open(app: CatalogApp) {
        selected = app
        confirmingInstall = false
        detail = Detail(loading = true)
        viewModelScope.launch {
            val installed = UpdateChecker.installedVersion(ctx, app.packageName)
            detail = try {
                val rel = repo.latestRelease(app.repo)
                val asset = ApkPicker.pick(rel.assets, app.assetPattern)
                Detail(
                    release = rel, asset = asset, installed = installed,
                    upToDate = UpdateChecker.isCurrent(ctx, app, installed, rel.tagName),
                    message = if (asset == null) "No compatible APK in the latest release." else null,
                )
            } catch (e: Exception) {
                Detail(installed = installed, message = "Couldn't reach GitHub: ${e.message}")
            }
        }
    }

    fun back() {
        selected = null
        confirmingInstall = false
    }

    /** Install/Update button: apps flagged with a warning ask for confirmation first. */
    fun requestInstall() {
        if (selected?.warning == true) confirmingInstall = true else installOrUpdate()
    }

    fun confirmInstall() {
        confirmingInstall = false
        installOrUpdate()
    }

    fun cancelConfirm() { confirmingInstall = false }

    fun installOrUpdate() {
        val app = selected ?: return
        val asset = detail.asset ?: return

        if (!Installer.canInstall(ctx)) {
            Installer.openUnknownSourcesSettings(ctx)
            detail = detail.copy(message = "Allow installs from this app, then press the button again.")
            return
        }
        installingTag = detail.release?.tagName
        preparing = true
        viewModelScope.launch {
            try {
                detail = detail.copy(busy = "Downloading…", progress = 0f, message = null)
                val downloaded = Installer.download(ctx, asset.url) { p -> detail = detail.copy(progress = p) }
                detail = detail.copy(busy = "Unpacking…", progress = null)
                val file = ArchiveExtractor.resolveApk(
                    downloaded, asset.name, app.innerApkPattern, java.io.File(ctx.cacheDir, "extract"),
                )
                Installer.verify(ctx, file, app)?.let {
                    file.delete()
                    refreshCacheSize(clean = true)
                    detail = detail.copy(busy = null, progress = null, message = it)
                    return@launch
                }
                detail = detail.copy(busy = "Installing…", progress = null)
                Installer.install(ctx, file)
            } catch (e: Exception) {
                detail = detail.copy(busy = null, progress = null, message = "Failed: ${e.message}")
                refreshCacheSize(clean = true)
            } finally {
                preparing = false   // the installer session now holds its own copy of the APK
            }
        }
    }

    /** The store never offers to uninstall itself. */
    fun canUninstall(app: CatalogApp): Boolean =
        detail.installed != null && app.packageName != ctx.packageName

    /** Opens Android's uninstall confirmation; state refreshes in onResume() when it closes. */
    fun uninstall() {
        val pkg = selected?.packageName ?: return
        if (pkg == ctx.packageName) return
        ctx.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun launch() {
        val pkg = selected?.packageName ?: return
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
    }

    private fun onInstallResult(r: InstallResult) {
        val app = selected
        refreshCacheSize(clean = true)   // APK is no longer needed, whatever the outcome
        detail = when (r.status) {
            PackageInstaller.STATUS_SUCCESS -> {
                app?.let { a -> installingTag?.let { UpdateChecker.recordInstalled(ctx, a.packageName, it) } }
                val inst = app?.let { UpdateChecker.installedVersion(ctx, it.packageName) }
                refreshInstalled()
                detail.copy(busy = null, message = "Installed ✓", installed = inst ?: detail.installed, upToDate = true)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> detail.copy(busy = null, message = "Cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> detail.copy(
                busy = null,
                message = "Conflicts with an installed copy (probably signed differently). Uninstall it first.",
            )
            else -> detail.copy(busy = null, message = "Install failed: ${r.message ?: r.status}")
        }
    }
}
