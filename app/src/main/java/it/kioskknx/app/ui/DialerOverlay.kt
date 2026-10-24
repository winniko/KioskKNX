package it.kioskknx.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import it.kioskknx.app.R
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.config.SpeedDial
import it.kioskknx.app.sip.SipManager
import it.kioskknx.app.util.L

/**
 * Tastiera per chiamate e videochiamate in uscita, con numeri rapidi.
 * I numeri rapidi si modificano da qui ("Modifica" + codice installatore), senza il menu generale.
 * Digitando *#CODICE# e premendo chiamata si aprono le impostazioni.
 */
class DialerOverlay(
    private val activity: AppCompatActivity,
    root: FrameLayout,
    private val beforeCall: () -> Unit,
    private val openSettings: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.rgb(16, 18, 22))
        isClickable = true
        visibility = View.GONE
    }
    private val body = LinearLayout(activity)
    private val number: TextView = activity.label("", 40f, bold = true).apply {
        gravity = Gravity.CENTER
        setSingleLine()
        setPadding(0, activity.dp(8), 0, activity.dp(8))
    }
    private val speedBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val editButton: MaterialButton
    private var editMode = false
    // --- modalità "in chiamata": toni DTMF oppure inoltro della chiamata
    private var inCall = false
    private var dtmfMode = true
    private val title: TextView = activity.label("Chiama", 26f, bold = true)
    private val modeButton: MaterialButton
    private val callButtons = mutableListOf<View>()
    private lateinit var forwardButton: RoundButton
    private val autoClose = Runnable { close() }

    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    init {
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12))
        }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        modeButton = activity.pillButton("", Color.rgb(121, 85, 72)) { toggleMode() }.apply { visibility = View.GONE }
        top.addView(modeButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = activity.dp(16) })
        top.addView(RoundButton(activity, R.drawable.ic_close, Color.rgb(84, 110, 122), "Chiudi", 56).apply { onClick { close() } })
        outer.addView(top)

        // --- colonna tastiera
        val keypad = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        keypad.addView(number, LinearLayout.LayoutParams(MATCH, WRAP))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")).forEach { row ->
            val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { k -> r.addView(key(k), LinearLayout.LayoutParams(0, activity.dp(72), 1f).apply { setMargins(activity.dp(6), activity.dp(5), activity.dp(6), activity.dp(5)) }) }
            keypad.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(activity.pillButton("⌫", Color.rgb(55, 71, 79)) { backspace() }.apply {
            textSize = 26f
            setOnLongClickListener { number.text = ""; true }
        })
        RoundButton(activity, R.drawable.ic_call, Color.rgb(46, 160, 67), "Chiama", 80).apply {
            onClick { call(number.text.toString(), false) }
            actions.addView(this); callButtons += this
        }
        RoundButton(activity, R.drawable.ic_videocam, Color.rgb(21, 101, 192), "Video", 80).apply {
            onClick { call(number.text.toString(), true) }
            actions.addView(this); callButtons += this
        }
        forwardButton = RoundButton(activity, R.drawable.ic_call, Color.rgb(230, 126, 34), "Inoltra", 80).apply {
            onClick { forward(number.text.toString(), number.text.toString()) }
            visibility = View.GONE
            actions.addView(this)
        }
        keypad.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = activity.dp(8) })

        // --- colonna numeri rapidi
        val speed = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sh = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sh.addView(activity.label("Numeri rapidi", 22f, Color.rgb(100, 181, 246), bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        editButton = activity.pillButton("Modifica", Color.rgb(55, 71, 79)) { toggleEdit() }
        sh.addView(editButton)
        speed.addView(sh)
        speed.addView(ScrollView(activity).apply { addView(speedBox) }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = activity.dp(8) })

        body.addView(keypad)
        body.addView(speed)
        outer.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        view.addView(outer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun key(k: String) = MaterialButton(activity).apply {
        text = k
        textSize = 30f
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 46, 56))
        cornerRadius = activity.dp(16)
        setOnClickListener {
            touched()
            number.append(k)
            if (inCall && dtmfMode && !SipManager.sendDtmfKey(k[0])) activity.toast("Toni disponibili solo dopo la risposta")
        }
        if (k == "0") setOnLongClickListener { touched(); number.append("+"); true }
    }

    /** @param duringCall true = tastiera durante una chiamata (toni DTMF / inoltro). */
    fun open(duringCall: Boolean = false) {
        number.text = ""
        editMode = false
        editButton.text = "Modifica"
        inCall = duringCall
        dtmfMode = duringCall && SipManager.isAnswered
        title.text = if (inCall) "Tastiera in chiamata" else "Chiama"
        modeButton.show(inCall)
        editButton.show(!inCall)
        callButtons.forEach { it.show(!inCall) }
        refreshMode()
        // Orizzontale: tastiera a sinistra, numeri rapidi a destra. Verticale: uno sopra l'altro.
        val landscape = view.rootView.width >= view.rootView.height
        body.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (i in 0 until body.childCount) {
            body.getChildAt(i).layoutParams = if (landscape)
                LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginEnd = activity.dp(if (i == 0) 24 else 0) }
            else LinearLayout.LayoutParams(MATCH, if (i == 0) WRAP else 0, if (i == 0) 0f else 1f)
        }
        renderSpeedDials()
        view.show(true)
        view.bringToFront()
        touched()
        L.i("Dialer", "Dialer aperto")
    }

    fun close() {
        main.removeCallbacks(autoClose)
        view.show(false)
    }

    /** Si chiude da solo dopo 90 s senza tocchi. */
    fun touched() {
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, 90_000)
    }

    private fun toggleMode() {
        touched()
        if (!dtmfMode && !SipManager.isAnswered) return activity.toast("I toni DTMF si possono inviare solo dopo aver risposto")
        dtmfMode = !dtmfMode
        number.text = ""
        refreshMode()
    }

    private fun refreshMode() {
        modeButton.text = if (dtmfMode) "Modalità: toni DTMF  ⇄" else "Modalità: inoltro  ⇄"
        forwardButton.show(inCall && !dtmfMode)
        renderSpeedDials()
    }

    private fun forward(target: String, label: String) {
        touched()
        if (target.isBlank()) return activity.toast("Scrivi il numero o scegli un numero rapido")
        AlertDialog.Builder(activity).setMessage("Inoltrare la chiamata a $label?")
            .setPositiveButton("Inoltra") { _, _ ->
                val err = SipManager.forward(target)
                if (err != null) activity.toast(err) else { activity.toast("Inoltro verso $label avviato"); close() }
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun backspace() {
        touched()
        val s = number.text
        if (s.isNotEmpty()) number.text = s.subSequence(0, s.length - 1)
    }

    private fun call(target: String, video: Boolean, name: String = "") {
        touched()
        val code = installerCode()
        if (code.isNotBlank() && target == "*#$code#") {
            number.text = ""
            close()
            L.i("Dialer", "Codice installatore: apro le impostazioni")
            openSettings()
            return
        }
        if (target.isBlank()) return activity.toast("Componi un numero o scegli un numero rapido")
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) needed += Manifest.permission.CAMERA
        val missing = needed.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), 77)
            activity.toast("Concedi il permesso richiesto e premi di nuovo Chiama")
            return
        }
        beforeCall()
        val err = SipManager.dial(target, video, name)
        if (err != null) activity.toast(err) else close()
    }

    // ------------------------------------------------------------------ numeri rapidi

    private fun installerCode(): String {
        val c = ConfigRepository.current
        return c.dialer.installerCode.ifBlank { c.kiosk.pin }
    }

    private fun renderSpeedDials() {
        speedBox.removeAllViews()
        val list = ConfigRepository.current.dialer.speedDials
        if (inCall && dtmfMode) {
            speedBox.addView(activity.label("I tasti premuti vengono inviati come toni DTMF.\nPer inoltrare la chiamata tocca \"Modalità\" in alto.", 17f, Color.GRAY))
            return
        }
        if (inCall) speedBox.addView(activity.label("Tocca un numero rapido per inoltrare la chiamata:", 16f, Color.GRAY).apply { setPadding(0, 0, 0, activity.dp(8)) })
        if (list.isEmpty() && !editMode) {
            speedBox.addView(activity.label("Nessun numero rapido.\nTocca \"Modifica\" per aggiungerne.", 17f, Color.GRAY))
        }
        list.forEachIndexed { i, sd ->
            val text = (if (sd.video) "📹  " else "📞  ") + sd.name.ifBlank { sd.number } + (if (editMode) "   ✎" else "")
            val b = activity.pillButton(text, if (editMode) Color.rgb(84, 110, 122) else Color.rgb(38, 70, 110)) {
                when {
                    editMode -> editDialog(i, sd)
                    inCall -> forward(sd.number, sd.name.ifBlank { sd.number })
                    else -> call(sd.number, sd.video, sd.name)
                }
            }.apply { textSize = 20f; minHeight = activity.dp(64); gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            speedBox.addView(b, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = activity.dp(8) })
        }
        if (editMode) {
            speedBox.addView(activity.pillButton("+  Aggiungi numero rapido", Color.rgb(46, 160, 67)) { editDialog(-1, SpeedDial()) },
                LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun toggleEdit() {
        touched()
        if (editMode) {
            editMode = false
            editButton.text = "Modifica"
            renderSpeedDials()
            return
        }
        val code = installerCode()
        if (code.isBlank()) return enterEdit()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Codice installatore"
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(activity).setTitle("Modifica numeri rapidi").setView(input)
            .setPositiveButton("Entra") { _, _ -> if (input.text.toString() == code) enterEdit() else activity.toast("Codice errato") }
            .setNegativeButton("Annulla", null).show()
    }

    private fun enterEdit() {
        editMode = true
        editButton.text = "Fine"
        renderSpeedDials()
    }

    private fun editDialog(index: Int, sd: SpeedDial) {
        touched()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0) }
        val name = EditText(activity).apply { hint = "Nome (es. Portineria)"; setText(sd.name); setSingleLine() }
        val num = EditText(activity).apply {
            hint = "Interno, IP o sip:... (es. 201 o 192.168.2.50)"
            setText(sd.number)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val video = CheckBox(activity).apply { text = "Videochiamata"; isChecked = sd.video }
        box.addView(name); box.addView(num); box.addView(video)
        val b = AlertDialog.Builder(activity).setTitle(if (index < 0) "Nuovo numero rapido" else "Modifica numero rapido").setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val item = SpeedDial(name.text.toString().trim(), num.text.toString().trim(), video.isChecked)
                if (item.number.isBlank()) { activity.toast("Il numero è obbligatorio"); return@setPositiveButton }
                saveSpeedDials { list -> if (index < 0) list += item else list[index] = item }
            }
            .setNegativeButton("Annulla", null)
        if (index >= 0) b.setNeutralButton("Elimina") { _, _ -> saveSpeedDials { it.removeAt(index) } }
        b.show()
    }

    private fun saveSpeedDials(change: (MutableList<SpeedDial>) -> Unit) {
        ConfigRepository.update { c ->
            val list = c.dialer.speedDials.toMutableList()
            change(list)
            c.copy(dialer = c.dialer.copy(speedDials = list))
        }
        renderSpeedDials()
    }
}
