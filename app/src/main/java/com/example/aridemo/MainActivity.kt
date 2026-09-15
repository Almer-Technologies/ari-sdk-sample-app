package com.example.aridemo

import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * What this app can do, as spoken phrases. Always on screen so a demo viewer can
 * see what to say without being coached, so keep it in step with the tools
 * [AriToolService] declares — that registry is what Ari exposes to the model.
 */
private val VOICE_COMMANDS = listOf(
    "add a blue circle" to "adds one with a new number",
    "remove circle 2" to "removes it by its number",
    "remove the purple circles" to "removes every purple one",
    "change circle 3 to yellow" to "recolours just that one",
    "change the colour to purple" to "recolours every circle",
    "what circles do I have on screen?" to "reads back the circles",
    "show me circle 3" to "opens this app on it, with no service call",
)

/**
 * The screen, and the target of the `show_circle` deeplink.
 *
 * `show_circle` is declared with a `uri` and no handler, so Ari never binds
 * [AriToolService] for it — it fills `aridemo://circle/{number}`, fires it as
 * `ACTION_VIEW`, and the manifest filter routes it here. A partner whose tools
 * are all deeplinks writes no service at all.
 */
class MainActivity : ComponentActivity() {

    /**
     * The circle the last deeplink asked for, or null when the app was opened from
     * the launcher. Not a circle that exists — the screen still looks it up.
     */
    private var requestedCircle by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedCircle = CircleDeeplink.circleNumber(intent?.dataString)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CirclesScreen(requestedCircle)
                }
            }
        }
    }

    /**
     * Where a second "show me circle 4" arrives. The activity is `singleTask`, so
     * Ari's link reaches the instance already on screen and [onCreate] does not
     * run again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedCircle = CircleDeeplink.circleNumber(intent.dataString)
    }
}

@Composable
private fun CirclesScreen(requestedCircle: Int?) {
    val circles by CircleState.circles.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The number shown is the number to say, for the life of the circle.
            circles.forEach { circle ->
                NumberedCircle(
                    number = circle.number,
                    colorName = circle.colorName,
                    highlighted = circle.number == requestedCircle,
                )
            }
        }

        Text(
            text = "${circles.size} of ${CircleState.MAX_CIRCLES} circles " +
                "· numbers don't change",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )

        if (requestedCircle != null) {
            DeeplinkBanner(
                number = requestedCircle,
                exists = circles.any { circle -> circle.number == requestedCircle },
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        VoiceCommandHelp(modifier = Modifier.padding(top = 24.dp))
    }
}

/**
 * Proof the deeplink arrived: Ari opened this app with none of our code running
 * first. Says which number, because a number Ari's rule allowed is still one
 * this app has to check.
 */
@Composable
private fun DeeplinkBanner(number: Int, exists: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = when {
            exists -> "Ari opened this app on circle $number."
            else -> "Ari asked for circle $number, which isn't on screen."
        },
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = when {
            exists -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        },
        modifier = modifier,
    )
}

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
private fun NumberedCircle(number: Int, colorName: String, highlighted: Boolean) {
    val fill = CircleState.colorOf(colorName) ?: Color.Gray
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(fill)
            // The palette includes white, invisible against the surface, so every
            // circle is outlined; `show_circle`'s highlight is the same ring, thicker.
            .border(
                width = if (highlighted) 6.dp else 2.dp,
                color = when {
                    highlighted -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                shape = CircleShape,
            ),
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
