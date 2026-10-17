package rw.aurum.pathtracer

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF2EE6C8)
val Glass = Color(0xB3101418)
val Warn = Color(0xFFFFC857)
val Danger = Color(0xFFFF6B6B)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Glass, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** A status chip with a pulsing coloured dot. */
@Composable
fun Pill(label: String, color: Color, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        modifier.background(Glass, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).alpha(pulse).background(color, CircleShape))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Reticle(modifier: Modifier) {
    val scale by rememberInfiniteTransition(label = "reticle").animateFloat(
        initialValue = 0.85f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scale",
    )
    Box(modifier.size(64.dp).scale(scale).border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(Accent, CircleShape))
    }
}

@Composable
fun BlockerScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White, textAlign = TextAlign.Center, fontSize = 18.sp)
    }
}

/**
 * An arrow seen from above. [turn] is in radians, positive to the left: 0 points straight up,
 * a right turn bends the head to the right. With [bent] the shaft goes up first and then turns,
 * like a road sign; otherwise the whole arrow is rotated.
 */
@Composable
fun TurnArrow(turn: Float?, size: Dp, modifier: Modifier = Modifier, color: Color = Color.White, bent: Boolean = true) {
    val degrees = Math.toDegrees((turn ?: 0f).toDouble()).toFloat()
    Canvas(modifier.size(size).rotate(if (bent) 0f else -degrees)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tip: Offset
        val path = Path()
        val headAngle: Double
        if (bent && turn != null && kotlin.math.abs(degrees) > 20f) {
            // Shaft up from the bottom, then a bend toward the turn.
            val dir = if (degrees > 0) -1f else 1f
            val sharp = kotlin.math.abs(degrees) >= 150f
            path.moveTo(w * 0.5f, w * 0.92f)
            path.lineTo(w * 0.5f, w * 0.45f)
            if (sharp) {
                path.cubicTo(w * 0.5f, w * 0.1f, w * (0.5f + dir * 0.32f), w * 0.1f, w * (0.5f + dir * 0.32f), w * 0.45f)
                path.lineTo(w * (0.5f + dir * 0.32f), w * 0.6f)
                tip = Offset(w * (0.5f + dir * 0.32f), w * 0.66f)
                headAngle = Math.PI / 2
            } else {
                val a = Math.toRadians(kotlin.math.abs(degrees).toDouble().coerceAtMost(120.0))
                val len = w * 0.36f
                val ex = w * 0.5f + dir * (len * kotlin.math.sin(a)).toFloat()
                val ey = w * 0.45f - (len * kotlin.math.cos(a)).toFloat()
                path.lineTo(ex, ey)
                tip = Offset(ex, ey)
                headAngle = -Math.PI / 2 + dir * a
            }
        } else {
            path.moveTo(w * 0.5f, w * 0.9f)
            path.lineTo(w * 0.5f, w * 0.14f)
            tip = Offset(w * 0.5f, w * 0.1f)
            headAngle = -Math.PI / 2
        }
        drawPath(path, color, style = stroke)
        val head = Path().apply {
            val l = w * 0.24f
            fun at(offset: Double) = Offset(
                tip.x - (l * kotlin.math.cos(headAngle + offset)).toFloat(),
                tip.y - (l * kotlin.math.sin(headAngle + offset)).toFloat(),
            )
            val left = at(0.6)
            val right = at(-0.6)
            moveTo(left.x, left.y)
            lineTo(tip.x, tip.y)
            lineTo(right.x, right.y)
        }
        drawPath(head, color, style = stroke)
    }
}
