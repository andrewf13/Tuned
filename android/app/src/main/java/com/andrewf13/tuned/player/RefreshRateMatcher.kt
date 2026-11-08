package com.andrewf13.tuned.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.mutableStateOf

/**
 * Switches the TV's refresh rate to suit the video by setting the window's
 * preferred display mode. On the Shield this needs Settings → Display & Sound →
 * Advanced → "Match content frame rate" turned on.
 */
class RefreshRateMatcher(private val activity: Activity) {

    val currentMode = mutableStateOf(describe(display()?.mode))

    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { currentMode.value = describe(display()?.mode) }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    init {
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun display(): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.display }.getOrNull()?.let { return it }
        }
        @Suppress("DEPRECATION")
        return activity.windowManager.defaultDisplay
    }

    private fun Display.Mode.spec() = ModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

    fun match(fps: Float) {
        val d = display() ?: return
        val target = FrameRates.choose(d.supportedModes.map { it.spec() }, d.mode.spec(), fps) ?: return
        setPreferred(target.id)
    }

    /** Hand the display back to the system default (e.g. 60 Hz for the launcher). */
    fun reset() = setPreferred(0)

    private fun setPreferred(id: Int) {
        val lp = activity.window.attributes
        if (lp.preferredDisplayModeId == id) return
        lp.preferredDisplayModeId = id
        activity.window.attributes = lp
    }

    fun supportedModes(): List<String> =
        display()?.supportedModes
            ?.sortedWith(compareByDescending<Display.Mode> { it.physicalWidth }.thenBy { it.refreshRate })
            ?.map { describe(it) } ?: emptyList()

    fun release() = displayManager.unregisterDisplayListener(listener)

    private fun describe(m: Display.Mode?): String =
        if (m == null) "unknown" else "${m.physicalWidth}×${m.physicalHeight} @ ${"%.3f".format(m.refreshRate).trimEnd('0').trimEnd('.')} Hz"
}
