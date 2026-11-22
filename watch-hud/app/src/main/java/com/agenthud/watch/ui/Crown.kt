package com.agenthud.watch.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs

/**
 * Routes the Pixel Watch crown. Screens register the scroll area that should follow the crown
 * ([CrownTarget]); the newest active registration wins. A turn scrolls that area while it can
 * move in that direction, otherwise it pages the main pager (unless the target is modal, such
 * as Controls, which only scrolls).
 */
class CrownRouter {
    internal class Target(val scroll: ScrollState, val modal: Boolean)

    internal val targets = mutableStateListOf<Target>()

    /** Set while the main pager is on screen; called with +1 / -1 to move a page. */
    var pager: (suspend (Int) -> Unit)? = null

    /** Set while Live Mode is active (and no modal scroll overlay is open); receives raw rotary delta. */
    var rawListener: ((Float) -> Unit)? = null

    private var accumulated = 0f
    private var paging = false

    /** Handles one rotary event. Returns the suspend work to run, or null if nothing to do. */
    fun onRotate(dy: Float): (suspend () -> Unit)? {
        val top = targets.lastOrNull()
        if (top != null) {
            val canMove = (dy > 0 && top.scroll.canScrollForward) || (dy < 0 && top.scroll.canScrollBackward)
            if (canMove) {
                accumulated = 0f
                return { top.scroll.scrollBy(dy) }
            }
            if (top.modal) return null
        }
        val raw = rawListener
        if (raw != null) {
            accumulated = 0f
            return { raw(dy) }
        }
        val pg = pager ?: return null
        if (paging) return null
        // Reset when the turn reverses so a small back-and-forth never flips a page.
        if (accumulated != 0f && (accumulated > 0) != (dy > 0)) accumulated = 0f
        accumulated += dy
        if (abs(accumulated) < PAGE_THRESHOLD_PX) return null
        val dir = if (accumulated > 0) 1 else -1
        accumulated = 0f
        paging = true
        return {
            try { pg(dir) } finally { paging = false }
        }
    }

    companion object {
        /** Roughly two crown detents. */
        const val PAGE_THRESHOLD_PX = 110f
    }
}

val LocalCrownRouter = staticCompositionLocalOf { CrownRouter() }

/** Makes [scroll] follow the crown while [active]. [modal] stops the crown from paging past it. */
@Composable
fun CrownTarget(scroll: ScrollState, active: Boolean = true, modal: Boolean = false) {
    val router = LocalCrownRouter.current
    DisposableEffect(router, scroll, active, modal) {
        if (!active) return@DisposableEffect onDispose {}
        val target = CrownRouter.Target(scroll, modal)
        router.targets.add(target)
        onDispose { router.targets.remove(target) }
    }
}
