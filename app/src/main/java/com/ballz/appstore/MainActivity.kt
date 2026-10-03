@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ballz.appstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
private val BgTop = Color(0xFF111827)
private val BgBottom = Color(0xFF05070A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
                    ) {
                        val sel = vm.selected
                        if (sel == null) HomeScreen(vm) else DetailScreen(vm, sel)
                        BackHandler(enabled = sel != null) { vm.back() }
                    }
                }
            }
        }
    }
}

// ---------- helpers ----------

/** Stable per-app colour pair (bright, dark) derived from the id. */
private fun tint(app: CatalogApp): Pair<Color, Color> {
    val h = abs(app.id.hashCode() % 360).toFloat()
    return Color.hsv(h, 0.55f, 0.55f) to Color.hsv((h + 40f) % 360f, 0.65f, 0.22f)
}

/** Catalog icon if given, otherwise the repo owner's GitHub avatar. */
private fun iconUrl(app: CatalogApp): String =
    app.icon ?: "https://github.com/${app.repo.substringBefore('/')}.png?size=160"

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
                    if (vm.offline) "Offline – showing saved catalog" else "${vm.apps.size} apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            Button(onClick = { vm.refresh() }, enabled = !vm.loading) {
                Text(if (vm.loading) "Refreshing…" else "Refresh")
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
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(grouped.entries.toList()) { (category, list) ->
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
                        items(list, key = { it.id }) { app -> AppCard(app) { vm.open(app) } }
                    }
                }
            }
        }
    }
}

@Composable
fun AppCard(app: CatalogApp, onClick: () -> Unit) {
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            app.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (app.status != "active") {
                            Text("⚠ ${app.status}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFC107))
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
                            onClick = { vm.installOrUpdate() },
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
