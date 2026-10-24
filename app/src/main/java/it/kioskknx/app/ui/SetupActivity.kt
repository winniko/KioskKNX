package it.kioskknx.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.kiosk.KioskManager

/** Configurazione guidata dei permessi al primo avvio (riapribile dalle impostazioni). */
class SetupActivity : KioskActivity() {
    private lateinit var list: LinearLayout

    private data class Item(val title: String, val desc: String, val granted: () -> Boolean, val request: () -> Unit)

    private val items: List<Item> by lazy {
        val l = mutableListOf(
            Item("Fotocamera", "Serve per il sensore di movimento (accensione automatica dello schermo).",
                { granted(Manifest.permission.CAMERA) }, { ask(Manifest.permission.CAMERA) }),
            Item("Microfono", "Serve per parlare con il citofono.",
                { granted(Manifest.permission.RECORD_AUDIO) }, { ask(Manifest.permission.RECORD_AUDIO) }),
            Item("Mostra sopra le altre app", "Permette di far comparire la chiamata e di riavviare l'app automaticamente.",
                { Settings.canDrawOverlays(this) },
                { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
            Item("Nessuna ottimizzazione batteria", "Impedisce ad Android di chiudere l'app in background.",
                { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
                { requestBattery() }),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(2, Item("Notifiche", "Mostra la notifica del servizio sempre attivo.",
                { granted(Manifest.permission.POST_NOTIFICATIONS) }, { ask(Manifest.permission.POST_NOTIFICATIONS) }))
        }
        if (Build.VERSION.SDK_INT >= 26) {
            l.add(Item("Installazione aggiornamenti (facoltativo)", "Consente di aggiornare l'app da remoto con /api/update.",
                { packageManager.canRequestPackageInstalls() },
                { openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }))
        }
        l.add(Item("App Home predefinita (facoltativo)", "Imposta KioskKNX come schermata Home: si riapre sempre premendo Home.",
            { isDefaultHome() }, { openSettings(Intent(Settings.ACTION_HOME_SETTINGS)) }))
        l
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        col.addView(label("Configurazione iniziale", 28f, bold = true))
        col.addView(label(
            "Concedi i permessi necessari toccando \"Concedi\". " +
                (if (KioskManager.isDeviceOwner(this)) "L'app è Device Owner: molti permessi sono già concessi automaticamente."
                else "Puoi farlo anche più tardi dal menu impostazioni."), 17f, Color.LTGRAY
        ).apply { setPadding(0, dp(8), 0, dp(16)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(pillButton("Continua", Color.rgb(46, 160, 67)) {
            ConfigRepository.setupDone = true
            finish()
        }, LinearLayout.LayoutParams(dp(300), WRAP).apply { topMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 22, 28)); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        items.forEach { item ->
            val ok = runCatching { item.granted() }.getOrDefault(false)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label((if (ok) "✅  " else "⬜  ") + item.title, 20f, bold = true))
            texts.addView(label(item.desc, 15f, Color.LTGRAY))
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!ok) row.addView(pillButton("Concedi", Color.rgb(21, 101, 192)) { item.request() })
            list.addView(row)
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ask(p: String) = requestPermissions(arrayOf(p), 10)

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() =
        openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))

    private fun openSettings(i: Intent) {
        KioskManager.exitKiosk(this)
        try {
            startActivity(i)
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.onFailure { toast("Impostazione non disponibile su questo dispositivo") }
        }
    }

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }
}
