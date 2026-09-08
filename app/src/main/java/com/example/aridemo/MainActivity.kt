package com.example.aridemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * What this app can do, as spoken phrases.
 *
 * Deliberately a static list, always on screen: a demo viewer should be able to
 * see what to say without being coached. Each entry is a phrase that really
 * works — keep it in step with the tools [AriToolService] declares, since that
 * registry is what Ari exposes to the model.
 */
private val VOICE_COMMANDS = listOf(
    "add a blue circle" to "adds one with a new number",
    "remove circle 2" to "asks you to confirm first",
    "remove the purple circles" to "removes every purple one",
    "change circle 3 to yellow" to "recolours just that one",
    "change the colour to purple" to "recolours every circle",
    "what's on screen?" to "reads back the circles",
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CirclesScreen()
                }
            }
        }
    }
}

@Composable
private fun CirclesScreen() {
    val circles by CircleState.circles.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The number shown IS the number to say, and it never changes for
            // the life of the circle — so removals leave gaps rather than
            // shifting everything, and a batch of removals stays correct.
            circles.forEach { circle ->
                NumberedCircle(number = circle.number, colorName = circle.colorName)
            }
        }

        Text(
            text = "${circles.size} of ${CircleState.MAX_CIRCLES} circles " +
                "· numbers don't change",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )

        VoiceCommandHelp(modifier = Modifier.padding(top = 24.dp))
    }
}

/** The always-visible list of what you can say. Never changes. */
@Composable
private fun VoiceCommandHelp(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Say \"Hey Ari\", then:",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        VOICE_COMMANDS.forEach { (phrase, effect) ->
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "“$phrase”",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "  —  $effect",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NumberedCircle(number: Int, colorName: String) {
    val fill = CircleState.colorOf(colorName) ?: Color.Gray
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(fill)
            // The palette includes white, which would otherwise be invisible
            // against the surface. Outline every circle so the shape reads
            // regardless of fill.
            .border(width = 2.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$number",
            // Fixed white vanished on yellow and white — pick per fill.
            color = CircleState.contrastingTextColor(fill),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
