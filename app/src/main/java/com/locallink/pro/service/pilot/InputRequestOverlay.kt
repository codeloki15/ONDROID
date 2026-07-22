package com.locallink.pro.service.pilot

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A soothing "User Input Requested" overlay drawn above every app (accessibility overlay). */
class InputRequestOverlay(
    private val ctx: Context,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var pulse: ObjectAnimator? = null

    fun show(question: String, reason: String?) {
        if (view != null) hide()
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2A"))
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(ctx).apply {
            text = "User Input Requested"
            setTextColor(Color.parseColor("#8AB4F8")); textSize = 16f
        })
        root.addView(TextView(ctx).apply {
            text = if (reason.isNullOrBlank()) question else "$question\n($reason)"
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val field = EditText(ctx).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Type your answer…"
        }
        root.addView(field)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(ctx).apply {
            text = "Cancel"; setOnClickListener { onCancel() }
        })
        row.addView(Button(ctx).apply {
            text = "Submit"; setOnClickListener { onSubmit(field.text.toString()) }
        })
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(root, lp)
        view = root
        pulse = ObjectAnimator.ofFloat(root, "alpha", 0.75f, 1f).apply {
            duration = 900; repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        view?.let { runCatching { wm.removeView(it) } }; view = null
    }
}
