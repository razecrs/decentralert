package app.decentralert.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.decentralert.shared.EmergencyCategory
import app.decentralert.shared.SosCoordinator
import app.decentralert.shared.SosState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EmergencyApp() }
    }
}

@Composable
private fun EmergencyApp() {
    val coordinator = remember { SosCoordinator() }
    val state by coordinator.state.collectAsState()
    LaunchedEffect(state) {
        while (state is SosState.Countdown) { delay(1_000); if (coordinator.tick()) break }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4DE3B2), background = Color(0xFF071018))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF071018)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("DECENTRALERT", color = Color.White, fontWeight = FontWeight.Black); Spacer(Modifier.weight(1f)); Text("NL · 112", color = Color(0xFF91A8B4)) }
            when (val current = state) {
                SosState.Idle, SosState.Cancelled -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Need urgent help?", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text("Pressing SOS starts a visible 30-second confirmation.", color = Color(0xFF91A8B4)); Spacer(Modifier.height(36.dp)); Button(onClick = { coordinator.activate() }, modifier = Modifier.size(210.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5E69))) { Text("SOS", fontSize = 44.sp, fontWeight = FontWeight.Black) } }
                }
                is SosState.Countdown -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Sending in", color = Color(0xFF91A8B4)); Text("${current.secondsRemaining}", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black); Text("Location and consented snapshots are being prepared", color = Color(0xFF91A8B4)); Spacer(Modifier.height(24.dp)); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = coordinator::cancel) { Text("Cancel") }; Button(onClick = { coordinator.sendNow() }) { Text("Send now") } } }
                }
                is SosState.Sending -> Text("Contacting configured responders…", color = Color.White, fontSize = 22.sp)
                is SosState.Sent -> Text("Help request sent\n${current.incidentId}", color = Color(0xFF4DE3B2), fontSize = 22.sp)
                is SosState.Failed -> Text("Could not send digitally. Call 112 now.\n${current.message}", color = Color(0xFFFF5E69), fontSize = 22.sp)
            }
            Text("Prototype · always call 112 when every second counts", color = Color(0xFF91A8B4), fontSize = 12.sp)
        }
    }
}
