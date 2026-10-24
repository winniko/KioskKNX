package it.kioskknx.app.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

/** Piccolo costruttore di form per il menu impostazioni (tutto da codice, nessun layout XML). */
class Form(private val ctx: Context, val parent: LinearLayout) {
    private val accent = Color.rgb(100, 181, 246)

    /** Contenitore dove finiscono i campi: la tendina della sezione corrente (o [parent]). */
    private var target: LinearLayout = parent
    private val sections = mutableListOf<Pair<LinearLayout, TextView>>()

    /**
     * Sezione a tendina: toccando il titolo si apre (e si chiudono le altre).
     * I campi aggiunti dopo finiscono dentro la tendina.
     */
    fun section(title: String, expanded: Boolean = false) {
        val header = ctx.label("", 22f, accent, bold = true).apply {
            setPadding(0, ctx.dp(22), 0, ctx.dp(14))
            isClickable = true
            isFocusable = true
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(8), 0, 0, ctx.dp(12))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        fun refresh() { header.text = (if (body.visibility == View.VISIBLE) "▾  " else "▸  ") + title }
        refresh()
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            sections.forEach { (b, h) -> if (b !== body && b.visibility == View.VISIBLE) { b.visibility = View.GONE; h.text = "▸" + h.text.toString().drop(1) } }
            body.visibility = if (open) View.VISIBLE else View.GONE
            refresh()
            if (open) header.post { (parent.parent as? android.widget.ScrollView)?.smoothScrollTo(0, header.top) }
        }
        sections += body to header
        parent.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(View(ctx).apply { setBackgroundColor(Color.rgb(60, 70, 80)) }, LinearLayout.LayoutParams(MATCH, ctx.dp(1)))
        target = body
    }

    /** Aggiunge una vista qualsiasi alla sezione corrente. */
    fun add(v: View) = target.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))

    fun note(text: String) {
        target.addView(ctx.label(text, 14f, Color.rgb(170, 170, 170)).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })
    }

    private fun row(label: String, field: View) {
        target.addView(ctx.label(label, 15f, Color.LTGRAY).apply { setPadding(0, ctx.dp(10), 0, 0) })
        target.addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun text(label: String, value: String, hint: String = "", password: Boolean = false, multiline: Boolean = false, numeric: Boolean = false): () -> String {
        val e = EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (multiline) { minLines = 2; maxLines = 6 } else isSingleLine = true
        }
        row(label, e)
        return { e.text.toString().trim() }
    }

    fun int(label: String, value: Int, hint: String = ""): () -> Int {
        val g = text(label, value.toString(), hint, numeric = true)
        return { g().toIntOrNull() ?: value }
    }

    fun float(label: String, value: Float, hint: String = ""): () -> Float {
        val g = text(label, value.toString(), hint, numeric = true)
        return { g().replace(',', '.').toFloatOrNull() ?: value }
    }

    fun switch(label: String, value: Boolean): () -> Boolean {
        val s = SwitchMaterial(ctx).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = value
            setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        }
        target.addView(s, LinearLayout.LayoutParams(MATCH, WRAP))
        return { s.isChecked }
    }

    /** [options] = lista di (valore, etichetta). */
    fun choice(label: String, options: List<Pair<String, String>>, value: String, onChange: ((String) -> Unit)? = null): () -> String {
        val sp = Spinner(ctx)
        sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
        sp.setSelection(options.indexOfFirst { it.first == value }.coerceAtLeast(0))
        if (onChange != null) {
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onChange(options[pos].first)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row(label, sp)
        return { options.getOrNull(sp.selectedItemPosition)?.first ?: value }
    }

    fun buttons(vararg items: Pair<String, () -> Unit>, color: Int = Color.rgb(55, 71, 79)) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (t, a) ->
            r.addView(ctx.pillButton(t, color, a), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(10) })
        }
        target.addView(HorizontalScrollView(ctx).apply { addView(r); setPadding(0, ctx.dp(8), 0, ctx.dp(4)) })
    }

    /** Sotto-form indentato (per gli elementi delle liste). */
    fun sub(): Form {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(8), ctx.dp(12))
            setBackgroundColor(Color.rgb(28, 34, 42))
        }
        target.addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(10) })
        return Form(ctx, box)
    }
}
