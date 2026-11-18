package app.trove.debrid.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toIntSize

/**
 * True backdrop blur for the floating Search control — a bounded
 * snapshot of the list content behind it, blurred and redrawn inside
 * the button's circle, with the icon kept sharp on top.
 *
 * How it works: the Downloads content is recorded into one
 * [GraphicsLayer] (one screen-sized buffer) whenever the capture
 * version/content changes. The caller uses a scrim during scrolling
 * and captures again when it settles. The Search backdrop
 * draws that layer shifted by the button's offset inside the content,
 * through a [RenderEffect] blur. One in-flight capture, at most one
 * buffer; nothing is persisted and Settings is never captured.
 *
 * Fallback: API < 31 (no RenderEffect) or low-RAM devices keep the
 * existing translucent surface — no capture, no extra memory.
 * Blur is therefore a progressive enhancement, not a promise on
 * every supported device.
 */

/** True blur is available on API 31+ on normal-memory devices. */
@Composable
fun rememberBackdropBlurEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember false
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.isLowRamDevice != true
    }
}

/** Mutable capture bookkeeping. A plain holder (not Compose state):
 *  it is only written during draw, which must never write state. */
class CaptureTick {
    private var lastVersion: Int = -1
    private var lastContent: Any? = null

    fun needsCapture(version: Int, content: Any): Boolean =
        lastVersion != version || lastContent !== content

    fun captured(version: Int, content: Any) {
        lastVersion = version
        lastContent = content
    }
}

/** The captured backdrop behind the Search button: the recorded
 *  content layer, this button's offset inside that content (px), and
 *  the blur effect (null where blur is off). */
data class SearchBackdrop(
    val layer: GraphicsLayer,
    val offset: Offset,
    val blurEffect: RenderEffect?,
    /** Checked during drawing, after the source has recorded this frame. */
    val isCurrent: () -> Boolean = { true },
)

/** Records the content into [layer] when [version], [content], or size changed, then
 *  draws the content live. Reading [version] (Compose state) inside
 *  draw subscribes the redraw; writing [tick] is a plain-var write. */
fun Modifier.backdropSource(
    layer: GraphicsLayer,
    version: Int,
    tick: CaptureTick,
    content: Any = Unit,
): Modifier = drawWithContent {
    if (tick.needsCapture(version, content) || layer.size != size.toIntSize()) {
        // Labeled receiver: the record block is a plain DrawScope,
        // which has no drawContent(); the outer drawWithContent scope
        // (a ContentDrawScope) does. Its drawContext is retargeted
        // to the layer while the block runs, so the content lands in
        // the layer, not on screen.
        layer.record { this@drawWithContent.drawContent() }
        tick.captured(version, content)
    }
    drawContent()
}

/** The blur effect for the backdrop, or null where blur is off.
 *  Uses Compose's [BlurEffect] (a [RenderEffect] wrapper around the
 *  platform blur); the icon and border are drawn outside it, so they
 *  stay sharp. */
@Composable
fun rememberBackdropBlurEffect(): RenderEffect? {
    val enabled = rememberBackdropBlurEnabled()
    return remember(enabled) {
        if (!enabled) null else BlurEffect(16f, 16f)
    }
}

/** Draws the captured [layer] inside this composable, shifted so the
 *  recorded content lines up with what sits behind it on screen,
 *  then tints it so the frosted surface stays legible. The caller
 *  clips to the button shape and applies the RenderEffect. */
fun Modifier.backdropImage(
    layer: GraphicsLayer,
    /** This composable's position inside the captured content, px. */
    offsetInContent: Offset,
    surfaceColor: androidx.compose.ui.graphics.Color,
    tintAlpha: Float = 0.35f,
    isCurrent: () -> Boolean = { true },
): Modifier = drawWithContent {
    if (isCurrent()) {
        translate(-offsetInContent.x, -offsetInContent.y) { drawLayer(layer) }
        drawRect(surfaceColor.copy(alpha = tintAlpha))
    } else {
        drawRect(surfaceColor.copy(alpha = 0.92f))
    }
}
