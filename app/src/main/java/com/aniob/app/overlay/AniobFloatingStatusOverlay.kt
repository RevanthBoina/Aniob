package com.aniob.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draggable floating status surface drawn as an accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY).
 *
 * Shows current step and narration, offers a stop control, and is draggable so it never blocks
 * the interactive area the agent is automating. Because it uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * with [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE], it does not steal window focus or require the
 * SYSTEM_ALERT_WINDOW permission.
 */
class AniobFloatingStatusOverlay(
    private val service: AccessibilityService,
    private val onStopRequested: () -> Unit
) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var stepTextView: TextView? = null
    private var narrationTextView: TextView? = null

    private var params: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show(stepIndex: Int, narration: String) {
        mainHandler.post {
            try {
                if (overlayView == null) {
                    val density = service.resources.displayMetrics.density
                    val dp = { value: Int -> (value * density).toInt() }

                    val root = LinearLayout(service).apply {
                        orientation = LinearLayout.VERTICAL
                        val bg = GradientDrawable().apply {
                            setColor(Color.parseColor("#EE1E222A"))
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), Color.parseColor("#446B7280"))
                        }
                        background = bg
                        setPadding(dp(14), dp(10), dp(14), dp(12))
                        elevation = dp(8).toFloat()
                    }

                    // Header row: Step badge + Stop button
                    val headerRow = LinearLayout(service).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val stepText = TextView(service).apply {
                        text = if (stepIndex > 0) "Step $stepIndex" else "Starting task..."
                        setTextColor(Color.parseColor("#60A5FA")) // Blue accent
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    stepTextView = stepText

                    val spacer = View(service).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    }

                    val stopButton = Button(service).apply {
                        text = "Stop"
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        val btnBg = GradientDrawable().apply {
                            setColor(Color.parseColor("#DC2626")) // Red
                            cornerRadius = dp(8).toFloat()
                        }
                        background = btnBg
                        minHeight = dp(36)
                        minimumHeight = dp(36)
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        setOnClickListener {
                            onStopRequested()
                        }
                    }

                    headerRow.addView(stepText)
                    headerRow.addView(spacer)
                    headerRow.addView(stopButton)

                    // Narration body
                    val narrationText = TextView(service).apply {
                        text = narration.ifBlank { "Running..." }
                        setTextColor(Color.parseColor("#E5E7EB"))
                        textSize = 12f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(6), 0, 0)
                    }
                    narrationTextView = narrationText

                    root.addView(headerRow)
                    root.addView(narrationText)

                    val layoutParams = WindowManager.LayoutParams(
                        dp(260),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = dp(16)
                        y = dp(80)
                    }
                    params = layoutParams

                    // Make draggable
                    var initialX = 0
                    var initialY = 0
                    var touchStartX = 0f
                    var touchStartY = 0f

                    root.setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = layoutParams.x
                                initialY = layoutParams.y
                                touchStartX = event.rawX
                                touchStartY = event.rawY
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (event.rawX - touchStartX).toInt()
                                val dy = (event.rawY - touchStartY).toInt()
                                layoutParams.x = initialX + dx
                                layoutParams.y = initialY + dy
                                try {
                                    windowManager?.updateViewLayout(root, layoutParams)
                                } catch (_: Exception) {}
                                true
                            }
                            else -> false
                        }
                    }

                    windowManager?.addView(root, layoutParams)
                    overlayView = root
                } else {
                    update(stepIndex, narration)
                }
            } catch (_: Exception) {
                // Non-blocking: overlay failure must never crash or block automation
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun update(stepIndex: Int, narration: String) {
        mainHandler.post {
            try {
                stepTextView?.text = if (stepIndex > 0) "Step $stepIndex" else "In progress..."
                if (narration.isNotBlank()) {
                    narrationTextView?.text = narration
                }
            } catch (_: Exception) {}
        }
    }

    fun hide() {
        mainHandler.post {
            try {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                }
            } catch (_: Exception) {}
            overlayView = null
            stepTextView = null
            narrationTextView = null
            params = null
        }
    }
}
