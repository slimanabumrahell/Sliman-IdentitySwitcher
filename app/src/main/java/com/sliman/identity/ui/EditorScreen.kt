package com.sliman.identity.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab as TabItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sliman.identity.Carriers
import com.sliman.identity.FieldDef
import com.sliman.identity.Fields
import com.sliman.identity.Gen
import com.sliman.identity.Kind
import com.sliman.identity.Presets
import com.sliman.identity.Profile
import com.sliman.identity.Tab
import com.sliman.identity.Transfer
import com.sliman.identity.withRandomIds
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    app: AppItem,
    initial: Profile,
    exists: Boolean,
    io: FileIO,
    onSave: (Profile) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var p by remember { mutableStateOf(initial) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val tabs = Tab.values()
    val current = tabs[tabIndex]

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(app.label, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(app.pkg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    Switch(checked = p.enabled, onCheckedChange = { p = p.copy(enabled = it) })
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Export profile") }, onClick = {
                                menu = false
                                io.export(Transfer.fileName(p), Transfer.exportProfile(p))
                            })
                            DropdownMenuItem(text = { Text("Import profile") }, onClick = {
                                menu = false
                                io.import { text ->
                                    val q = Transfer.parseForEditor(text, app.pkg)
                                    if (q == null) {
                                        Toast.makeText(ctx, "Not a valid Sliman-Identity profile file", Toast.LENGTH_LONG).show()
                                    } else {
                                        p = q.copy(enabled = p.enabled)
                                        Toast.makeText(ctx, "Imported. Press Save profile to keep it.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            })
                            DropdownMenuItem(text = { Text("Randomize all identifiers") }, onClick = {
                                menu = false; p = p.withRandomIds()
                            })
                            DropdownMenuItem(text = { Text("Reset everything to Real") }, onClick = {
                                menu = false; p = Profile(app.pkg, p.enabled, p.name)
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (exists) {
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Remove") }
                    }
                    Button(
                        onClick = { onSave(p) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Save profile") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tabIndex, containerColor = Color.Transparent, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    TabItem(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(t.title) })
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    Tab.DEVICE -> { NameCard(p) { p = it }; PresetCard(p) { p = it } }
                    Tab.SIM -> CarrierCard(p) { p = it }
                    else -> {}
                }
                FieldsFor(current, p) { p = it }
                if (current == Tab.IDENTITY || current == Tab.SIM) RandomizeAll(p) { p = it }
                Note(noteFor(current))
            }
        }
    }
}

private fun noteFor(tab: Tab): String = when (tab) {
    Tab.SYSTEM -> "Real = untouched. Changing SDK_INT can crash apps that rely on the real API level - leave it Real unless you know you need it. " +
        "Changes apply the next time the target app starts."
    Tab.PRIVACY -> "Blocked lists are returned empty to the target app. Changes apply the next time the target app starts."
    else -> "Real = untouched, Static = your value, Random = new value every launch (use the ⋮ menu on a field). " +
        "Changes apply the next time the target app starts. Force-stop it after saving, and make sure it is ticked in Vector's module scope."
}

@Composable
private fun NameCard(p: Profile, update: (Profile) -> Unit) {
    SectionCard("Profile") {
        OutlinedTextField(
            value = p.name,
            onValueChange = { update(p.copy(name = it)) },
            label = { Text("Profile name") },
            placeholder = { Text("e.g. Work phone, Test A") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PresetCard(p: Profile, update: (Profile) -> Unit) {
    var includeOs by remember { mutableStateOf(false) }
    val selected = Presets.matching(p)
    SectionCard("Preset") {
        Presets.groups.forEach { g ->
            Text(g, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Presets.all.filter { it.group == g }) { preset ->
                    FilterChip(
                        selected = preset == selected,
                        onClick = {
                            var q = preset.applyTo(p, includeOs)
                            // Serial format depends on the brand: re-roll a static serial for the new model.
                            val s = q["serial"]
                            if (s.isNotBlank() && s != Profile.RANDOM) q = q.set("serial", Gen.serial(q))
                            update(q)
                        },
                        label = { Text(preset.name) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Also set Android version")
                Text(
                    "RELEASE / SDK / patch of the model. May not match your real OS.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = includeOs, onCheckedChange = { includeOs = it })
        }
    }
}

@Composable
private fun CarrierCard(p: Profile, update: (Profile) -> Unit) {
    SectionCard("Carrier pack") {
        Text(
            "Sets operator, country, IMSI, ICCID, phone number, timezone and locale together so they agree.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Carriers.all) { c ->
                FilterChip(
                    selected = p["simOperator"] == c.mccMnc,
                    onClick = { update(Carriers.applyTo(p, c)) },
                    label = { Text("${c.name} (${c.iso.uppercase()})") },
                )
            }
        }
    }
}

@Composable
private fun FieldsFor(tab: Tab, p: Profile, update: (Profile) -> Unit) {
    Fields.all.filter { it.tab == tab }.groupBy { it.section }.forEach { (section, defs) ->
        SectionCard(section) {
            defs.forEach { d ->
                if (d.kind == Kind.FLAG) FlagRow(d, p, update) else TextRow(d, p, update)
            }
        }
    }
}

@Composable
private fun TextRow(d: FieldDef, p: Profile, update: (Profile) -> Unit) {
    val v = p[d.key]
    val random = v == Profile.RANDOM
    var menu by remember { mutableStateOf(false) }

    val support: String? = when {
        random -> "Random every launch"
        v.isBlank() -> "Real (unchanged)"
        d.isTime -> v.toLongOrNull()?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
        else -> null
    }

    val supportContent: (@Composable () -> Unit)? =
        if (support != null) ({ Text(support, fontSize = 11.sp) }) else null

    OutlinedTextField(
        value = if (random) "Random every launch" else v,
        onValueChange = { update(p.set(d.key, it)) },
        readOnly = random,
        label = { Text(d.label) },
        supportingText = supportContent,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        textStyle = if (d.mono && !random) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d.gen != null) {
                    IconButton(onClick = { update(p.set(d.key, d.gen.invoke(p))) }) { Icon(Icons.Default.Refresh, "Generate now") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mode") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Real (don't change)") }, onClick = { menu = false; update(p.set(d.key, "")) })
                        if (d.gen != null) {
                            DropdownMenuItem(text = { Text("Random every launch") }, onClick = { menu = false; update(p.set(d.key, Profile.RANDOM)) })
                            DropdownMenuItem(text = { Text("Generate now (static)") }, onClick = { menu = false; update(p.set(d.key, d.gen.invoke(p))) })
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun FlagRow(d: FieldDef, p: Profile, update: (Profile) -> Unit) {
    val on = p[d.key] == "1"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.label)
            Text(
                if (on) d.flagLabel else "Real",
                fontSize = 12.sp,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = { update(p.set(d.key, if (it) "1" else "")) })
    }
}

@Composable
private fun RandomizeAll(p: Profile, update: (Profile) -> Unit) {
    OutlinedButton(
        onClick = { update(p.withRandomIds()) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Default.Refresh, null)
        Spacer(Modifier.padding(start = 6.dp))
        Text("Randomize all identifiers")
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
