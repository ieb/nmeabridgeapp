package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.service.SourceType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared bottom navigation bar used by every top-level screen (Nav,
 * Polar, Engine, Battery, Settings). Sub-screens (EngineGraphs, and the
 * Polar editor state) hide this bar and show an in-screen top back
 * button instead.
 *
 * The connection-state dot on the far left reflects the same three-way
 * state the nav screen showed before: green (running + GATT up + nav
 * live), amber (running but stream stale), grey (stopped).
 *
 * Icons are drawn with Compose Canvas rather than pulled from a material
 * icon library: the core icon set shipped with material3 lacks good
 * matches for a compass rose, tachometer, polar plot and battery glyph,
 * and we don't want to add the 15 MB material-icons-extended dep for
 * five symbols.
 */
@Composable
fun AppBottomBar(
    viewModel: ServerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.serviceState.collectAsState()
    val current by viewModel.currentScreen.collectAsState()
    val navLive = state.navigationState != null
    val linkUp = state.bluetoothConnected || state.sourceType == SourceType.SIMULATOR
    val dotColor = when {
        state.isRunning && linkUp && navLive -> Color(0xFF4CAF50)  // green
        state.isRunning -> Color(0xFFFFA000)                       // amber
        else -> Color(0xFF757575)                                  // grey
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Connection dot + TCP client count on the far left.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(4.dp))
            if (state.isRunning) {
                Text(
                    text = "TCP:${state.connectedClients}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Nav destinations — evenly spaced so they fill the bar.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavButton(
                    selected = current == Screen.NAV,
                    onClick = { viewModel.setCurrentScreen(Screen.NAV) },
                    icon = { NavCompassIcon(it) }
                )
                NavButton(
                    selected = current == Screen.POLAR,
                    onClick = { viewModel.setCurrentScreen(Screen.POLAR) },
                    icon = { PolarIcon(it) }
                )
                NavButton(
                    selected = current == Screen.ENGINE,
                    onClick = { viewModel.setCurrentScreen(Screen.ENGINE) },
                    icon = { TachometerIcon(it) }
                )
                NavButton(
                    selected = current == Screen.BATTERY,
                    onClick = { viewModel.setCurrentScreen(Screen.BATTERY) },
                    icon = { BatteryIcon(it) }
                )
                NavButton(
                    selected = current == Screen.SETTINGS,
                    onClick = { viewModel.setCurrentScreen(Screen.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = it,
                            modifier = Modifier.size(ICON_SIZE),
                        )
                    }
                )
            }
        }
    }
}

private val ICON_SIZE = 28.dp

/**
 * A tap target in the bottom bar. Takes its icon as a slot so the call
 * site passes a function receiving the tint (selected or unselected) —
 * lets each icon draw itself in the same size and colour without
 * duplicating size / colour plumbing.
 */
@Composable
private fun NavButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

/**
 * Compass rose — outer circle with four cardinal tick marks and a
 * solid north-pointing triangle. Echoes the app icon.
 */
@Composable
private fun NavCompassIcon(tint: Color) {
    Canvas(modifier = Modifier.size(ICON_SIZE)) {
        val w = size.width
        val cx = w / 2f
        val cy = w / 2f
        val outer = w * 0.48f
        val inner = w * 0.16f
        // Outer circle
        drawCircle(color = tint, radius = outer, center = Offset(cx, cy), style = Stroke(width = w * 0.08f))
        // Cardinal ticks
        val tickLen = w * 0.12f
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            val rad = (angle - 90f) * PI.toFloat() / 180f
            val x1 = cx + cos(rad) * (outer - tickLen)
            val y1 = cy + sin(rad) * (outer - tickLen)
            val x2 = cx + cos(rad) * outer
            val y2 = cy + sin(rad) * outer
            drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = w * 0.08f)
        }
        // North-pointing triangle (needle)
        val needle = Path().apply {
            moveTo(cx, cy - outer * 0.7f)
            lineTo(cx - inner, cy + inner * 0.3f)
            lineTo(cx + inner, cy + inner * 0.3f)
            close()
        }
        drawPath(needle, color = tint)
        // Centre dot
        drawCircle(color = tint, radius = w * 0.05f, center = Offset(cx, cy))
    }
}

/**
 * Polar plot icon — a quarter-ellipse curve from forward to abeam,
 * inside a radial guide arc. Recognisable as the shape of a polar
 * performance curve.
 */
@Composable
private fun PolarIcon(tint: Color) {
    Canvas(modifier = Modifier.size(ICON_SIZE)) {
        val w = size.width
        val cx = w / 2f
        val cy = w * 0.62f
        val r = w * 0.44f
        val stroke = Stroke(width = w * 0.08f)
        // Polar-plot curve sampled parametrically: r(θ) = R * sin(θ) which
        // produces a teardrop-like arc typical of sailboat polars.
        val path = Path()
        var first = true
        val steps = 20
        for (i in 0..steps) {
            val theta = PI.toFloat() * i / steps
            val rr = r * sin(theta)
            val x = cx + cos(theta - PI.toFloat() / 2f) * rr
            val y = cy - sin(theta - PI.toFloat() / 2f) * rr
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        drawPath(path, tint, style = stroke)
        // Horizontal base line
        drawLine(tint, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = w * 0.06f)
    }
}

/**
 * Tachometer icon — arc gauge with tick marks and a needle pointing
 * up-right, suggesting a running engine.
 */
@Composable
private fun TachometerIcon(tint: Color) {
    Canvas(modifier = Modifier.size(ICON_SIZE)) {
        val w = size.width
        val cx = w / 2f
        val cy = w * 0.58f
        val r = w * 0.42f
        val sw = w * 0.08f
        // Bottom-open arc: 210° sweep from 165° → 375° (i.e. past 0°).
        // Rendered via lots of short line segments to avoid pulling in
        // drawArc's fiddly angle conventions.
        val path = Path()
        val start = 150f
        val sweep = 240f
        val steps = 24
        for (i in 0..steps) {
            val a = (start + sweep * i / steps) * PI.toFloat() / 180f
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint, style = Stroke(width = sw))
        // Needle — points to upper-right, like rising RPM.
        val needleAngle = (start + sweep * 0.75f) * PI.toFloat() / 180f
        val nx = cx + cos(needleAngle) * (r - sw)
        val ny = cy + sin(needleAngle) * (r - sw)
        drawLine(tint, Offset(cx, cy), Offset(nx, ny), strokeWidth = w * 0.1f)
        // Pivot
        drawCircle(tint, radius = w * 0.07f, center = Offset(cx, cy))
    }
}

/**
 * Battery icon — outlined rectangle with a positive terminal cap and a
 * partial fill suggesting charge.
 */
@Composable
private fun BatteryIcon(tint: Color) {
    Canvas(modifier = Modifier.size(ICON_SIZE)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.08f
        val bodyLeft = w * 0.18f
        val bodyRight = w * 0.82f
        val bodyTop = h * 0.25f
        val bodyBottom = h * 0.85f
        val capLeft = w * 0.38f
        val capRight = w * 0.62f
        val capTop = h * 0.15f
        // Terminal cap
        drawLine(tint, Offset(capLeft, capTop), Offset(capRight, capTop), strokeWidth = sw * 1.4f)
        // Body rectangle (four sides)
        drawLine(tint, Offset(bodyLeft, bodyTop), Offset(bodyRight, bodyTop), strokeWidth = sw)
        drawLine(tint, Offset(bodyLeft, bodyBottom), Offset(bodyRight, bodyBottom), strokeWidth = sw)
        drawLine(tint, Offset(bodyLeft, bodyTop), Offset(bodyLeft, bodyBottom), strokeWidth = sw)
        drawLine(tint, Offset(bodyRight, bodyTop), Offset(bodyRight, bodyBottom), strokeWidth = sw)
        // Fill: the bottom 60% of the body.
        val fillTop = bodyTop + (bodyBottom - bodyTop) * 0.4f
        drawRect(
            color = tint,
            topLeft = Offset(bodyLeft + sw, fillTop),
            size = androidx.compose.ui.geometry.Size(
                width = (bodyRight - bodyLeft) - 2 * sw,
                height = (bodyBottom - fillTop) - sw / 2f,
            )
        )
    }
}

/**
 * Scaffold every top-level screen with an identical layout:
 *   ┌──────────────────────────┐
 *   │ content (weight=1f,      │
 *   │         scrollable)      │
 *   │                          │
 *   ├──────────────────────────┤
 *   │ AppBottomBar (fixed)     │
 *   └──────────────────────────┘
 *
 * The content area fills remaining height and scrolls internally when it
 * overflows, so the nav bar always sits at the bottom of the window at
 * a consistent height across screens. Callers pass their screen body as
 * a `ColumnScope` lambda so they can still use weighted children inside
 * — the outer scroll only kicks in when the children's intrinsic heights
 * exceed the available space.
 */
@Composable
fun TopLevelScreen(
    viewModel: ServerViewModel,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f, fill = true),
            content = content,
        )
        AppBottomBar(viewModel = viewModel)
    }
}

/**
 * Top bar for sub-screens that replaces the bottom nav bar with a back
 * control. Used by screens that are logically "inside" a top-level
 * screen (e.g. EngineGraphs is a sub-screen of Engine). Title centred
 * between a "< Back" button and a spacer so the visual layout is
 * symmetric.
 */
@Composable
fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack) { Text("< Back") }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(48.dp))
    }
}
