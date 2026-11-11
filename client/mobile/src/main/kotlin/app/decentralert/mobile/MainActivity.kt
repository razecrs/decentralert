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
import kotlinx.coroutines.CancellationException
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
    var startingSession by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf<DecentralertNetwork?>(null) }

    LaunchedEffect(vault) { store.saveVault(vault) }

    suspend fun ensureCredentials(): DeviceCredentials {
        credentials?.let { return it }
        error("Create your Decentralert username first")
    }

    LaunchedEffect(credentials?.deviceId) {
        while (true) {
            credentials?.let { auth ->
                runCatching { api.network(auth) }.onSuccess { network = it }
            }
            delay(2_000)
        }
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
        if (granted) {
            startingSession = true
            startRequest++
        } else {
            error = "Location permission is required to create an SOS session."
        }
    }

    LaunchedEffect(startRequest) {
        if (startRequest == 0) return@LaunchedEffect
        error = null
        if (vault.profile.displayName.isBlank()) {
            error = "Complete your emergency profile before starting SOS."
            section = AppSection.PROFILE
            return@LaunchedEffect
        }
        try {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: "Could not create the server SOS session"
        } finally {
            startingSession = false
        }
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
        if (credentials == null) {
            OnboardingScreen(
                initialName = vault.profile.displayName,
                error = error,
                onCreate = { displayName, username ->
                    scope.launch {
                        error = null
                        syncStatus = "Creating account"
                        runCatching {
                            val registered = api.register(username)
                            val updatedVault = vault.copy(
                                profile = vault.profile.copy(displayName = displayName),
                                updatedAt = Instant.now().toString(),
                            )
                            store.saveCredentials(registered)
                            store.saveVault(updatedVault)
                            api.saveVault(registered, updatedVault)
                            credentials = registered
                            vault = updatedVault
                            syncStatus = "Vault synced"
                        }.onFailure {
                            error = it.message ?: "Could not create Decentralert account"
                            syncStatus = "Account setup failed"
                        }
                    }
                },
            )
            return@MaterialTheme
        }
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
                AppHeader(syncStatus, credentials?.username)
                error?.let { ErrorBanner(it) }
                when (section) {
                    AppSection.SOS -> SosScreen(
                        state = state,
                        vault = vault,
                        category = selectedCategory,
                        activeSession = activeSession,
                        inboundAlert = network?.alerts?.firstOrNull(),
                        connectedCount = network?.connections?.size ?: 0,
                        preparing = startingSession,
                        onCategory = { selectedCategory = it },
                        onActivate = {
                            if (!startingSession) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    startingSession = true
                                    startRequest++
                                } else {
                                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            }
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
                        username = credentials?.username.orEmpty(),
                        network = network,
                        onVaultChange = { vault = it },
                        onSave = { scope.launch { runCatching { syncVault() }.onFailure { error = it.message } } },
                        onInvite = { target ->
                            val auth = credentials
                            if (auth != null) scope.launch {
                                runCatching { api.invite(auth, target) }
                                    .onSuccess { network = api.network(auth) }
                                    .onFailure { error = it.message }
                            }
                        },
                        onAccept = { inviteId ->
                            val auth = credentials
                            if (auth != null) scope.launch {
                                runCatching { api.acceptInvite(auth, inviteId) }
                                    .onSuccess { network = api.network(auth) }
                                    .onFailure { error = it.message }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    initialName: String,
    error: String?,
    onCreate: (String, String) -> Unit,
) {
    var displayName by remember { mutableStateOf(initialName) }
    var username by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(54.dp).background(Mint, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text("D", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(24.dp))
        Text("Join Decentralert", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Text("Create a private device session and a username your trusted people can add.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        VaultField("Display name, test data only", displayName) { displayName = it }
        Spacer(Modifier.height(12.dp))
        VaultField("Decentralert username", username) {
            username = it.lowercase().filter { character -> character.isLetterOrDigit() || character == '_' }.take(24)
        }
        Text("3 to 24 lowercase letters, numbers, or underscores", color = Muted, fontSize = 11.sp)
        error?.let { Spacer(Modifier.height(12.dp)); ErrorBanner(it) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onCreate(displayName.trim(), username.trim()) },
            enabled = displayName.isNotBlank() && username.length in 3..24,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create secure device session") }
        Spacer(Modifier.height(14.dp))
        Text("Prototype account only. No password recovery or verified identity yet.", color = Amber, fontSize = 11.sp)
    }
}

@Composable
private fun AppHeader(syncStatus: String, username: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("D", color = Ink, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(10.dp))
        Column { Text("DECENTRALERT", color = Color.White, fontWeight = FontWeight.Black); Text(username?.let { "@$it" } ?: "Emergency Vault", color = Muted, fontSize = 11.sp) }
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
    inboundAlert: InAppAlert?,
    connectedCount: Int,
    preparing: Boolean,
    onCategory: (EmergencyCategory) -> Unit,
    onActivate: () -> Unit,
    onCancel: () -> Unit,
    onSendNow: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        inboundAlert?.let { alert ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = .18f)),
                modifier = Modifier.fillMaxWidth().border(1.dp, Danger, RoundedCornerShape(16.dp)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("LIVE SOS FROM @${alert.senderUsername}", color = Danger, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Text("${alert.senderDisplayName} · ${alert.category.name.lowercase().replaceFirstChar { it.uppercase() }}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Location ${alert.location.latitude}, ${alert.location.longitude}", color = Mint, fontSize = 12.sp)
                    val medical = (alert.profile.conditions + alert.profile.allergies).joinToString().ifBlank { "No shared medical flags" }
                    Text(medical, color = Muted, fontSize = 12.sp)
                    Text(if (alert.offlineEscalation) "Phone heartbeat lost" else "User confirmed SOS", color = Amber, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Text("$connectedCount Decentralert connections · ${if (vault.escalation.escalateWhenHeartbeatLost) "offline handoff on" else "offline handoff off"}", color = if (vault.escalation.escalateWhenHeartbeatLost) Amber else Muted, fontSize = 12.sp)
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
                Button(onClick = onActivate, enabled = !preparing, modifier = Modifier.size(210.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color(0xFF341229))) { Text(if (preparing) "Preparing" else "SOS", fontSize = if (preparing) 22.sp else 44.sp, fontWeight = FontWeight.Black) }
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
                Text("${activeSession?.notificationAttempts?.size ?: 0} Emergency Circle delivery attempts completed", color = Color.White)
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
        item { Text("The local vault and token are protected by Android Keystore. Server-side end-to-end encryption and authority review are still required for production.", color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun CircleScreen(
    vault: EmergencyVault,
    username: String,
    network: DecentralertNetwork?,
    onVaultChange: (EmergencyVault) -> Unit,
    onSave: () -> Unit,
    onInvite: (String) -> Unit,
    onAccept: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var contactUsername by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Emergency Circle", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Accepted Decentralert connections receive an authenticated in-app SOS.", color = Muted, fontSize = 12.sp) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Mint.copy(alpha = .10f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("YOUR DECENTRALERT CODE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("@$username", color = Mint, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("Share this username with someone you trust.", color = Muted, fontSize = 11.sp)
                }
            }
        }
        if (!network?.incomingInvites.isNullOrEmpty()) {
            item { SectionTitle("Connection requests") }
            items(network.incomingInvites, key = { "invite-${it.id}" }) { invite ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().border(1.dp, Amber, RoundedCornerShape(14.dp))) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("@${invite.fromUsername}", color = Color.White, fontWeight = FontWeight.Bold); Text("wants to add you as a trusted contact", color = Muted, fontSize = 11.sp) }
                        Button(onClick = { onAccept(invite.id) }) { Text("Accept") }
                    }
                }
            }
        }
        item { SectionTitle("Add trusted person") }
        item { VaultField("Contact name", name) { name = it } }
        item { VaultField("Their Decentralert username", contactUsername) { value -> contactUsername = value.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(24) } }
        item { VaultField("Phone number", phone) { phone = it } }
        item { VaultField("Relationship", relationship) { relationship = it } }
        item {
            Button(
                enabled = name.isNotBlank() && phone.isNotBlank() && contactUsername.length in 3..24 && vault.circle.size < 12,
                onClick = {
                    val target = contactUsername.trim()
                    val member = CircleMember(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(),
                        phone = phone.trim(),
                        relationship = relationship.ifBlank { null },
                        decentralertUsername = target,
                        priority = vault.circle.size + 1,
                    )
                    onVaultChange(vault.copy(circle = vault.circle + member))
                    onInvite(target)
                    name = ""; phone = ""; relationship = ""; contactUsername = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add and send invite") }
        }
        items(vault.circle, key = { it.id }) { member ->
            val connected = member.decentralertUsername?.let { it in network?.connections.orEmpty() } == true
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(14.dp))) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(member.name, color = Color.White, fontWeight = FontWeight.Bold); Text("@${member.decentralertUsername} · ${member.relationship ?: "Trusted contact"}", color = Muted, fontSize = 12.sp) }; Text(if (connected) "CONNECTED" else "INVITED", color = if (connected) Mint else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { onVaultChange(vault.copy(circle = vault.circle.filterNot { it.id == member.id })) }) { Text("Remove", color = Danger) }
                }
            }
        }
        item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save and sync Emergency Circle") } }
        item { Text("Decentralert in-app delivery is live while both apps are online. Calls, SMS, push, and government dispatch remain simulated.", color = Muted, fontSize = 11.sp) }
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
