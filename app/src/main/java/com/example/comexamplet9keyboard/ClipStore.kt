package com.example.comexamplet9keyboard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Clip(val text: String, val pinned: Boolean)

// Pano geçmişi: hem klavye servisi hem de düzenleme ekranı buradan okur/yazar.
object ClipStore {
    private const val KEY = "clips"
    private const val MAX = 40

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("t9", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Clip> {
        val out = ArrayList<Clip>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Clip(o.getString("t"), o.optBoolean("p", false)))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun save(ctx: Context, list: List<Clip>) {
        val arr = JSONArray()
        list.forEach { (text, pinned) -> arr.put(JSONObject().put("t", text).put("p", pinned)) }
        prefs(ctx).edit { putString(KEY, arr.toString()) }
    }

    // Yeni kopyalanan metin başa eklenir; aynısı varsa öne taşınır (kilidi korunur)
    fun add(ctx: Context, text: String, pinned: Boolean = false) {
        if (text.isBlank()) return
        val list = load(ctx)
        val old = list.firstOrNull { it.text == text }
        list.removeAll { it.text == text }
        list.add(0, Clip(text, pinned || (old?.pinned ?: false)))
        // Sınırı aşınca en eski kilitsiz kart silinir; kilitliler silinmez
        while (list.size > MAX) {
            val idx = list.indexOfLast { !it.pinned }
            if (idx < 0) break
            list.removeAt(idx)
        }
        save(ctx, list)
    }

    fun remove(ctx: Context, text: String) {
        val list = load(ctx)
        list.removeAll { it.text == text }
        save(ctx, list)
    }

    // Kartın metnini ve kilit durumunu yerinde günceller
    fun update(ctx: Context, oldText: String, newText: String, pinned: Boolean) {
        if (newText.isBlank()) return
        val list = load(ctx)
        val idx = list.indexOfFirst { it.text == oldText }
        if (idx < 0) { add(ctx, newText, pinned); return }
        list[idx] = Clip(newText, pinned)
        // Aynı metinden başka kart varsa tekrarı sil
        val seen = HashSet<String>()
        val clean = list.filter { seen.add(it.text) }
        save(ctx, clean)
    }
}
