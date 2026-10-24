package it.kioskknx.app.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import it.kioskknx.app.history.CallHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Storico chiamate con foto del visitatore. */
class HistoryActivity : KioskActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("Storico chiamate", 26f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pillButton("Svuota", Color.rgb(211, 47, 47)) {
            AlertDialog.Builder(this).setMessage("Cancellare tutto lo storico e le foto?")
                .setPositiveButton("Cancella") { _, _ -> CallHistory.clear(); render() }
                .setNegativeButton("Annulla", null).show()
        })
        top.addView(pillButton("Chiudi", Color.rgb(84, 110, 122)) { finish() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        col.addView(top)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 22, 28)); addView(col) })
        render()
    }

    private fun render() {
        list.removeAllViews()
        val fmt = SimpleDateFormat("EEE dd/MM/yyyy HH:mm", Locale.ITALY)
        val entries = CallHistory.list()
        if (entries.isEmpty()) list.addView(label("Nessuna chiamata registrata.", 18f, Color.LTGRAY).apply { setPadding(0, dp(20), 0, 0) })
        entries.forEach { e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY) }
            if (e.snapshot.isNotEmpty()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(e.snapshot, opts)?.let { img.setImageBitmap(it) }
                img.setOnClickListener {
                    val full = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(e.snapshot)); adjustViewBounds = true }
                    AlertDialog.Builder(this).setView(full).setPositiveButton("Chiudi", null).show()
                }
            }
            row.addView(img, LinearLayout.LayoutParams(dp(160), dp(100)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
            texts.addView(label(fmt.format(Date(e.time)), 18f, bold = true))
            val esito = if (e.answered) "Risposta (${e.durationSec} s)" else "Non risposta"
            texts.addView(label("${e.remote}  -  $esito" + if (e.doorOpened) "  -  porta aperta" else "", 16f,
                if (e.answered) Color.LTGRAY else Color.rgb(255, 160, 120)))
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            list.addView(row)
        }
    }
}
