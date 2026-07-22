package com.locallink.pro.service.pilot

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.locallink.pro.R

/** A soothing "User Input Requested" overlay drawn above every app (accessibility overlay). */
class InputRequestOverlay(
    private val ctx: Context,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var pulse: ObjectAnimator? = null

    private fun dp(v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun font(res: Int): Typeface? = runCatching { ResourcesCompat.getFont(ctx, res) }.getOrNull()

    fun show(question: String, reason: String?) {
        if (view != null) hide()
        val pad = dp(20f)
        val semibold = font(R.font.epilogue_semibold)
        val regular = font(R.font.epilogue_regular)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                setColor(Color.parseColor("#221F20"))
                setStroke(dp(1f), Color.parseColor("#66AC1ED6"))
            }
            setPadding(pad, pad, pad, pad)
            elevation = dp(12f).toFloat()
        }
        root.addView(TextView(ctx).apply {
            text = "Omni needs your input"
            setTextColor(Color.parseColor("#C957E8")); textSize = 16f
            typeface = semibold
        })
        root.addView(TextView(ctx).apply {
            text = question
            setTextColor(Color.parseColor("#F7F3F5")); textSize = 15f
            typeface = regular
            setPadding(0, dp(10f), 0, if (reason.isNullOrBlank()) dp(14f) else dp(2f))
        })
        if (!reason.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = reason
                setTextColor(Color.parseColor("#B9B1B5")); textSize = 12.5f
                typeface = regular
                setPadding(0, 0, 0, dp(14f))
            })
        }
        val field = EditText(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#2A2627"))
            }
            setTextColor(Color.parseColor("#F7F3F5"))
            setHintTextColor(Color.parseColor("#878083"))
            hint = "Type your answer…"
            textSize = 15f
            typeface = regular
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f))
        }
        root.addView(field)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16f), 0, 0)
        }
        row.addView(TextView(ctx).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#B9B1B5")); textSize = 14f
            typeface = semibold
            setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
            setOnClickListener { onCancel() }
        })
        row.addView(TextView(ctx).apply {
            text = "Submit"
            setTextColor(Color.WHITE); textSize = 14f
            typeface = semibold
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#AC1ED6"), Color.parseColor("#E066B4")),
            ).apply { cornerRadius = dp(24f).toFloat() }
            setPadding(dp(24f), dp(11f), dp(24f), dp(11f))
            setOnClickListener { onSubmit(field.text.toString()) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(10f) })
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            dp(320f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(root, lp)
        view = root
        pulse = ObjectAnimator.ofFloat(root, "alpha", 0.88f, 1f).apply {
            duration = 1100; repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        view?.let { runCatching { wm.removeView(it) } }; view = null
    }
}
