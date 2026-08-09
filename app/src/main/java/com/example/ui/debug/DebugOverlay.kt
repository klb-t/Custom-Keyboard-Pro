package com.example.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.Point
import com.example.domain.sensitivity.DecisionMetrics
import com.example.domain.sensitivity.KeyProbabilityDistribution

data class FieldDisplaySettings(
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val gamma: Float = 1.0f,
    val alpha: Float = 0.7f,
    val palette: String = "DEFAULT",
    val minDisplayRange: Float = 0f,
    val maxDisplayRange: Float = 1f
)

enum class DiagnosticViewMode {
    NORMAL_KEYBOARD,
    RAW_SENSITIVITY,
    EFFECTIVE_SENSITIVITY,
    WINNER_REGION,
    ENTROPY,
    ADAPTATION,
    COMPOSITE
}

@Composable
fun DebugOverlay(
    lastTap: Point?,
    distribution: KeyProbabilityDistribution?,
    winner: String?,
    metrics: DecisionMetrics?,
    modifier: Modifier = Modifier
) {
    if (lastTap == null && distribution == null) return

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(8.dp)
    ) {
        lastTap?.let {
            Text("Tap: (${it.x.toInt()}, ${it.y.toInt()})", color = Color.White, fontSize = 12.sp)
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        distribution?.candidates?.take(3)?.forEach { cand ->
            Text("${cand.elementId}  ${String.format("%.2f", cand.score)}", color = Color.LightGray, fontSize = 12.sp)
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        if (winner != null && metrics != null) {
            Text("winner: $winner", color = Color.Green, fontSize = 12.sp)
            metrics.margin?.let { margin ->
                Text("margin: ${String.format("%.2f", margin)}", color = Color.Yellow, fontSize = 12.sp)
            }
        } else {
            Text("winner: FALLBACK", color = Color.Red, fontSize = 12.sp)
        }
    }
}
