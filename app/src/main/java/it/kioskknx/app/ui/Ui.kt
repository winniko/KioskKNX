package it.kioskknx.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun parseColor(s: String, def: Int): Int = try { Color.parseColor(s.trim()) } catch (e: Exception) { def }

val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** Pulsante rotondo grande con icona ed etichetta (cornetta, serrature, ...). */
class RoundButton(context: Context, @DrawableRes icon: Int, color: Int, label: String, sizeDp: Int = 84) :
    LinearLayout(context) {
    val circle: FrameLayout
    val label: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = context.dp(sizeDp)
        circle = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            isClickable = true
            isFocusable = true
            foreground = context.getDrawable(android.R.drawable.list_selector_background)
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
            }, FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER))
        }
        addView(circle, LayoutParams(size, size))
        this.label = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, context.dp(6), 0, 0)
        }
        addView(this.label, LayoutParams(WRAP, WRAP))
        setPadding(context.dp(14), context.dp(4), context.dp(14), context.dp(4))
    }

    fun onClick(block: () -> Unit) = circle.setOnClickListener { block() }

    fun setColor(color: Int) {
        (circle.background as GradientDrawable).setColor(color)
    }
}

/** Pulsante rettangolare colorato (pulsanti custom HTTP). */
fun Context.pillButton(text: String, color: Int, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        maxLines = 1
        letterSpacing = 0f
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(color)
        cornerRadius = dp(28)
        minHeight = dp(56)
        setPadding(dp(22), 0, dp(22), 0)
        setOnClickListener { onClick() }
    }

fun Context.label(text: String, sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
