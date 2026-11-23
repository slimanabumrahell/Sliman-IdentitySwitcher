package com.sliman.identity.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.sliman.identity.Profile

data class AppItem(val pkg: String, val label: String, val system: Boolean)

@Composable
fun AppListScreen(
    apps: List<AppItem>,
    profiles: Map<String, Profile>,
    onExportAll: () -> Unit,
    onImportAll: () -> Unit,
    onOpen: (AppItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var onlyActive by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }

    val activeCount = profiles.values.count { it.enabled }
    val list = apps.filter { app ->
        (showSystem || !app.system || profiles.containsKey(app.pkg)) &&
            (!onlyActive || profiles[app.pkg]?.enabled == true) &&
            (query.isBlank() || app.label.contains(query, true) || app.pkg.contains(query, true))
    }

    Column(Modifier.fillMaxSize()) {
        Header(activeCount, onExportAll, onImportAll)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps or package name") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = onlyActive, onClick = { onlyActive = !onlyActive }, label = { Text("Active only") })
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("System apps") })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.pkg }) { app ->
                AppRow(app, profiles[app.pkg]) { onOpen(app) }
            }
        }
    }
}

@Composable
private fun Header(activeCount: Int, onExportAll: () -> Unit, onImportAll: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1840), Color(0xFF0B0D12))))
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 14.dp),
    ) {
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Export all profiles") }, onClick = { menu = false; onExportAll() })
                DropdownMenuItem(text = { Text("Import profiles") }, onClick = { menu = false; onImportAll() })
            }
        }
        Column {
            Text("Sliman-Identity", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Per-app device identity switcher",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(Modifier.size(10.dp))
            Pill("$activeCount active", MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun AppRow(app: AppItem, profile: Profile?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(app.pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(icon, null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(Modifier.size(46.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.pkg,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOf(profile?.name.orEmpty(), profile?.deviceLabel.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                if (profile != null && sub.isNotBlank()) {
                    Text(
                        sub,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            if (profile != null) {
                Pill(
                    if (profile.enabled) "ON" else "OFF",
                    if (profile.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
