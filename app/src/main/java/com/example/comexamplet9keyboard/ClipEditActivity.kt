package com.example.comexamplet9keyboard

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

// Pano kartını oluşturma / düzenleme / kilitleme / silme penceresi
class ClipEditActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n", "HardcodedText")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = intent.getStringExtra("text")
        val oldPinned = intent.getBooleanExtra("pinned", false)
        title = if (old == null) "Yeni pano kartı" else "Pano kartını düzenle"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        val edit = EditText(this).apply {
            setText(old ?: "")
            hint = "Metin"
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        val lock = CheckBox(this).apply {
            text = "Kilitle (silinmesin, hep üstte kalsın)"
            isChecked = oldPinned
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun btn(label: String, f: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { f() }
        }
        if (old != null) {
            row.addView(btn("Sil") {
                ClipStore.remove(this, old)
                finish()
            })
        }
        row.addView(btn("İptal") { finish() })
        row.addView(btn("Kaydet") {
            val t = edit.text.toString()
            if (t.isNotBlank()) {
                if (old != null) {
                    ClipStore.update(this, old, t, lock.isChecked)
                } else {
                    ClipStore.add(this, t, lock.isChecked)
                }
            }
            finish()
        })

        root.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(lock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }
}
