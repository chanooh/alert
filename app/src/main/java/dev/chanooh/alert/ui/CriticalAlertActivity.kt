package dev.chanooh.alert.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chanooh.alert.alarm.CriticalAlarmService
import dev.chanooh.alert.alert.ActiveAlertStore
import dev.chanooh.alert.network.AckWorker
import dev.chanooh.alert.ui.theme.AlertTheme

class CriticalAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val title = intent.getStringExtra(CriticalAlarmService.EXTRA_TITLE).orEmpty()
            .ifBlank { "Critical alert" }
        val message = intent.getStringExtra(CriticalAlarmService.EXTRA_MESSAGE).orEmpty()
            .ifBlank { "Immediate attention required" }

        setContent {
            AlertTheme {
                CriticalAlertScreen(
                    title = title,
                    message = message,
                    onAcknowledge = {
                        val eventIds = ActiveAlertStore(applicationContext).drain()
                        // Persist ACK work before stopping the alarm service. If the
                        // process is killed immediately after the tap, WorkManager
                        // has the best chance to retain the user's acknowledgement.
                        eventIds.forEach { eventId ->
                            AckWorker.enqueue(applicationContext, eventId)
                        }
                        CriticalAlarmService.stop(this)
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }
}

@Composable
private fun CriticalAlertScreen(
    title: String,
    message: String,
    onAcknowledge: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CRITICAL",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Black,
                fontSize = 36.sp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(40.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = onAcknowledge) {
                Text("Acknowledge & stop")
            }
        }
    }
}
