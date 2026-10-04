@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ballz.appstore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import kotlin.math.abs

private val Accent = Color(0xFF3DDC84)
private val Amber = Color(0xFFFFC107)
private val BgTop = Color(0xFF111827)
private val BgBottom = Color(0xFF05070A)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UpdateWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
                    ) {
                        val sel = vm.selected
                        if (sel == null) HomeScreen(vm) else DetailScreen(vm, sel)
                        BackHandler(enabled = sel != null) { vm.back() }
                        BackHandler(enabled = vm.confirmingInstall) { vm.cancelConfirm() }   // closes the warning first
                        Text(
                            "Apps are installed and used at your own risk.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}

// ---------- helpers ----------

/** Stable per-app colour pair (bright, dark) derived from the id. */
private fun tint(app: CatalogApp): Pair<Color, Color> {
    val h = abs(app.id.hashCode() % 360).toFloat()
    return Color.hsv(h, 0.55f, 0.55f) to Color.hsv((h + 40f) % 360f, 0.65f, 0.22f)
}

/** Icon from the catalog (extracted from the APK), otherwise the repo owner's GitHub avatar. */
private fun iconUrl(app: CatalogApp): String {
    val icon = app.icon
    return when {
        icon == null -> "https://github.com/${app.repo.substringBefore('/')}.png?size=160"
        icon.startsWith("http") -> icon
        else -> CatalogRepo.CATALOG_URL.substringBeforeLast('/') + "/" + icon   // e.g. icons/newpipe.png
    }
}

@Composable
fun AppIcon(app: CatalogApp, size: Dp) {
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        // Letter shows until (or if) the image loads.
        Text(
            app.name.take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        AsyncImage(model = iconUrl(app), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun Pill(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White) }
}

// ---------- home ----------

@Composable
fun HomeScreen(vm: MainViewModel) {
    val grouped = vm.apps.groupBy { it.category }.toSortedMap()
    val updates = vm.updates()
    val sections: List<Pair<String, List<CatalogApp>>> =
        (if (updates.isNotEmpty()) listOf("Updates available" to updates) else emptyList()) + grouped.toList()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Ballz Store",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                )
                Text(
                    (if (vm.offline) "Offline – showing saved catalog" else "${vm.apps.size} apps") +
                        (if (updates.isNotEmpty()) "  •  ${updates.size} update" + (if (updates.size == 1) "" else "s") else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
                vm.cacheNote?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Accent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.clearCache() }) {
                    Text(if (vm.cacheBytes > 0) "Clear cache · ${formatBytes(vm.cacheBytes)}" else "Clear cache")
                }
                Button(onClick = { vm.refresh() }, enabled = !vm.loading) {
                    Text(if (vm.loading) "Refreshing…" else "Refresh")
                }
            }
        }

        if (grouped.isEmpty()) {
            Text(
                if (vm.loading) "Loading…" else "No apps in the catalog yet.",
                modifier = Modifier.padding(48.dp),
            )
        }

        // Padding lives INSIDE the lazy lists so focused cards (which scale up and glow)
        // have room to grow instead of being clipped by the list bounds.
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
            items(sections) { (category, list) ->
                Column {
                    Row(
                        Modifier.padding(start = 48.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(5.dp).height(24.dp)
                                .clip(RoundedCornerShape(3.dp)).background(Accent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            category,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(list, key = { it.id }) { app -> AppCard(app, vm.badge(app)) { vm.open(app) } }
                    }
                }
            }
        }
    }
}

@Composable
fun AppCard(app: CatalogApp, badge: Badge, onClick: () -> Unit) {
    val (bright, dark) = tint(app)
    val shape = RoundedCornerShape(18.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.width(260.dp).height(170.dp),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(containerColor = dark, focusedContainerColor = bright),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = shape)
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = Accent.copy(alpha = 0.35f), elevation = 18.dp)
        ),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))
            )
        ) {
            if (badge == Badge.Update) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).size(12.dp)
                        .clip(CircleShape).background(Accent)
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (app.warning) {
                                Text("⚠ ", style = MaterialTheme.typography.titleMedium, color = Amber)
                            }
                            Text(
                                app.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when {
                            app.status != "active" ->
                                Text("⚠ ${app.status}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFC107))
                            badge == Badge.Update ->
                                Text("⬆ Update available", style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold, color = Accent)
                            badge == Badge.Installed ->
                                Text("✓ Installed", style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
                Text(
                    app.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------- detail ----------

@Composable
fun DetailScreen(vm: MainViewModel, app: CatalogApp) {
    if (vm.confirmingInstall) {
        ConfirmWarning(vm, app)
        return
    }
    val d = vm.detail
    val (bright, _) = tint(app)
    val focus = remember { FocusRequester() }
    val showInstall = d.asset != null && !d.upToDate
    LaunchedEffect(d.loading, d.asset, d.installed) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(bright.copy(alpha = 0.5f), Color.Transparent))
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 112.dp)
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "${app.category}  •  github.com/${app.repo}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Text(
                app.description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxWidth(0.7f),
            )

            if (app.status != "active") {
                Text("⚠ ${app.status}: ${app.statusNote.orEmpty()}", color = Color(0xFFFFC107))
            }
            if (app.warning) {
                WarningBox(vm.warningText, app.warningNote)
            }

            if (d.loading) {
                Text("Checking latest release…", color = Color.White.copy(alpha = 0.7f))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.release?.let { Pill("Latest ${it.tagName}") }
                    Pill(if (d.installed != null) "Installed ${d.installed}" else "Not installed")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (showInstall) {
                        Button(
                            onClick = { vm.requestInstall() },
                            enabled = d.busy == null,
                            modifier = Modifier.focusRequester(focus),
                            colors = ButtonDefaults.colors(
                                containerColor = Accent,
                                contentColor = Color.Black,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color.Black,
                            ),
                        ) { Text(if (d.installed == null) "Install" else "Update", fontWeight = FontWeight.Bold) }
                    }
                    if (d.installed != null) {
                        Button(
                            onClick = { vm.launch() },
                            modifier = if (showInstall) Modifier else Modifier.focusRequester(focus),
                        ) { Text("Open") }
                    }
                    if (vm.canUninstall(app)) {
                        Button(
                            onClick = { vm.uninstall() },
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                contentColor = Color(0xFFFF8A80),
                                focusedContainerColor = Color(0xFFFF5252),
                                focusedContentColor = Color.Black,
                            ),
                        ) { Text("Uninstall") }
                    }
                }
            }

            d.busy?.let { b ->
                Text(b + (d.progress?.let { " ${(it * 100).toInt()}%" } ?: ""))
                d.progress?.let { p ->
                    Box(
                        Modifier.fillMaxWidth(0.5f).height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Accent)
                        )
                    }
                }
            }
            d.message?.let { Text(it, color = Color.White.copy(alpha = 0.85f)) }
        }
    }
}

// ---------- system-changes warning ----------

@Composable
fun WarningBox(text: String, note: String?) {
    Column(
        Modifier.fillMaxWidth(0.7f)
            .clip(RoundedCornerShape(12.dp))
            .background(Amber.copy(alpha = 0.14f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚠  $text", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        if (!note.isNullOrBlank()) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Shown instead of the app page when Install is pressed on a warned app. Focus starts on Cancel. */
@Composable
fun ConfirmWarning(vm: MainViewModel, app: CatalogApp) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Text(
            "⚠  Before you install ${app.name}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Amber,
        )
        WarningBox(vm.warningText, app.warningNote)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { vm.cancelConfirm() }, modifier = Modifier.focusRequester(cancelFocus)) {
                Text("Cancel")
            }
            Button(
                onClick = { vm.confirmInstall() },
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Amber,
                    focusedContainerColor = Amber,
                    focusedContentColor = Color.Black,
                ),
            ) { Text("I understand – install") }
        }
    }
}
