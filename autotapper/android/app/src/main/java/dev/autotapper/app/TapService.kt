package dev.autotapper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Injects taps. An AccessibilityService is the only way to dispatch a touch into
 * another app without root, and the user has to enable it by hand in Settings -
 * there is no way to grant it programmatically, by design.
 */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We already subscribe to window-state changes; recording the package is
        // free and turns "why did it see nothing?" into a one-line answer. It
        // needs no window-content permission.
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrEmpty()) foregroundPackage = pkg
    }
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: TapService? = null
            private set

        /** Package of the most recent window to come forward, or null if unknown. */
        @Volatile
        var foregroundPackage: String? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
