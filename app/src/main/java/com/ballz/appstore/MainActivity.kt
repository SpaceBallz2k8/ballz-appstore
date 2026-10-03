@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ballz.appstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val sel = vm.selected
                    if (sel == null) HomeScreen(vm) else DetailScreen(vm, sel)
                    BackHandler(enabled = sel != null) { vm.back() }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val grouped = vm.apps.groupBy { it.category }.toSortedMap()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("Ballz Store", style = MaterialTheme.typography.headlineLarge)
        if (vm.offline) Text("Offline – showing saved catalog", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        if (grouped.isEmpty()) {
            Text(if (vm.loading) "Loading…" else "No apps in the catalog yet.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(grouped.entries.toList()) { (category, list) ->
                Column {
                    Text(category, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(list, key = { it.id }) { app -> AppCard(app) { vm.open(app) } }
                    }
                }
            }
        }
    }
}

@Composable
fun AppCard(app: CatalogApp, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(240.dp).height(150.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(
                app.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DetailScreen(vm: MainViewModel, app: CatalogApp) {
    val d = vm.detail
    val focus = remember { FocusRequester() }
    LaunchedEffect(d.loading, d.asset) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(app.name, style = MaterialTheme.typography.headlineLarge)
        Text(app.description, style = MaterialTheme.typography.bodyLarge)
        Text("github.com/${app.repo}", style = MaterialTheme.typography.bodySmall)
        if (app.status != "active") {
            Text("⚠ ${app.status}: ${app.statusNote.orEmpty()}")
        }

        when {
            d.loading -> Text("Checking latest release…")
            else -> {
                d.release?.let { Text("Latest: ${it.tagName}   Installed: ${d.installed ?: "–"}") }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (d.asset != null && !(d.upToDate)) {
                        Button(
                            onClick = { vm.installOrUpdate() },
                            enabled = d.busy == null,
                            modifier = Modifier.focusRequester(focus),
                        ) { Text(if (d.installed == null) "Install" else "Update") }
                    }
                    if (d.installed != null) {
                        Button(onClick = { vm.launch() }) { Text("Open") }
                    }
                }
            }
        }
        d.busy?.let { b ->
            Text(b + (d.progress?.let { " ${(it * 100).toInt()}%" } ?: ""))
        }
        d.message?.let { Text(it) }
    }
}
