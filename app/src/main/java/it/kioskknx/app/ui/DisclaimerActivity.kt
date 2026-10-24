package it.kioskknx.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.addCallback
import it.kioskknx.app.R
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.util.L

/** Informativa legale obbligatoria al primo avvio (non aggirabile). */
class DisclaimerActivity : KioskActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { /* non si può uscire senza accettare */ }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        col.addView(label(getString(R.string.disclaimer_title), 28f, bold = true))
        col.addView(label(getString(R.string.disclaimer_text) + "\n\nCodice sorgente: ${it.kioskknx.app.BuildConfig.SOURCE_URL}", 19f, Color.rgb(220, 220, 220)).apply {
            setPadding(0, dp(20), 0, dp(20))
            setLineSpacing(0f, 1.25f)
        })
        val check = CheckBox(this).apply {
            text = getString(R.string.disclaimer_check)
            textSize = 19f
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        col.addView(check)
        val confirm = pillButton(getString(R.string.disclaimer_confirm), Color.rgb(46, 160, 67)) {
            ConfigRepository.disclaimerAcceptedAt = System.currentTimeMillis()
            L.i("Disclaimer", "Condizioni accettate")
            finish()
        }.apply { isEnabled = false; alpha = 0.4f }
        check.setOnCheckedChangeListener { _, c -> confirm.isEnabled = c; confirm.alpha = if (c) 1f else 0.4f }
        col.addView(confirm, LinearLayout.LayoutParams(dp(320), WRAP).apply { topMargin = dp(24); gravity = Gravity.CENTER_HORIZONTAL })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 22, 28))
            isFillViewport = true
            addView(col)
        })
    }
}
