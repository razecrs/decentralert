package app.decentralert.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.decentralert.shared.*
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ink = Color(0xFF071018)
private val Panel = Color(0xFF0C1923)
private val PanelRaised = Color(0xFF102431)
private val Line = Color(0xFF1F3A49)
private val Mint = Color(0xFF4DE3B2)
private val Amber = Color(0xFFFFB547)
private val Danger = Color(0xFFFF5E69)
private val TextPrimary = Color(0xFFF3F8FA)
private val TextMuted = Color(0xFF91A8B4)

@Composable
fun DispatchApp(api: DispatchApi = remember { DispatchApi() }) {
    var incidents by remember { mutableStateOf<List<Incident>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var apiState by remember { mutableStateOf("Connecting") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { api.incidents() }
            .onSuccess { loaded ->
                incidents = loaded
                if (selectedId == null) selectedId = loaded.firstOrNull()?.id
                apiState = "Live"
                error = null
            }
            .onFailure { apiState = "Offline"; error = it.message ?: "API unavailable" }
    }

    LaunchedEffect(Unit) {
        while (true) { refresh(); delay(3_000) }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(primary = Mint, secondary = Amber, background = Ink, surface = Panel),
        typography = Typography(bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
            val compact = maxWidth < 900.dp
            if (compact) {
                CompactLayout(incidents, selectedId, apiState, error, { selectedId = it }) {
                    scope.launch { createDemo(api); refresh() }
                }
            } else {
                DesktopLayout(incidents, selectedId, apiState, error, { selectedId = it }) {
                    scope.launch { createDemo(api); refresh() }
                }
            }
        }
    }
}

private suspend fun createDemo(api: DispatchApi) {
    api.create(
        CreateIncidentRequest(
            idempotencyKey = "dashboard-${window.performance.now()}",
            category = EmergencyCategory.UNKNOWN,
            location = GeoPoint(52.3728, 4.8936, 7f),
            description = "Person unresponsive near Dam Square",
            profile = EmergencyProfile(
                displayName = "Demo citizen",
                allergies = listOf("Penicillin"),
                conditions = listOf("Asthma"),
                preferredLanguage = "nl-NL",
            ),
        )
    )
}

@Composable
private fun DesktopLayout(incidents: List<Incident>, selectedId: String?, apiState: String, error: String?, onSelect: (String) -> Unit, onDemo: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(apiState, onDemo)
        Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IncidentQueue(incidents, selectedId, onSelect, Modifier.width(330.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SituationMap(incidents, Modifier.weight(1f).fillMaxWidth())
                error?.let { OfflineBanner(it) }
            }
            IncidentDetails(incidents.firstOrNull { it.id == selectedId }, Modifier.width(360.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun CompactLayout(incidents: List<Incident>, selectedId: String?, apiState: String, error: String?, onSelect: (String) -> Unit, onDemo: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(apiState, onDemo)
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { item { OfflineBanner(it) } }
            item { SituationMap(incidents, Modifier.fillMaxWidth().height(260.dp)) }
            items(incidents) { IncidentCard(it, it.id == selectedId) { onSelect(it.id) } }
            item { IncidentDetails(incidents.firstOrNull { it.id == selectedId }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun Header(apiState: String, onDemo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(78.dp).background(Panel).border(1.dp, Line).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("D", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column { Text("DECENTRALERT", color = TextPrimary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text("Dispatch coordination", color = TextMuted, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        StatusPill(apiState)
        Spacer(Modifier.width(14.dp))
        Button(onClick = onDemo, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) { Text("Create demo incident", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun StatusPill(text: String) {
    val color = if (text == "Live") Mint else if (text == "Offline") Danger else Amber
    Row(Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).border(1.dp, color.copy(alpha = .35f), RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(7.dp)); Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun IncidentQueue(incidents: List<Incident>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("INCIDENT QUEUE", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.weight(1f)); Text("${incidents.size} active", color = Mint, fontSize = 12.sp) }
        Spacer(Modifier.height(14.dp))
        if (incidents.isEmpty()) EmptyState() else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(incidents, key = { it.id }) { IncidentCard(it, it.id == selectedId) { onSelect(it.id) } } }
    }
}

@Composable
private fun IncidentCard(incident: Incident, selected: Boolean, onClick: () -> Unit) {
    val accent = categoryColor(incident.category)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (selected) PanelRaised else Ink.copy(alpha = .5f), RoundedCornerShape(14.dp)).border(1.dp, if (selected) accent else Line, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(incident.category.name.replace('_', ' '), color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f)); Text(incident.status.name.replace('_', ' '), color = TextMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp)); Text(incident.description ?: "No description", color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Spacer(Modifier.height(9.dp)); Text("${incident.dispatchAttempts.size} services · ${incident.responderOffers.size} responders · ${incident.emergencyCircleNotified} circle", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SituationMap(incidents: List<Incident>, modifier: Modifier) {
    Box(modifier.background(Panel, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val grid = 44.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(Line.copy(alpha = .35f), Offset(x, 0f), Offset(x, size.height), 1f); x += grid }
            var y = 0f
            while (y < size.height) { drawLine(Line.copy(alpha = .35f), Offset(0f, y), Offset(size.width, y), 1f); y += grid }
            drawCircle(Mint.copy(alpha = .06f), size.minDimension * .30f, center)
            drawCircle(Mint.copy(alpha = .25f), size.minDimension * .30f, center, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            incidents.forEachIndexed { index, incident ->
                val px = size.width * (.30f + ((index * 37) % 55) / 100f)
                val py = size.height * (.25f + ((index * 23) % 50) / 100f)
                drawCircle(categoryColor(incident.category), 9.dp.toPx(), Offset(px, py))
                drawCircle(categoryColor(incident.category).copy(alpha = .25f), 22.dp.toPx(), Offset(px, py), style = Stroke(3f))
            }
        }
        Column(Modifier.padding(18.dp)) { Text("LIVE SITUATION MAP", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp); Text("Amsterdam · simulated coverage", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Row(Modifier.align(Alignment.BottomStart).padding(18.dp).background(Ink.copy(alpha = .88f), RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Mint, CircleShape)); Spacer(Modifier.width(7.dp)); Text("Verified responders", color = TextMuted, fontSize = 11.sp); Spacer(Modifier.width(14.dp)); Box(Modifier.size(8.dp).background(Danger, CircleShape)); Spacer(Modifier.width(7.dp)); Text("Incidents", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun IncidentDetails(incident: Incident?, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(18.dp)) {
        Text("INCIDENT DETAIL", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
        if (incident == null) { Spacer(Modifier.height(30.dp)); EmptyState(); return@Column }
        Spacer(Modifier.height(14.dp)); Text(incident.profile.displayName, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("ID ${incident.id.take(8).uppercase()}", color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp)); SectionLabel("MEDICAL ESSENTIALS")
        DetailRow("Allergies", incident.profile.allergies.ifEmpty { listOf("None reported") }.joinToString())
        DetailRow("Medication", incident.profile.medications.ifEmpty { listOf("None reported") }.joinToString())
        DetailRow("Conditions", incident.profile.conditions.ifEmpty { listOf("None reported") }.joinToString())
        Spacer(Modifier.height(18.dp)); SectionLabel("SERVICE FAN-OUT")
        incident.dispatchAttempts.forEach { attempt -> DetailRow(attempt.service.replaceFirstChar { it.uppercase() }, attempt.status.replace('_', ' '), if (attempt.status == "acknowledged") Mint else Amber) }
        if (incident.emergencyCircleNotified > 0 || incident.offlineEscalation) {
            Spacer(Modifier.height(18.dp)); SectionLabel("EMERGENCY HANDOFF")
            DetailRow("Circle", "${incident.emergencyCircleNotified} notified", Mint)
            DetailRow("Trigger", if (incident.offlineEscalation) "heartbeat lost" else "user confirmed", if (incident.offlineEscalation) Amber else TextPrimary)
        }
        Spacer(Modifier.height(18.dp)); SectionLabel("NEARBY RESPONSE")
        incident.responderOffers.take(3).forEach { offer -> DetailRow(offer.displayName, "${offer.distanceMeters} m · ${offer.estimatedArrivalSeconds / 60 + 1} min") }
        Spacer(Modifier.weight(1f)); Text("SIMULATION ONLY · NOT CONNECTED TO 112", color = Danger, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, color = Mint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = .8.sp); Spacer(Modifier.height(8.dp)) }
@Composable private fun DetailRow(label: String, value: String, valueColor: Color = TextPrimary) { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(105.dp)); Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)) } }
@Composable private fun EmptyState() { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("No active incidents", color = TextPrimary, fontWeight = FontWeight.Bold); Text("Create a demo incident to test fan-out.", color = TextMuted, fontSize = 12.sp) } }
@Composable private fun OfflineBanner(message: String) { Row(Modifier.fillMaxWidth().background(Danger.copy(alpha = .10f), RoundedCornerShape(12.dp)).border(1.dp, Danger.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(12.dp)) { Text("API OFFLINE · $message", color = Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp) } }
private fun categoryColor(category: EmergencyCategory) = when (category) { EmergencyCategory.MEDICAL -> Color(0xFFFF6D7A); EmergencyCategory.FIRE -> Color(0xFFFF8C42); EmergencyCategory.POLICE -> Color(0xFF61A8FF); EmergencyCategory.RESCUE -> Color(0xFFF4D35E); EmergencyCategory.MULTI_SERVICE, EmergencyCategory.UNKNOWN -> Mint }

