package com.example.comexamplet9keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnEnable: LinearLayout
    private lateinit var btnSelect: LinearLayout
    private lateinit var statusEnable: TextView
    private lateinit var statusSelect: TextView

    private val BG = Color.parseColor("#0F172A")
    private val CARD_BG = Color.parseColor("#1E293B")
    private val ACCENT = Color.parseColor("#5FD3C5")
    private val ACCENT_DARK = Color.parseColor("#00897B")
    private val TXT = Color.parseColor("#F8FAFC")
    private val SUBTXT = Color.parseColor("#94A3B8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply { isFillViewport = true }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(36))
        }

        // Hero Header
        val logoBadge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(20).toFloat()
            }
            val tv = TextView(context).apply {
                text = "T9"
                textSize = 32f
                setTextColor(ACCENT)
                gravity = Gravity.CENTER
            }
            addView(tv, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        }

        val title = TextView(this).apply {
            text = "T9 KlavyeTR"
            textSize = 26f
            setTextColor(TXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }

        val subtitle = TextView(this).apply {
            text = "Hızlı, Akıllı ve Modern Türkçe T9 Akıllı Klavye"
            textSize = 14f
            setTextColor(SUBTXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(28))
        }

        container.addView(logoBadge, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        container.addView(title)
        container.addView(subtitle)

        // Setup Steps Card
        val stepsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }

        val stepsTitle = TextView(this).apply {
            text = "Klavye Kurulumu"
            textSize = 18f
            setTextColor(TXT)
            setPadding(0, 0, 0, dp(14))
        }
        stepsCard.addView(stepsTitle)

        // Step 1 Button
        btnEnable = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(ACCENT_DARK)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        val labelEnable = TextView(this).apply {
            text = "1. Klavyeyi Etkinleştir"
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        statusEnable = TextView(this).apply {
            text = "Kontrol..."
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        btnEnable.addView(labelEnable, LinearLayout.LayoutParams(0, -2, 1f))
        btnEnable.addView(statusEnable, LinearLayout.LayoutParams(-2, -2))

        // Step 2 Button
        btnSelect = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(ACCENT_DARK)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        val labelSelect = TextView(this).apply {
            text = "2. Varsayılan Klavye Seç"
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        statusSelect = TextView(this).apply {
            text = "Kontrol..."
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        btnSelect.addView(labelSelect, LinearLayout.LayoutParams(0, -2, 1f))
        btnSelect.addView(statusSelect, LinearLayout.LayoutParams(-2, -2))

        stepsCard.addView(btnEnable, LinearLayout.LayoutParams(MATCH_PARENT, -2))
        stepsCard.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(10)))
        stepsCard.addView(btnSelect, LinearLayout.LayoutParams(MATCH_PARENT, -2))

        container.addView(stepsCard, LinearLayout.LayoutParams(MATCH_PARENT, -2))

        // Features Highlights Card
        val featuresCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }

        val featureTitle = TextView(this).apply {
            text = "Öne Çıkan Özellikler"
            textSize = 18f
            setTextColor(TXT)
            setPadding(0, 0, 0, dp(12))
        }
        featuresCard.addView(featureTitle)

        val features = listOf(
            "⚡ Akıllı Türkçe Tahmin" to "50.000+ kelimelik konuşma dili sözlüğü ve gelişmiş Türkçe ek motoru.",
            "🎙️ Sesle Yazma" to "Boşluk tuşuna uzun basarak sesle Türkçe mesaj yazın.",
            "🎨 Koyu & Beyaz Temalar" to "Menüden tek tıkla gece ve gündüz temaları arasında geçiş yapın.",
            "📋 Pano & İmleç Yönetimi" to "Hızlı pano kartları ve hassas imleç hareket tuşları."
        )

        features.forEach { (head, desc) ->
            val h = TextView(this).apply { text = head; textSize = 15f; setTextColor(ACCENT); setPadding(0, dp(8), 0, dp(2)) }
            val d = TextView(this).apply { text = desc; textSize = 13f; setTextColor(SUBTXT) }
            featuresCard.addView(h)
            featuresCard.addView(d)
        }

        container.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(16)))
        container.addView(featuresCard, LinearLayout.LayoutParams(MATCH_PARENT, -2))

        scroll.addView(container)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        checkKeyboardStatus()
    }

    private fun checkKeyboardStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val isEnabled = enabledMethods.any { it.packageName == packageName }

        val currentDefault = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val isDefault = currentDefault != null && currentDefault.contains(packageName)

        statusEnable.text = if (isEnabled) "✓ Etkin" else "Etkinleştir"
        btnEnable.background = GradientDrawable().apply {
            setColor(if (isEnabled) Color.parseColor("#334155") else ACCENT_DARK)
            cornerRadius = dp(12).toFloat()
        }

        statusSelect.text = if (isDefault) "✓ Seçildi" else "Seç"
        btnSelect.background = GradientDrawable().apply {
            setColor(if (isDefault) Color.parseColor("#334155") else ACCENT_DARK)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT
    }
}
