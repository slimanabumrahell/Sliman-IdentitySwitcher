package com.sliman.identity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sliman.identity.Gen
import com.sliman.identity.Presets
import com.sliman.identity.Profile
import com.sliman.identity.withRandomIds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    app: AppItem,
    initial: Profile,
    exists: Boolean,
    onSave: (Profile) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var p by remember { mutableStateOf(initial) }
    var tab by remember { mutableIntStateOf(0) }

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
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 12.dp))
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
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("Device", "Identity", "SIM & Net").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    0 -> DeviceTab(p) { p = it }
                    1 -> IdentityTab(p) { p = it }
                    else -> NetworkTab(p) { p = it }
                }
                Note("Changes apply the next time the target app starts. Force-stop it after saving, and make sure it is ticked in Vector's module scope.")
            }
        }
    }
}

@Composable
private fun DeviceTab(p: Profile, update: (Profile) -> Unit) {
    SectionCard("Preset") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Presets.all) { preset ->
                FilterChip(
                    selected = p.model == preset.model,
                    onClick = { update(preset.applyTo(p)) },
                    label = { Text(preset.name) },
                )
            }
        }
    }
    SectionCard("Build properties") {
        Field("Manufacturer", p.manufacturer) { update(p.copy(manufacturer = it)) }
        Field("Brand", p.brand) { update(p.copy(brand = it)) }
        Field("Model", p.model) { update(p.copy(model = it)) }
        Field("Device", p.device) { update(p.copy(device = it)) }
        Field("Product", p.product) { update(p.copy(product = it)) }
        Field("Board", p.board) { update(p.copy(board = it)) }
        Field("Hardware", p.hardware) { update(p.copy(hardware = it)) }
        Field("Fingerprint", p.fingerprint) { update(p.copy(fingerprint = it)) }
    }
}

@Composable
private fun IdentityTab(p: Profile, update: (Profile) -> Unit) {
    SectionCard("Identifiers") {
        Field("Android ID", p.androidId, mono = true, onRegen = { update(p.copy(androidId = Gen.androidId())) }) { update(p.copy(androidId = it)) }
        Field("IMEI", p.imei, mono = true, onRegen = { update(p.copy(imei = Gen.imei())) }) { update(p.copy(imei = it)) }
        Field("Serial", p.serial, mono = true, onRegen = { update(p.copy(serial = Gen.serial())) }) { update(p.copy(serial = it)) }
    }
    RandomizeAll(p, update)
}

@Composable
private fun NetworkTab(p: Profile, update: (Profile) -> Unit) {
    SectionCard("SIM") {
        Field("IMSI", p.imsi, mono = true, onRegen = { update(p.copy(imsi = Gen.imsi())) }) { update(p.copy(imsi = it)) }
        Field("SIM serial (ICCID)", p.simSerial, mono = true, onRegen = { update(p.copy(simSerial = Gen.simSerial())) }) { update(p.copy(simSerial = it)) }
    }
    SectionCard("Network") {
        Field("Wi-Fi MAC", p.wifiMac, mono = true, onRegen = { update(p.copy(wifiMac = Gen.mac())) }) { update(p.copy(wifiMac = it)) }
    }
    RandomizeAll(p, update)
}

@Composable
private fun RandomizeAll(p: Profile, update: (Profile) -> Unit) {
    OutlinedButton(
        onClick = { update(p.withRandomIds()) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Default.Refresh, null)
        Text("  Randomize all identifiers")
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
private fun Field(
    label: String,
    value: String,
    mono: Boolean = false,
    onRegen: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
        trailingIcon = if (onRegen != null) {
            { IconButton(onClick = onRegen) { Icon(Icons.Default.Refresh, "Randomize") } }
        } else null,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
