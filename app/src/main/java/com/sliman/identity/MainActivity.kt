package com.sliman.identity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sliman.identity.ui.AppItem
import com.sliman.identity.ui.AppListScreen
import com.sliman.identity.ui.EditorScreen
import com.sliman.identity.ui.SlimanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProfileStore(this)
        setContent { SlimanTheme { Root(store) } }
    }
}

@Composable
private fun Root(store: ProfileStore) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var selected by remember { mutableStateOf<AppItem?>(null) }
    val profiles = remember { mutableStateMapOf<String, Profile>().apply { putAll(store.loadAll()) } }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }
    BackHandler(enabled = selected != null) { selected = null }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val list = apps
        val sel = selected
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            sel == null -> AppListScreen(list, profiles) { selected = it }
            else -> EditorScreen(
                app = sel,
                initial = profiles[sel.pkg] ?: Presets.all.first().applyTo(Profile(sel.pkg)).withRandomIds(),
                exists = profiles.containsKey(sel.pkg),
                onSave = {
                    profiles[sel.pkg] = it
                    store.saveAll(profiles.toMap())
                    Toast.makeText(ctx, "Saved. Force-stop the app to apply.", Toast.LENGTH_LONG).show()
                    selected = null
                },
                onDelete = {
                    profiles.remove(sel.pkg)
                    store.saveAll(profiles.toMap())
                    selected = null
                },
                onBack = { selected = null },
            )
        }
    }
}

private fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    return pm.getInstalledApplications(0)
        .filter { it.packageName != ctx.packageName }
        .map {
            val system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            AppItem(it.packageName, it.loadLabel(pm).toString(), system)
        }
        .sortedBy { it.label.lowercase() }
}
