package app.decentralert.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.decentralert.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.round

private val Ink = Color(0xFF071018)
private val Panel = Color(0xFF0E202B)
private val Line = Color(0xFF25404E)
private val Mint = Color(0xFF4DE3B2)
private val Danger = Color(0xFFFF5E69)
private val Amber = Color(0xFFFFB547)
private val Muted = Color(0xFF91A8B4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EmergencyApp() }
    }
}

private enum class AppSection { SOS, PROFILE, CIRCLE }

@Composable
private fun EmergencyApp() {
    val context = LocalContext.current
    val store = remember { VaultStore(context) }
    val api = remember { VaultApi() }
    val coordinator = remember { SosCoordinator() }
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(AppSection.SOS) }
    var vault by remember { mutableStateOf(store.loadVault() ?: emptyVault()) }
    var credentials by remember { mutableStateOf(store.credentials()) }
    var syncStatus by remember { mutableStateOf(if (credentials == null) "Local only" else "Ready to sync") }
    var activeSession by remember { mutableStateOf<SosSession?>(null) }
    var selectedCategory by remember { mutableStateOf(EmergencyCategory.UNKNOWN) }
    var error by remember { mutableStateOf<String?>(null) }
    var startRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(vault) { store.saveVault(vault) }

    suspend fun ensureCredentials(): DeviceCredentials {
        credentials?.let { return it }
        syncStatus = "Registering device"
        val registered = api.register()
        store.saveCredentials(registered)
        credentials = registered
        return registered
    }

    suspend fun syncVault(): EmergencyVault {
        val current = vault.copy(updatedAt = Instant.now().toString())
        store.saveVault(current)
        syncStatus = "Syncing vault"
        val saved = api.saveVault(ensureCredentials(), current)
        vault = saved
        syncStatus = "Vault synced"
        return saved
    }

    suspend fun confirmActiveSession() {
        val session = activeSession ?: run {
            coordinator.markFailed("No server session exists")
            return
        }
        val auth = credentials ?: run {
            coordinator.markFailed("Device registration is missing")
            return
        }
        runCatching { api.confirm(auth, session.id) }
            .onSuccess {
                activeSession = it
                coordinator.markSent(it.incidentId ?: it.id)
            }
            .onFailure { coordinator.markFailed(it.message ?: "Confirmation failed") }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRequest++ else error = "Location permission is required to create an SOS session."
    }

    LaunchedEffect(startRequest) {
        if (startRequest == 0) return@LaunchedEffect
        error = null
        if (vault.profile.displayName.isBlank()) {
            error = "Complete your emergency profile before starting SOS."
            section = AppSection.PROFILE
            return@LaunchedEffect
        }
        runCatching {
            syncVault()
            val capturedLocation = currentLocation(context) ?: error("Location is unavailable. Enable location services and try again.")
            val location = capturedLocation.forConsent(vault.consent.sharePreciseLocation)
            val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            val session = api.createSession(
                ensureCredentials(),
                CreateSosSessionRequest(
                    category = selectedCategory,
                    location = location,
                    description = "Emergency Vault SOS",
                    sensorContext = SensorContext(
                        capturedAt = Instant.now().toString(),
                        batteryPercent = battery,
                        networkType = "android",
                    ),
                ),
            )
            activeSession = session
            coordinator.activate(selectedCategory)
        }.onFailure { error = it.message ?: "Could not create the server SOS session" }
    }

    LaunchedEffect(state, activeSession?.id) {
        val countdown = state as? SosState.Countdown ?: return@LaunchedEffect
        delay(1_000)
        if (countdown.secondsRemaining % 3 == 0) {
            val auth = credentials
            val session = activeSession
            if (auth != null && session != null) {
                runCatching { api.heartbeat(auth, session.id, HeartbeatRequest()) }
                    .onSuccess { activeSession = it }
            }
        }
        if (coordinator.tick()) confirmActiveSession()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(primary = Mint, secondary = Amber, background = Ink, surface = Panel),
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    AppSection.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = section == destination,
                            onClick = { section = destination },
                            icon = { Text(if (destination == AppSection.CIRCLE) "PEOPLE" else destination.name, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            label = null,
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Ink)) {
                AppHeader(syncStatus)
                error?.let { ErrorBanner(it) }
                when (section) {
                    AppSection.SOS -> SosScreen(
                        state = state,
                        vault = vault,
                        category = selectedCategory,
                        activeSession = activeSession,
                        onCategory = { selectedCategory = it },
                        onActivate = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startRequest++
                            else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onCancel = {
                            val session = activeSession
                            val auth = credentials
                            coordinator.cancel()
                            activeSession = null
                            if (session != null && auth != null) scope.launch { runCatching { api.cancel(auth, session.id) } }
                        },
                        onSendNow = {
                            if (coordinator.sendNow()) scope.launch { confirmActiveSession() }
                        },
                        onReset = { coordinator.reset(); activeSession = null },
                    )
                    AppSection.PROFILE -> ProfileScreen(
                        vault = vault,
                        onVaultChange = { vault = it },
                        onSave = { scope.launch { runCatching { syncVault() }.onFailure { error = it.message } } },
                    )
                    AppSection.CIRCLE -> CircleScreen(
                        vault = vault,
                        onVaultChange = { vault = it },
                        onSave = { scope.launch { runCatching { syncVault() }.onFailure { error = it.message } } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(syncStatus: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("D", color = Ink, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(10.dp))
        Column { Text("DECENTRALERT", color = Color.White, fontWeight = FontWeight.Black); Text("Emergency Vault", color = Muted, fontSize = 11.sp) }
        Spacer(Modifier.weight(1f))
        Text(syncStatus, color = if (syncStatus.contains("synced", true)) Mint else Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Text(message, color = Danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).background(Danger.copy(alpha = .12f), RoundedCornerShape(12.dp)).border(1.dp, Danger.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(12.dp), fontSize = 12.sp)
}

@Composable
private fun SosScreen(
    state: SosState,
    vault: EmergencyVault,
    category: EmergencyCategory,
    activeSession: SosSession?,
    onCategory: (EmergencyCategory) -> Unit,
    onActivate: () -> Unit,
    onCancel: () -> Unit,
    onSendNow: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${vault.circle.count { it.accepted }} accepted contacts · ${if (vault.escalation.escalateWhenHeartbeatLost) "offline handoff on" else "offline handoff off"}", color = if (vault.escalation.escalateWhenHeartbeatLost) Amber else Muted, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        when (val current = state) {
            SosState.Idle, SosState.Cancelled -> {
                Text("Need urgent help?", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("A server session is created before the countdown starts.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Text("Emergency type", color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(EmergencyCategory.UNKNOWN, EmergencyCategory.MEDICAL, EmergencyCategory.FIRE, EmergencyCategory.POLICE).forEach { option ->
                        FilterChip(selected = category == option, onClick = { onCategory(option) }, label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) })
                    }
                }
                Spacer(Modifier.height(30.dp))
                Button(onClick = onActivate, modifier = Modifier.size(210.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color(0xFF341229))) { Text("SOS", fontSize = 44.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.height(18.dp))
                Text(if (vault.profile.displayName.isBlank()) "Profile setup required" else "Vault ready for ${vault.profile.displayName}", color = if (vault.profile.displayName.isBlank()) Danger else Mint, fontSize = 12.sp)
            }
            is SosState.Countdown -> {
                Text("Server session active", color = Mint, fontWeight = FontWeight.Bold)
                Text("Sending in", color = Muted)
                Text("${current.secondsRemaining}", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black)
                Text("Location and your consented vault snapshot are stored. Heartbeats keep the session alive.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = onCancel) { Text("Cancel") }; Button(onClick = onSendNow) { Text("Send now") } }
            }
            is SosState.Sending -> { CircularProgressIndicator(); Spacer(Modifier.height(18.dp)); Text("Parallel emergency fan-out in progress", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            is SosState.Sent -> {
                Text("SOS confirmed", color = Mint, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("${activeSession?.notificationAttempts?.size ?: 0} Emergency Circle contacts notified in simulation", color = Color.White)
                Text("Police, fire, medical, and rescue adapters remain simulated.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(22.dp)); Button(onClick = onReset) { Text("Return to safety screen") }
            }
            is SosState.Failed -> { Text("Digital handoff failed", color = Danger, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(current.message, color = Muted); Spacer(Modifier.height(16.dp)); Text("Call 112 now", color = Color.White, fontWeight = FontWeight.Bold); Button(onClick = onReset) { Text("Reset") } }
        }
        Spacer(Modifier.weight(1f))
        Text("Prototype simulation · not connected to 112 · use test data only", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ProfileScreen(vault: EmergencyVault, onVaultChange: (EmergencyVault) -> Unit, onSave: () -> Unit) {
    val profile = vault.profile
    fun update(updated: EmergencyProfile) = onVaultChange(vault.copy(profile = updated))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Emergency profile", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Use test data in this public prototype.", color = Amber, fontSize = 12.sp) }
        item { VaultField("Full name", profile.displayName) { update(profile.copy(displayName = it)) } }
        item { VaultField("Date of birth", profile.dateOfBirth.orEmpty()) { update(profile.copy(dateOfBirth = it.ifBlank { null })) } }
        item { VaultField("Allergies, comma separated", profile.allergies.joinToString()) { update(profile.copy(allergies = csv(it))) } }
        item { VaultField("Medications, comma separated", profile.medications.joinToString()) { update(profile.copy(medications = csv(it))) } }
        item { VaultField("Medical conditions, comma separated", profile.conditions.joinToString()) { update(profile.copy(conditions = csv(it))) } }
        item { VaultField("Accessibility needs", profile.accessibilityNeeds.joinToString()) { update(profile.copy(accessibilityNeeds = csv(it))) } }
        item { VaultField("Blood type, self reported", profile.bloodTypeSelfReported.orEmpty()) { update(profile.copy(bloodTypeSelfReported = it.ifBlank { null })) } }
        item { VaultField("Preferred language", profile.preferredLanguage.orEmpty()) { update(profile.copy(preferredLanguage = it.ifBlank { null })) } }
        item { SectionTitle("Consent") }
        item { SettingSwitch("Share precise location", "Required for responder ranking", vault.consent.sharePreciseLocation) { onVaultChange(vault.copy(consent = vault.consent.copy(sharePreciseLocation = it))) } }
        item { SettingSwitch("Share medical profile", "Attach these essentials after confirmation", vault.consent.shareMedicalProfile) { onVaultChange(vault.copy(consent = vault.consent.copy(shareMedicalProfile = it))) } }
        item { SettingSwitch("Consented snapshots", "Planned, not captured by this build", vault.consent.consentedSnapshots) { onVaultChange(vault.copy(consent = vault.consent.copy(consentedSnapshots = it))) } }
        item { SettingSwitch("Offline emergency handoff", "Escalate if heartbeats stop for 15 seconds", vault.escalation.escalateWhenHeartbeatLost) { onVaultChange(vault.copy(escalation = vault.escalation.copy(escalateWhenHeartbeatLost = it))) } }
        item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save and sync Emergency Vault") } }
        item { Text("Vault access uses a device token. Production requires Android Keystore storage, end-to-end encryption, and authority review.", color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun CircleScreen(vault: EmergencyVault, onVaultChange: (EmergencyVault) -> Unit, onSave: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Emergency Circle", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Contacts run in parallel with simulated services.", color = Muted, fontSize = 12.sp) }
        item { VaultField("Contact name", name) { name = it } }
        item { VaultField("Phone number", phone) { phone = it } }
        item { VaultField("Relationship", relationship) { relationship = it } }
        item {
            Button(
                enabled = name.isNotBlank() && phone.isNotBlank() && vault.circle.size < 12,
                onClick = {
                    val member = CircleMember(id = UUID.randomUUID().toString(), name = name.trim(), phone = phone.trim(), relationship = relationship.ifBlank { null }, priority = vault.circle.size + 1)
                    onVaultChange(vault.copy(circle = vault.circle + member)); name = ""; phone = ""; relationship = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add contact") }
        }
        items(vault.circle, key = { it.id }) { member ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(14.dp))) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(member.name, color = Color.White, fontWeight = FontWeight.Bold); Text("${member.relationship ?: "Trusted contact"} · ${member.phone}", color = Muted, fontSize = 12.sp) }; Text(if (member.accepted) "ACCEPTED" else "INVITE NEEDED", color = if (member.accepted) Mint else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    SettingSwitch("Confirmed for this demo", "Real contacts must accept an invitation", member.accepted) { accepted -> onVaultChange(vault.copy(circle = vault.circle.map { if (it.id == member.id) it.copy(accepted = accepted) else it })) }
                    TextButton(onClick = { onVaultChange(vault.copy(circle = vault.circle.filterNot { it.id == member.id })) }) { Text("Remove", color = Danger) }
                }
            }
        }
        item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save and sync Emergency Circle") } }
        item { Text("Calls, SMS, and push notifications are simulated in this build. No real message is sent.", color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun VaultField(label: String, value: String, onValueChange: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
@Composable private fun SectionTitle(text: String) { Text(text, color = Mint, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(description, color = Muted, fontSize = 11.sp) }; Switch(checked = checked, onCheckedChange = onChecked) } }

private fun emptyVault() = EmergencyVault(profile = EmergencyProfile(displayName = ""), updatedAt = Instant.now().toString())
private fun csv(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
private fun GeoPoint.forConsent(sharePreciseLocation: Boolean): GeoPoint = if (sharePreciseLocation) {
    this
} else {
    copy(
        latitude = round(latitude * 1_000.0) / 1_000.0,
        longitude = round(longitude * 1_000.0) / 1_000.0,
        accuracyMeters = accuracyMeters.coerceAtLeast(150f),
    )
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private suspend fun currentLocation(context: Context): GeoPoint? = withContext(Dispatchers.Main) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val best = manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }.maxByOrNull { it.time }
    val location = if (best != null && System.currentTimeMillis() - best.time < 10 * 60 * 1_000) best else withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine<Location?> { continuation ->
            val provider = when { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER; manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER; else -> null }
            if (provider == null) { continuation.resume(null); return@suspendCancellableCoroutine }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }
    location?.let { GeoPoint(it.latitude, it.longitude, it.accuracy.coerceAtLeast(0f)) }
}
