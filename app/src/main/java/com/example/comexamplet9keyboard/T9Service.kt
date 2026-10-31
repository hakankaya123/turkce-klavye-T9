package com.example.comexamplet9keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class T9Service : InputMethodService() {

    private val tr = Locale("tr", "TR")
    private val handler = Handler(Looper.getMainLooper())

    // Tema renkleri (applyTheme ile Koyu / Beyaz arasında değişir)
    private var BG = Color.parseColor("#212121")           // klavye arka planı
    private var KEY = Color.parseColor("#2E3236")          // tuş rengi
    private var ACCENT = Color.parseColor("#5FD3C5")       // vurgu (yeşil-mavi)
    private var DIM = Color.parseColor("#262829")          // koyu tuş (ok, silme vb.)
    private var TXT = Color.WHITE                          // tuş ve yazı rengi
    private var SUBTXT = Color.parseColor("#8A8E92")       // küçük etiketler, boşluk tuşu yazısı
    private var ICON = Color.LTGRAY                        // üst şerit simgeleri
    private var SELBG = Color.parseColor("#3F6B66")        // "Seç" açıkken tuş rengi
    private var SHIFTOFF = Color.parseColor("#7FA8A3")     // ⇧ kapalıyken
    private var lightTheme = false
    private lateinit var themePage: View
    private lateinit var numPage: View
    private var numActionLabel: TextView? = null

    // ---------- T9 harf grupları ----------
    private val groups = mapOf(
        '2' to "abcç", '3' to "def", '4' to "gğhıi", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrsş", '8' to "tuüv", '9' to "wxyz"
    )
    private val charToDigit: Map<Char, Char> =
        groups.flatMap { (d, s) -> s.map { it to d } }.toMap()

    // ---------- Sözlük ----------
    private val words = ArrayList<String>()
    private val codes = ArrayList<String>()
    private val wordSet = HashSet<String>()
    private val builtinBigrams = HashMap<String, ArrayList<String>>()
    private val userCount = HashMap<String, Int>()
    private val suffixCount = HashMap<String, Int>()
    private val wordIdx = HashMap<String, Int>()
    private val bigram = HashMap<String, Int>()       // harf geçiş sayıları (sözlükten)
    private val charTotal = HashMap<Char, Int>()
    private val suffixIndex: Map<String, Int> by lazy {
        SUFFIXES.withIndex().associate { it.value to it.index }
    }
    private val userBigram = HashMap<String, HashMap<String, Int>>()
    private var learnedSinceSave = 0
    private val prefs by lazy { getSharedPreferences("t9", Context.MODE_PRIVATE) }
    private var voiceInputEnabled: Boolean
        get() = prefs.getBoolean("voice_input_enabled", true)
        set(v) = prefs.edit().putBoolean("voice_input_enabled", v).apply()

    private var vibrateEnabled: Boolean
        get() = prefs.getBoolean("vibrate_enabled", true)
        set(v) = prefs.edit().putBoolean("vibrate_enabled", v).apply()

    private fun playClick() {
        if (vibrateEnabled && keyboardRootView != null) {
            try {
                keyboardRootView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (e: Exception) { }
        }
    }

    // ---------- Yazma durumu ----------
    private var predictive = true                 // a✓ tuşu
    private val word = StringBuilder()            // çoklu basış: onaylanmış harfler
    private var pendKey: String? = null           // çoklu basış: bekleyen tuş
    private var pendChars = ""
    private var pendIdx = 0
    private val seq = StringBuilder()             // tahmin modu: tuş dizisi
    private var cands: List<String> = emptyList()
    private var symComposing = false              // @ tuşu
    private var symIdx = 0
    private val forced = ArrayList<Char?>()       // sol sütundan seçilen harfler (tahmin modu)
    private var colActive = false                 // sol sütunda basılan tuşun harfleri gösterilsin mi
    private var lastColSig = "?"
    private lateinit var leftCol: LinearLayout
    private var upper = false
    private var shift = false                     // tek seferlik büyük harf
    private var caps = false                      // sabit büyük harf (çift basış)
    private var lastShiftTap = 0L
    private val letterLabels = ArrayList<Pair<TextView, String>>()
    private var prevWord: String? = null
    private var attach: String? = null            // üst çubuktan seçilen, sonuna ek yazılabilecek kelime
    private var chipWord: String? = null          // sözlüğe eklenebilecek kelime (+ simgesi)
    private var recentUnknownWord: String? = null
    private var stashedChip: String? = null       // alan yeniden başlarsa ekleme önerisini geri getirmek için
    private var stashedChipTime = 0L
    private var keyboardRootView: LinearLayout? = null
    private var navBottomPx = 0
    private var keyboardFrameView: FrameLayout? = null
    private var contentLayoutView: FrameLayout? = null
    private var letterMode = false                // üst çubukta kelime yerine harf adayları var
    private var letterPos = -1
    private var suggestions: List<String> = emptyList()

    // ---------- Görünümler ----------
    private lateinit var iconBar: LinearLayout
    private lateinit var suggestScroll: HorizontalScrollView
    private lateinit var suggestBox: LinearLayout
    private lateinit var closeBtn: TextView
    private lateinit var suggestLeftSpacer: View
    private lateinit var suggestRow: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var t9Page: View
    private lateinit var sym1: View
    private lateinit var sym2: View
    private lateinit var emojiPage: View
    private lateinit var cursorPage: View
    private lateinit var clipPage: View
    private lateinit var settingsPage: View
    private lateinit var clipLeft: LinearLayout
    private lateinit var clipRight: LinearLayout
    private lateinit var selKey: FrameLayout
    private lateinit var hideOrReturnTool: TextView
    private var selectMode = false
    private var autoSpaced = false                // noktalamadan sonra otomatik boşluk konduysa
    private val spacePunct = setOf(".", ",", "?", "!", ":", ";")
    private var lastClip: String? = null
    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(true) }
    private var predView: TextView? = null
    private var shiftIcon: IconView? = null

    private val confirmRunnable = Runnable { confirmPending(); updateSuggestions() }
    private val symRunnable = Runnable {
        currentInputConnection?.finishComposingText()
        symComposing = false
        updateSuggestions()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =====================================================
    //  Yaşam döngüsü
    // =====================================================

    override fun onCreate() {
        super.onCreate()
        lightTheme = prefs.getBoolean("theme_light", false)
        applyTheme()
        loadDictionary()
        loadUserData()
        try { clipboard.addPrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { clipboard.removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Tarayıcılar (Chrome, Opera...) aynı alanı kısa aralıklarla yeniden başlatır; öneri kaybolmasın
        val keep = if (restarting && stashedChip != null &&
            SystemClock.uptimeMillis() - stashedChipTime < 2500) stashedChip else null
        stashedChip = null
        resetState()
        if (keep != null) {
            recentUnknownWord = keep
            updateSuggestions()
        }
        if (::pages.isInitialized) {
            updateNumActionLabel(info)
            // Aynı alanın yeniden başlamasında açık sayfa değişmesin
            if (!restarting) showPage(if (isNumericField(info)) numPage else t9Page, toggle = false)
        }
    }

    override fun onFinishInput() {
        stashedChip = recentUnknownWord
        stashedChipTime = SystemClock.uptimeMillis()
        commitWord(false)
        saveUserData()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (autoSpaced && currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() != " ") autoSpaced = false
        if (composingActive()) checkCursorMoved()
        val a = attach
        if (a != null && !composingActive()) {
            val b = currentInputConnection?.getTextBeforeCursor(a.length, 0)?.toString()
            if (b != a) { attach = null; updateSuggestions() }
        }
    }

    private fun resetState() {
        handler.removeCallbacks(confirmRunnable)
        handler.removeCallbacks(symRunnable)
        word.setLength(0); seq.setLength(0); forced.clear(); colActive = false
        pendKey = null; symComposing = false
        cands = emptyList(); upper = false; prevWord = null
        attach = null; chipWord = null; recentUnknownWord = null
        shift = false; caps = false; updateShiftLook()
        updateSuggestions()
    }

    // =====================================================
    //  Sözlük ve öğrenme
    // =====================================================

    private fun codeOf(w: String) = w.map { charToDigit[it] ?: '?' }.joinToString("")

    private fun addWord(w: String) {
        if (w.isEmpty() || !w.all { charToDigit.containsKey(it) }) return
        if (!wordSet.add(w)) return
        wordIdx[w] = words.size
        words.add(w); codes.add(codeOf(w)); addSuffixes(w); addLetterStats(w)
    }

    private fun addSuffixes(w: String) {
        for (len in 1..4) if (w.length > len) {
            val k = w.takeLast(len)
            suffixCount[k] = (suffixCount[k] ?: 0) + 1
        }
    }

    private fun addLetterStats(w: String) {
        var prev = '^'
        for (c in w) {
            val k = "$prev$c"
            bigram[k] = (bigram[k] ?: 0) + 1
            charTotal[prev] = (charTotal[prev] ?: 0) + 1
            prev = c
        }
    }

    // Sözlük: app/src/main/assets/words_tr.txt
    // Satır başına bir kelime; istenirse "kelime sayı" biçiminde sıklık da olabilir.
    // Dosya yoksa küçük yerleşik liste kullanılır.
    private fun loadDictionary() {
        val list = ArrayList<Pair<String, Long>>()
        var hasFreq = false
        try {
            assets.open("words_tr.txt").bufferedReader().useLines { ls ->
                ls.forEach { line ->
                    val t = line.trim()
                    if (t.isNotEmpty()) {
                        val parts = t.split(' ', '\t', ',', ';').filter { it.isNotEmpty() }
                        val f = parts.getOrNull(1)?.toLongOrNull()
                        if (f != null) hasFreq = true
                        list.add(parts[0].lowercase(tr) to (f ?: 0L))
                    }
                }
            }
        } catch (e: Exception) { }
        if (list.isNotEmpty()) {
            val ordered = if (hasFreq) list.sortedByDescending { it.second } else list
            ordered.forEach { addWord(it.first) }
        } else {
            DEFAULT_WORDS.split(" ").forEach { addWord(it) }
        }
        DEFAULT_BIGRAMS.split(";").forEach { e ->
            val p = e.split(":")
            if (p.size == 2) builtinBigrams.getOrPut(p[0]) { ArrayList() }.addAll(p[1].split(","))
        }
    }

    private fun loadUserData() {
        try {
            (prefs.getString("uc", "") ?: "").lines().forEach { l ->
                val p = l.split('\t')
                if (p.size == 2) {
                    val c = p[1].toIntOrNull()
                    if (c != null) { userCount[p[0]] = c; addWord(p[0]) }
                }
            }
            (prefs.getString("ub", "") ?: "").lines().forEach { l ->
                val p = l.split('\t')
                if (p.size == 3) {
                    val c = p[2].toIntOrNull()
                    if (c != null) userBigram.getOrPut(p[0]) { HashMap() }[p[1]] = c
                }
            }
        } catch (e: Exception) { }
    }

    private fun saveUserData() {
        learnedSinceSave = 0
        val a = StringBuilder()
        userCount.forEach { (w, c) -> a.append(w).append('\t').append(c).append('\n') }
        val b = StringBuilder()
        userBigram.forEach { (p, m) ->
            m.forEach { (w, c) -> b.append(p).append('\t').append(w).append('\t').append(c).append('\n') }
        }
        prefs.edit().putString("uc", a.toString()).putString("ub", b.toString()).apply()
    }

    // Sadece sözlükte olan kelimelerin kullanım sayısını artırır. Yeni kelime yalnızca + ile eklenir.
    private fun learn(w0: String) {
        val w = w0.lowercase(tr)
        if (!wordSet.contains(w)) { prevWord = null; return }
        userCount[w] = (userCount[w] ?: 0) + 1
        prevWord?.let { p ->
            val m = userBigram.getOrPut(p) { HashMap() }
            m[w] = (m[w] ?: 0) + 1
        }
        prevWord = w
        learnedSinceSave++
        if (learnedSinceSave >= 15) saveUserData()
    }

    private fun matchesForced(w: String): Boolean {
        for (k in forced.indices) {
            val f = forced[k]
            if (f != null && (k >= w.length || w[k] != f)) return false
        }
        return true
    }

    // T9 tuş dizisine uyan kelimeler: uzunluğa göre artan sırada (1 harf, 2 harf, 3 harf...)
    private fun candidates(s: String): List<String> {
        val matches = ArrayList<Int>()
        for (i in codes.indices) {
            val c = codes[i]
            if (c.startsWith(s) && matchesForced(words[i])) {
                matches.add(i)
                if (matches.size >= 200) break
            }
        }
        for (w in userCount.keys) {
            val i = wordIdx[w] ?: continue
            if (codes[i].startsWith(s) && matchesForced(w) && !matches.contains(i)) {
                matches.add(i)
            }
        }
        matches.sortWith(
            compareBy<Int> { codes[it].length }
                .thenByDescending { userCount[words[it]] ?: 0 }
                .thenBy { it }
        )
        return matches.take(8).map { words[it] }
    }

    private fun letterScore(prev: Char, c: Char): Double {
        val b = (bigram["$prev$c"] ?: 0) + 0.5
        val t = (charTotal[prev] ?: 0) + 15.0
        return Math.log(b / t)
    }

    private fun startPrev(): Char =
        attach?.lastOrNull()?.let { it.toString().lowercase(tr)[0] } ?: '^'

    private fun chainScore(start: Char, frag: String): Double {
        var prev = start
        var sc = 0.0
        for (c in frag) { sc += letterScore(prev, c); prev = c }
        return sc
    }

    // Sözlükte karşılığı olmayan tuş dizisi için en olası yazım (seçilmiş harfler korunur)
    private fun guessSpelling(): String {
        var beam: List<Pair<String, Double>> = listOf("" to 0.0)
        for (k in 0 until seq.length) {
            val opts = forced.getOrNull(k)?.toString() ?: groups[seq[k]] ?: "?"
            val next = ArrayList<Pair<String, Double>>()
            for (b in beam) for (c in opts) {
                val prev = if (b.first.isEmpty()) startPrev() else b.first.last()
                next.add((b.first + c) to (b.second + letterScore(prev, c)))
            }
            beam = next.sortedByDescending { it.second }.take(10)
        }
        return beam.firstOrNull()?.first ?: ""
    }

    // Kelime bulunamayınca: sıradaki seçilmemiş tuşun harfleri, olasılığa göre sıralı
    private fun letterCandidates(): List<String> {
        val u = forced.indexOfLast { it == null }
        letterPos = u
        if (u < 0 || u >= seq.length) return emptyList()
        val guess = guessSpelling()
        val prev = if (u > 0 && u - 1 < guess.length) guess[u - 1] else startPrev()
        val opts = groups[seq[u]] ?: return emptyList()
        return opts.map { it.toString() }.sortedByDescending { letterScore(prev, it[0]) }
    }

    private fun fragScore(f: String): Int =
        suffixIndex[f]?.let { 1_000_000 - it } ?: (suffixCount[f] ?: 0)

    // Üst çubuktan seçilen kelimenin sonuna eklenecek 1-4 harflik parçalar
    private fun fragmentCandidates(): List<String> {
        val n = seq.length
        if (n == 0 || n > 4) return emptyList()
        var combos = listOf("")
        for (k in 0 until n) {
            val opts = forced.getOrNull(k)?.toString() ?: groups[seq[k]] ?: return emptyList()
            combos = combos.flatMap { c -> opts.map { c + it } }
        }
        val start = startPrev()
        return combos.sortedWith(
            compareByDescending<String> { fragScore(it) }.thenByDescending { chainScore(start, it) }
        ).take(8)
    }

    private fun suffixCompletions(prefix: String): List<String> {
        val p = prefix.lowercase(tr)
        return SUFFIXES.filter { it.startsWith(p) && it != p }.take(6)
    }

    private fun prefixCandidates(prefix: String): List<String> {
        val p = prefix.lowercase(tr)
        val hits = ArrayList<Int>()
        for (i in words.indices) {
            if (words[i].startsWith(p)) { hits.add(i); if (hits.size >= 40) break }
        }
        for (w in userCount.keys) {
            val i = wordIdx[w] ?: continue
            if (w.startsWith(p) && !hits.contains(i)) hits.add(i)
        }
        hits.sortWith(compareByDescending<Int> { userCount[words[it]] ?: 0 }.thenBy { it })
        return hits.take(6).map { words[it] }
    }

    private fun nextWords(prev: String?): List<String> {
        if (prev == null) return emptyList()
        val out = LinkedHashSet<String>()
        userBigram[prev]?.entries?.sortedByDescending { it.value }?.forEach { out.add(it.key) }
        builtinBigrams[prev]?.let { out.addAll(it) }
        return out.take(6)
    }

    // =====================================================
    //  Yazma mantığı
    // =====================================================

    private fun shown(s: String) = when {
        caps -> s.uppercase(tr)
        upper && s.isNotEmpty() -> s.substring(0, 1).uppercase(tr) + s.substring(1)
        else -> s
    }

    private fun composingActive() =
        seq.isNotEmpty() || word.isNotEmpty() || pendKey != null || symComposing

    // Editördeki yazılmakta olan metin (setComposingText ile gönderdiğimiz)
    private fun composingString(): String? = when {
        seq.isNotEmpty() -> shown(predText())
        word.isNotEmpty() || pendKey != null -> shown(pendingText())
        symComposing -> "@.-_/"[symIdx].toString()
        else -> null
    }

    // Kelimeyi olduğu gibi bırakır (imleç yerinde kalır), klavye durumunu sıfırlar
    private fun abandonComposing() {
        handler.removeCallbacks(confirmRunnable)
        handler.removeCallbacks(symRunnable)
        currentInputConnection?.finishComposingText()
        word.setLength(0); seq.setLength(0); forced.clear(); colActive = false
        pendKey = null; symComposing = false
        cands = emptyList(); upper = false
        attach = null; prevWord = null; autoSpaced = false
        updateSuggestions()
    }

    // Kullanıcı imleci yazdığı kelimenin ortasına ya da başka yere taşıdıysa kelimeyi bırak.
    // Böylece silme ve yazma, imlecin olduğu yerde çalışır.
    private fun checkCursorMoved() {
        if (!composingActive()) return
        val ic = currentInputConnection ?: return
        val c = composingString() ?: return
        val before = ic.getTextBeforeCursor(c.length, 0)?.toString() ?: return
        if (!before.equals(c, ignoreCase = true)) abandonComposing()
    }

    // Noktalamadan hemen sonra harf yazılıyorsa ve boşluk yoksa boşluk ekler
    private fun ensureSpaceAfterPunct() {
        if (composingActive() || attach != null) return
        val ic = currentInputConnection ?: return
        val b = ic.getTextBeforeCursor(2, 0)?.toString() ?: return
        if (b.isEmpty()) return
        val last = b.last().toString()
        if (last in spacePunct && shouldAutoSpace(ic, last)) ic.commitText(" ", 1)
    }

    private fun pendingText() =
        word.toString() + (if (pendKey != null) pendChars[pendIdx].toString() else "")

    private fun predText(): String {
        val exact = cands.firstOrNull { it.length == seq.length }
        return exact?.take(seq.length) ?: guessSpelling()
    }

    private fun confirmPending() {
        if (pendKey != null) { word.append(pendChars[pendIdx]); pendKey = null }
    }

    // Çoklu basış (a✓ kapalıyken)
    private fun tapMulti(id: String, chars: String) {
        val ic = currentInputConnection ?: return
        autoSpaced = false
        checkCursorMoved()
        ensureSpaceAfterPunct()
        if (seq.isNotEmpty() || symComposing) commitWord(false)
        if (pendKey == id) {
            pendIdx = (pendIdx + 1) % chars.length
        } else {
            confirmPending()
            if (word.isEmpty()) { upper = shift; shift = false; updateShiftLook() }
            pendKey = id; pendChars = chars; pendIdx = 0
        }
        colActive = true
        ic.setComposingText(shown(pendingText()), 1)
        handler.removeCallbacks(confirmRunnable)
        handler.postDelayed(confirmRunnable, 900)
        updateSuggestions()
    }

    // Tahmin modu (a✓ açıkken): her tuşa bir kez basılır
    private fun tapPredictive(digit: Char) {
        val ic = currentInputConnection ?: return
        autoSpaced = false
        checkCursorMoved()
        ensureSpaceAfterPunct()
        if (symComposing || word.isNotEmpty() || pendKey != null) commitWord(false)
        if (seq.isEmpty()) { upper = shift; shift = false; updateShiftLook() }
        seq.append(digit); forced.add(null); colActive = true
        refreshPredictive(ic)
    }

    private fun refreshPredictive(ic: android.view.inputmethod.InputConnection) {
        cands = if (attach != null) fragmentCandidates() else candidates(seq.toString())
        ic.setComposingText(shown(predText()), 1)
        updateSuggestions()
    }

    // @ tuşu: @ . - _ /
    private fun tapSymbolKey() {
        val ic = currentInputConnection ?: return
        val chars = "@.-_/"
        if (symComposing) symIdx = (symIdx + 1) % chars.length
        else { commitWord(false); attach = null; symIdx = 0; symComposing = true }
        ic.setComposingText(chars[symIdx].toString(), 1)
        handler.removeCallbacks(symRunnable)
        handler.postDelayed(symRunnable, 900)
        updateSuggestions()
    }

    // Yazılmakta olan kelimeyi metne işler
    private fun commitWord(space: Boolean, chosen: String? = null, learnIt: Boolean = true) {
        val ic = currentInputConnection ?: return
        checkCursorMoved()
        handler.removeCallbacks(confirmRunnable)
        handler.removeCallbacks(symRunnable)
        val before = attach
        autoSpaced = false
        val text: String? = when {
            seq.isNotEmpty() -> {
                if (chosen != null) chosen
                else {
                    val exact = cands.firstOrNull { it.length == seq.length }
                    exact ?: guessSpelling()
                }
            }
            word.isNotEmpty() || pendKey != null -> chosen ?: pendingText()
            else -> null
        }
        var newAttach: String? = null
        var committedFull: String? = null
        if (text != null) {
            val out = shown(text)
            ic.commitText(out + (if (space) " " else ""), 1)
            committedFull = (before ?: "") + out
            if (learnIt) learn((before ?: "") + text)
            // Üst çubuktan seçildiyse boşluk bırakma, sonuna ek yazılabilsin
            if (!space && chosen != null) newAttach = (before ?: "") + out

        } else {
            ic.finishComposingText()
            if (space) ic.commitText(" ", 1) else newAttach = before
        }
        // Boşluk yazıldıysa: imlecin solundaki kelime sözlükte yoksa "ekle" önerisi çıksın
        if (space) {
            // Kelime bu çağrıda yazıldıysa onu kullan; yazılmadıysa (ör. üst çubuktan seçilmişse) editöre bak
            val full = committedFull
            if (full != null) markUnknown(full) else detectUnknownWord()
        } else {
            recentUnknownWord = null
        }
        word.setLength(0); seq.setLength(0); forced.clear(); colActive = false
        pendKey = null; symComposing = false
        cands = emptyList(); upper = false
        attach = newAttach
        updateSuggestions()
    }

    // Boşluktan sonra imlecin solundaki kelime sözlükte yoksa ⊕ önerisini hazırlar.
    // Kelimenin nasıl yazıldığına (tahmin, çoklu basış, üst çubuk) bakmadan editördeki metne bakar.
    // Ekleme önerisi gösterilebilen alan mı? E-posta ve şifre alanlarında gösterilmez.
    // Adres çubuğu (arama için kullanılır) ve web sayfası alanları dahildir.
    private fun chipAllowedField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: 0
        if (t != 0 && (t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
            else -> true
        }
    }

    // Az önce yazılan kelime sözlükte yoksa ⊕ önerisini hazırlar
    private fun markUnknown(typed: String) {
        recentUnknownWord = null
        if (!chipAllowedField()) return
        val w = typed.lowercase(tr)
        if (w.length < 2 || !w.all { charToDigit.containsKey(it) } || wordSet.contains(w)) return
        recentUnknownWord = typed
    }

    private fun detectUnknownWord() {
        recentUnknownWord = null
        val ic = currentInputConnection ?: return
        if (!chipAllowedField()) return
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: return
        if (!before.endsWith(" ")) return
        val trimmed = before.trimEnd(' ')
        val token = trimmed.takeLastWhile { it.isLetter() }
        if (token.length < 2) return
        // Hemen öncesinde rakam, @, /, . gibi bir karakter varsa (adres, sayı vb.) atla
        val prevCh = trimmed.getOrNull(trimmed.length - token.length - 1)
        if (prevCh != null && (prevCh.isDigit() || prevCh in "@/.:#_-'’")) return
        val w = token.lowercase(tr)
        if (!w.all { charToDigit.containsKey(it) }) return
        if (wordSet.contains(w)) return
        recentUnknownWord = token
    }

    private fun typeText(s: String) {
        val wasAuto = autoSpaced
        commitWord(false)
        attach = null
        val ic = currentInputConnection
        if (ic != null) {
            // "Merhaba. " sonrasında yine noktalama gelirse (..., ?!) otomatik boşluğu geri al
            if (wasAuto && s in spacePunct) {
                val b = ic.getTextBeforeCursor(2, 0)?.toString()
                if (b != null && b.length == 2 && b[1] == ' ' && b[0].toString() in spacePunct) {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            ic.commitText(s, 1)
            autoSpaced = false
            if (s in spacePunct && shouldAutoSpace(ic, s)) {
                ic.commitText(" ", 1)
                autoSpaced = true
            }
        }
        prevWord = null
        updateSuggestions()
    }

    // Uzun basış: Google sesle yazma açılır (TouchPal'daki gibi). Konuşulan metni o klavye doğrudan alana yazar,
    // bitince klavyeye geri dönülür. Etkin bir "sesli giriş" klavyesi bulunamazsa ayar ekranı açılır.
    private fun startVoiceInput() {
        if (!voiceInputEnabled) {
            android.widget.Toast.makeText(this, "Sesle yazma kapalı (Menü ayarlarından açabilirsiniz)", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        commitWord(false)
        attach = null
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        try {
            val imes = imm.enabledInputMethodList.sortedBy { if (it.packageName.contains("google")) 0 else 1 }
            for (imi in imes) {
                if (imi.packageName == packageName) continue
                val voice = imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == "voice" }
                    ?: (0 until imi.subtypeCount).map { imi.getSubtypeAt(it) }.firstOrNull { it.mode == "voice" }
                if (voice != null) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        switchInputMethod(imi.id, voice)
                    } else {
                        @Suppress("DEPRECATION")
                        imm.setInputMethodAndSubtype(window.window!!.attributes.token, imi.id, voice)
                    }
                    return
                }
            }
        } catch (e: Exception) { }
        android.widget.Toast.makeText(
            this,
            "Google sesle yazma bulunamadı. Klavyeleri yönet ekranından \"Google ses girişi\" seçeneğini açın.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { }
    }

    // e-posta, adres, şifre alanlarında ve 3.5 / 12:30 gibi sayılarda boşluk koyma
    private fun shouldAutoSpace(ic: InputConnection, s: String): Boolean {
        val t = currentInputEditorInfo?.inputType ?: 0
        if ((t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return false
        }
        if ((t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        val recent = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
        if (recent.contains("@") || recent.contains("www.") || recent.contains(".com") || recent.contains(".net") || recent.contains(".org")) return false

        if (s == "." || s == "," || s == ":") {
            val b = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (b.length >= 2 && b[b.length - 2].isDigit()) return false
        }
        return true
    }

    private fun backspace() {
        recentUnknownWord = null
        val ic = currentInputConnection ?: return
        autoSpaced = false
        checkCursorMoved()
        when {
            seq.isNotEmpty() -> {
                seq.setLength(seq.length - 1)
                if (forced.isNotEmpty()) forced.removeAt(forced.lastIndex)
                colActive = false
                if (seq.isEmpty()) {
                    ic.setComposingText("", 1); ic.finishComposingText()
                    cands = emptyList(); upper = false; updateSuggestions()
                } else refreshPredictive(ic)
            }
            pendKey != null || word.isNotEmpty() -> {
                handler.removeCallbacks(confirmRunnable)
                colActive = false
                if (pendKey != null) pendKey = null else word.setLength(word.length - 1)
                if (word.isEmpty()) {
                    ic.setComposingText("", 1); ic.finishComposingText(); upper = false
                } else ic.setComposingText(shown(word.toString()), 1)
                updateSuggestions()
            }
            symComposing -> {
                handler.removeCallbacks(symRunnable)
                ic.setComposingText("", 1); ic.finishComposingText()
                symComposing = false; updateSuggestions()
            }
            else -> {
                // Seçili metin varsa onu sil, yoksa bir karakter geri sil
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) { ic.commitText("", 1); attach = null }
                else {
                    ic.deleteSurroundingTextInCodePoints(1, 0)
                    attach = attach?.dropLast(1)?.takeIf { it.isNotEmpty() }
                }
                updateSuggestions()
            }
        }
    }

    private fun enter() {
        commitWord(false)
        attach = null
        prevWord = null
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        updateSuggestions()
    }

    // Uzun basış: alanın kendi eylemi (Gönder, Ara, Git...)
    private fun editorAction() {
        commitWord(false)
        attach = null
        prevWord = null
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        }
        updateSuggestions()
    }

    private fun enterKey(): View {
        val k = key("", accent = true, onLong = { editorAction() }) { enter() }
        k.addView(
            IconView(this, IconView.ENTER, ACCENT, dp(3).toFloat()),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )
        return k
    }

    // Üst çubuktan seçim: sonuna boşluk koymaz, yeni bağımsız kelimeye hazırlar
    private fun pickSuggestion(s: String) {
        if (composingActive()) {
            commitWord(false, s)
        } else {
            currentInputConnection?.commitText(s, 1)
            learn(s)
        }
        seq.setLength(0)
        word.setLength(0)
        cands = emptyList()
        attach = null
        prevWord = s
        updateSuggestions()
    }

    // Sözlüğe eklenebilecek kelime var mı? (+ simgesiyle gösterilir)
    private fun computeChip(): String? {
        if (recentUnknownWord != null && !composingActive() && attach == null) {
            return recentUnknownWord
        }
        val shownFrag: String
        when {
            seq.isNotEmpty() -> {
                if (attach == null) {
                    // Sözlükte tam karşılığı olmayan kelime
                    if (cands.any { it.length == seq.length }) return null
                } else if (!forced.all { it != null }) return null   // ek modunda: tüm harfler seçilince
                shownFrag = shown(predText())
            }
            word.isNotEmpty() || pendKey != null -> {
                if (attach != null && pendKey != null) return null
                shownFrag = shown(pendingText())
            }
            attach != null -> shownFrag = ""
            else -> return null
        }
        val whole = (attach ?: "") + shownFrag
        val w = whole.lowercase(tr)
        if (w.length < 2 || wordSet.contains(w) || !w.all { charToDigit.containsKey(it) }) return null
        return whole
    }

    private fun addToDictionary(w0: String) {
        val w = w0.lowercase(tr)
        if (w.length < 2 || !w.all { charToDigit.containsKey(it) }) return
        addWord(w)
        userCount[w] = (userCount[w] ?: 0) + 1
        saveUserData()
        try {
            android.provider.UserDictionary.Words.addWord(this, w, 250, 0)
        } catch (e: Exception) { }
        recentUnknownWord = null
        android.widget.Toast.makeText(this, "\"$w\" sözlüğe eklendi", android.widget.Toast.LENGTH_SHORT).show()
        val ic = currentInputConnection
        if (seq.isNotEmpty() && ic != null && attach == null) refreshPredictive(ic) else updateSuggestions()
    }

    // X: yazılanı olduğu gibi bırak, öneri çubuğunu kapat
    private fun dismissBar() {
        recentUnknownWord = null
        commitWord(false, learnIt = false)
        attach = null
        prevWord = null
        seq.setLength(0)
        word.setLength(0)
        cands = emptyList()
        letterMode = false
        updateSuggestions()
    }

    private fun updateSuggestions() {
        if (composingActive() || attach != null) recentUnknownWord = null
        if (recentUnknownWord != null) {
            suggestions = emptyList()
        } else {
            letterMode = seq.isNotEmpty() && attach == null && cands.isEmpty()
            suggestions = when {
                letterMode -> letterCandidates()
                seq.length == 1 -> {
                    val letters = letterCandidates()
                    (letters + cands).distinct()
                }
                seq.isNotEmpty() -> cands
                word.isNotEmpty() || pendKey != null ->
                    if (attach != null) suffixCompletions(pendingText()) else prefixCandidates(pendingText())
                symComposing -> emptyList()
                attach != null -> emptyList()
                else -> nextWords(prevWord)
            }
        }
        chipWord = computeChip()
        autoCap()
        updateLeftColumn()
        renderSuggestions()
    }

    private fun renderSuggestions() {
        if (!::suggestRow.isInitialized) return
        suggestRow.removeAllViews()
        val chip = chipWord
        if (suggestions.isEmpty() && chip == null) {
            iconBar.visibility = View.VISIBLE
            suggestBox.visibility = View.GONE
            return
        }
        iconBar.visibility = View.GONE
        suggestBox.visibility = View.VISIBLE
        val isChipOnly = chip != null && suggestions.isEmpty()
        closeBtn.visibility = if (chip != null || suggestions.isNotEmpty()) View.VISIBLE else View.GONE
        suggestLeftSpacer.visibility = if (isChipOnly) View.VISIBLE else View.GONE
        suggestRow.gravity = if (isChipOnly) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)

        fun addChip() {
            if (chip == null) return
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { addToDictionary(chip) }
            }
            c.addView(TextView(this).apply { text = "⊕"; textSize = 28f; setTextColor(ACCENT) })
            c.addView(TextView(this).apply {
                text = chip; textSize = 19f; setTextColor(TXT); setPadding(dp(8), 0, 0, 0)
            })
            suggestRow.addView(c, LinearLayout.LayoutParams(-2, MATCH_PARENT))
        }

        // Kelime adayları varsa + sona, yoksa (veya harf adayları varsa) başa konur
        val chipFirst = suggestions.isEmpty() || letterMode
        if (chipFirst) addChip()
        suggestions.forEachIndexed { i, s ->
            val tv = TextView(this).apply {
                text = shown(s); textSize = if (letterMode) 24f else 19f; gravity = Gravity.CENTER
                setTextColor(if (i == 0 && composingActive() && !(chipFirst && chip != null && !letterMode)) ACCENT else TXT)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { if (letterMode) pickCandidateLetter(s[0]) else pickSuggestion(s) }
            }
            suggestRow.addView(tv, LinearLayout.LayoutParams(-2, MATCH_PARENT))
        }
        if (!chipFirst) addChip()
        suggestScroll.scrollTo(0, 0)
    }

    private fun updateShiftLook() {
        shiftIcon?.setLook(
            when { caps -> IconView.SHIFT_CAPS; shift -> IconView.SHIFT_ON; else -> IconView.SHIFT_OFF },
            if (caps || shift) ACCENT else SHIFTOFF
        )
        updateKeyLabels()
    }

    // Tuş etiketleri: büyük yazılacaksa ABC, küçükse abc
    private fun updateKeyLabels() {
        val up = shift || caps
        letterLabels.forEach { (tv, base) ->
            tv.text = if (up) base.uppercase(tr) else base.lowercase(tr)
        }
    }

    // Bir basış: tek seferlik büyük. Hızlı iki basış: sabit büyük. Sabitken bir basış: kapat.
    private fun onShiftTap() {
        val now = System.currentTimeMillis()
        when {
            caps -> { caps = false; shift = false }
            shift && now - lastShiftTap < 450 -> { caps = true; shift = false }
            else -> shift = !shift
        }
        lastShiftTap = now
        updateShiftLook()
    }

    // Alan cümle başı büyük harf istiyorsa (ör. mesaj kutusu) otomatik büyük harf
    private fun autoCap() {
        if (caps || composingActive()) return
        val ic = currentInputConnection ?: return
        val t = currentInputEditorInfo?.inputType ?: return
        if ((t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return
        val want = ic.getCursorCapsMode(t) != 0
        if (want != shift) { shift = want; updateShiftLook() }
    }

    private fun updatePredLook() {
        predView?.setTextColor(if (predictive) ACCENT else SUBTXT)
    }

    // Sol sütundan harf seçimi: tahmin modunda soldan sağa sırayla, çoklu basışta bekleyen harf için
    private fun pickLetter(ch: Char) {
        val ic = currentInputConnection ?: return
        checkCursorMoved()
        if (!composingActive()) return
        if (seq.isNotEmpty() && forced.size >= seq.length) {
            val k = forced.indexOfFirst { it == null }
            if (k in 0 until seq.length) {
                forced[k] = ch
                refreshPredictive(ic)
            }
        } else if (pendKey != null) {
            val i = pendChars.indexOf(ch)
            if (i >= 0) pendIdx = i
            ic.setComposingText(shown(pendingText()), 1)
            confirmPending()
            handler.removeCallbacks(confirmRunnable)
            colActive = false
            updateSuggestions()
        }
    }

    // Tahmin modunda: harfi henüz seçilmemiş ilk tuşun harfleri. Hepsi seçildiyse sembol sütunu.
    // Üst çubuktaki harf adaylarından biri seçildi
    private fun pickCandidateLetter(ch: Char) {
        val ic = currentInputConnection ?: return
        checkCursorMoved()
        if (!composingActive()) return
        if (letterPos in 0 until seq.length && forced.size >= seq.length) {
            forced[letterPos] = ch
            refreshPredictive(ic)
        }
    }

    private fun activeLetters(): String? {
        if (seq.isNotEmpty()) {
            val k = forced.indexOfFirst { it == null }
            return if (k in 0 until seq.length) groups[seq[k]] else null
        }
        if (colActive && pendKey != null) return pendChars
        return null
    }

    // Sol sütun: her zaman !, ?, ', ~
    private fun updateLeftColumn() {
        if (!::leftCol.isInitialized) return
        leftCol.removeAllViews()
        listOf("!", "?", "'", "~").forEach { sym -> add(leftCol, key(sym) { typeText(sym) }, 1f) }
    }

    private fun showPage(p: View, toggle: Boolean = true) {
        commitWord(false)
        attach = null
        if (p !== cursorPage && selectMode) { selectMode = false; updateSelLook() }
        val target = if (toggle && p !== t9Page && p.visibility == View.VISIBLE) t9Page else p
        if (target === emojiPage) {
            loadRecentEmojis()
            populateRecentRow()
        }
        pages.forEach { it.visibility = if (it === target) View.VISIBLE else View.GONE }
        if (::hideOrReturnTool.isInitialized) {
            val isReturn = (target !== t9Page && target !== numPage)
            hideOrReturnTool.text = if (isReturn) "↩" else "⌄"
            hideOrReturnTool.setTextColor(if (isReturn) ACCENT else ICON)
        }
        if (target === clipPage) refreshClips()
    }

    private fun menuAction(id: Int) {
        commitWord(false)
        attach = null
        currentInputConnection?.performContextMenuAction(id)
    }

    // =====================================================
    //  Arayüz yardımcıları
    // =====================================================

    private fun add(parent: LinearLayout, v: View, w: Float) {
        val lp = if (parent.orientation == LinearLayout.HORIZONTAL)
            LinearLayout.LayoutParams(0, MATCH_PARENT, w)
        else LinearLayout.LayoutParams(MATCH_PARENT, 0, w)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        parent.addView(v, lp)
    }

    private fun key(
        label: String,
        sub: String? = null,
        topSub: String? = null,
        accent: Boolean = false,
        size: Float = 22f,
        repeat: Boolean = false,
        bg: Int = KEY,
        onLong: (() -> Unit)? = null,
        onClick: () -> Unit
    ): FrameLayout {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(6).toFloat() }
        val main = TextView(this).apply {
            text = label; textSize = size; gravity = Gravity.CENTER
            setTextColor(if (accent) ACCENT else TXT)
        }
        f.tag = main
        f.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (sub != null) {
            val s = TextView(this).apply { text = sub; textSize = 11f; setTextColor(SUBTXT) }
            f.addView(s, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(6), dp(3))
            })
        }
        if (topSub != null) {
            val ts = TextView(this).apply { text = topSub; textSize = 12f; setTextColor(SUBTXT) }
            f.addView(ts, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(4), dp(6), 0)
            })
        }
        bindTouch(f, repeat, onLong, onClick)
        return f
    }

    private fun bindTouch(f: View, repeat: Boolean, onLong: (() -> Unit)?, onClick: () -> Unit) {
        if (repeat) {
            val r = object : Runnable {
                override fun run() { playClick(); onClick(); handler.postDelayed(this, 55) }
            }
            f.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; playClick(); onClick(); handler.postDelayed(r, 400) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; handler.removeCallbacks(r) }
                }
                true
            }
        } else {
            f.setOnClickListener { playClick(); onClick() }
            if (onLong != null) f.setOnLongClickListener { playClick(); onLong(); true }
        }
    }

    // Çizilmiş simge tuşu (ok, boşluk); koyu arka planlı
    private fun iconKey(kind: Int, repeat: Boolean = false, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply { setColor(DIM); cornerRadius = dp(6).toFloat() }
        f.addView(IconView(this, kind, ACCENT, dp(2).toFloat() * 1.2f),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bindTouch(f, repeat, null, onClick)
        return f
    }

    private fun t9Key(label: String, chars: String, digit: String): View {
        val k = key(if (digit == "1") label else label.lowercase(tr), digit, onLong = { typeText(digit) }) {
            when {
                digit == "1" -> tapSymbolKey()
                predictive -> tapPredictive(digit[0])
                else -> tapMulti(label, chars)
            }
        }
        if (digit != "1") letterLabels.add((k.tag as TextView) to label)
        return k
    }

    // Boşluk tuşu (TouchPal görünümü): gri "◂ Türkçe ▸" yazısı, sağ üstte çizilmiş mikrofon.
    // Uzun basınca Google sesle yazma açılır.
    private fun spaceKey(label: String, w: Float = 1f): View {
        val k = key(label, size = 14f, onLong = { startVoiceInput() }) {
            // Noktalamadan sonra boşluk zaten konduysa ikinci boşluk koyma
            if (autoSpaced && !composingActive()) autoSpaced = false
            else commitWord(true)
        }
        val gray = SUBTXT
        (k.tag as? TextView)?.apply {
            setTextColor(gray)
            if (label.isNotEmpty()) {
                // Oklar yazıdan biraz küçük
                val sp = android.text.SpannableString(label)
                label.forEachIndexed { i, c ->
                    if (c == '◂' || c == '▸') {
                        sp.setSpan(
                            android.text.style.RelativeSizeSpan(0.7f), i, i + 1,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                text = sp
            }
        }
        k.addView(
            IconView(this, IconView.MIC, if (lightTheme) Color.BLACK else Color.WHITE, dp(2).toFloat()),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                rightMargin = dp(8)
            }
        )
        return k
    }

    private fun navInset(): Int {
        if (Build.VERSION.SDK_INT < 35) return 0
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // =====================================================
    //  Sayfalar
    // =====================================================

    private fun buildT9Page(): View {
        letterLabels.clear()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftCol = left
        lastColSig = "?"
        updateLeftColumn()
        add(main, left, 0.65f)

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf(Triple("@", "", "1"), Triple("ABC", "abcç", "2"), Triple("DEF", "def", "3")),
            listOf(Triple("GHİ", "gğhıi", "4"), Triple("JKL", "jkl", "5"), Triple("MNO", "mnoö", "6")),
            listOf(Triple("PQRS", "pqrsş", "7"), Triple("TUV", "tuüv", "8"), Triple("WXYZ", "wxyz", "9"))
        )
        rows.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { (l, c, d) -> add(row, t9Key(l, c, d), 1f) }
            add(mid, row, 1f)
        }
        add(main, mid, 3f)

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(right, key("⌫", accent = true, repeat = true) { backspace() }, 1f)
        val pk = key("a✓", sub = "0", accent = true, onLong = { typeText("0") }) {
            commitWord(false)
            predictive = !predictive
            updatePredLook()
        }
        predView = pk.tag as TextView
        updatePredLook()
        add(right, pk, 1f)
        val sk = key("", accent = true) { onShiftTap() }
        val shiftIc = IconView(this, IconView.SHIFT_OFF, SHIFTOFF, dp(3).toFloat())
        sk.addView(shiftIc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        shiftIcon = shiftIc
        updateShiftLook()
        add(right, sk, 1f)
        add(main, right, 0.65f)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(bottom, key("12#", accent = true) { showPage(sym1) }, 0.65f)
        add(bottom, key(",") { typeText(",") }, 0.45f)
        add(bottom, spaceKey("◂ Türkçe ▸"), 1.95f)
        add(bottom, key(".") { typeText(".") }, 0.45f)
        add(bottom, enterKey(), 0.65f)

        add(page, main, 3f)
        add(page, bottom, 1f)
        return page
    }

    private class SK(val l: String, val sub: String? = null, val w: Float = 1f)

    private fun symRow(items: List<SK>, backspaceW: Float = 0f): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { k ->
            val long: (() -> Unit)? = k.sub?.let { s -> { typeText(s) } }
            add(row, key(k.l, k.sub, size = 20f, onLong = long) { typeText(k.l) }, k.w)
        }
        if (backspaceW > 0f) add(row, key("⌫", accent = true, repeat = true) { backspace() }, backspaceW)
        return row
    }

    private fun buildSym1(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val d = 1.4f
        add(page, symRow(listOf(SK("1", null, d), SK("2", null, d), SK("3", null, d),
            SK("+"), SK("-", "_"), SK("@", "&"), SK("₺", "€"), SK("(", "<"), SK(")", ">"))), 1f)
        add(page, symRow(listOf(SK("4", null, d), SK("5", null, d), SK("6", null, d),
            SK("*"), SK("/"), SK("'", "‹"), SK("\"", "«"), SK(":", ";"), SK("#", "%"))), 1f)
        add(page, symRow(listOf(SK("7", null, d), SK("8", null, d), SK("9", null, d),
            SK(","), SK("="), SK("!", "¡"), SK("?", "¿")), 2f), 1f)
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(b, key("abc", accent = true) { showPage(t9Page) }, 1.4f)
        add(b, key("0", size = 20f) { typeText("0") }, 1.4f)
        add(b, key(".", size = 20f) { typeText(".") }, 1.4f)
        add(b, spaceKey(""), 3.6f)
        add(b, key("1/2", accent = true) { showPage(sym2) }, 1.2f)
        add(b, enterKey(), 1.2f)
        add(page, b, 1f)
        return page
    }

    private fun buildSym2(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(page, symRow(listOf("[", "]", "{", "}", "<", ">", "&", "_", "%", "√").map { SK(it) }), 1f)
        add(page, symRow(listOf(SK("|", "¦"), SK("\\", "§"), SK("~"), SK("•", "°"), SK("`"),
            SK("…"), SK("€", "\$"), SK("¥"), SK("£"), SK("¢"))), 1f)
        add(page, symRow(listOf(SK("α"), SK("β"), SK("^", "Δ"), SK("®"), SK("©"),
            SK("™"), SK("π", "Π"), SK("¤", "Φ")), 2f), 1f)
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(b, key("abc", accent = true) { showPage(t9Page) }, 1.4f)
        add(b, key("☺") { showPage(emojiPage) }, 1.4f)
        add(b, spaceKey(""), 4.2f)
        add(b, key("2/2", accent = true) { showPage(sym1) }, 1.5f)
        add(b, enterKey(), 1.5f)
        add(page, b, 1f)
        return page
    }

    private val recentEmojis = mutableListOf<String>()

    private fun loadRecentEmojis() {
        recentEmojis.clear()
        val saved = prefs.getString("recent_emojis", "👍 😀 😂 🙏 ❤️ 🔥 ✨ 😊") ?: ""
        recentEmojis.addAll(saved.split(" ").filter { it.isNotEmpty() }.take(8))
    }

    private val skinToneBaseEmojis = setOf(
        "👍", "👎", "👏", "🙏", "💪", "👋", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘",
        "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "✍️",
        "🙋", "💁", "🙅", "🙆", "🙇", "🤷", "🤦", "💆", "💇", "🚶", "🏃",
        "🧑", "👦", "👧", "👨", "👩", "🧓", "👴", "👵", "🤝", "🤲", "🦾", "🦿"
    )

    private fun showSkinTonePopup(anchor: View, baseEmoji: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D3748"))
                cornerRadius = dp(24).toFloat()
            }
        }
        val variants = listOf(baseEmoji) + listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF").map { baseEmoji + it }
        val popup = PopupWindow(layout, -2, -2, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        variants.forEach { v ->
            val tv = TextView(this).apply {
                text = v
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    typeText(v)
                    recordRecentEmoji(v)
                    popup.dismiss()
                }
            }
            layout.addView(tv)
        }
        layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val screenWidth = resources.displayMetrics.widthPixels
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        var screenX = loc[0] + (anchor.width - layout.measuredWidth) / 2
        val rightMargin = dp(16)
        if (screenX + layout.measuredWidth > screenWidth - rightMargin) {
            screenX = screenWidth - layout.measuredWidth - rightMargin
        }
        if (screenX < rightMargin) {
            screenX = rightMargin
        }
        val xOff = screenX - loc[0]
        val yOff = -layout.measuredHeight - anchor.height - dp(8)
        popup.showAsDropDown(anchor, xOff, yOff)
    }

    private lateinit var recentRowContainer: LinearLayout

    private fun recordRecentEmoji(e: String) {
        recentEmojis.remove(e)
        recentEmojis.add(0, e)
        if (recentEmojis.size > 8) recentEmojis.removeAt(recentEmojis.size - 1)
        prefs.edit().putString("recent_emojis", recentEmojis.joinToString(" ")).apply()
        populateRecentRow()
    }

    private fun populateRecentRow() {
        if (!::recentRowContainer.isInitialized) return
        recentRowContainer.removeAllViews()
        for (i in 0 until 8) {
            val e = recentEmojis.getOrNull(i) ?: ""
            val tv = TextView(this).apply {
                text = e
                textSize = 26f
                gravity = Gravity.CENTER
                if (e.isNotEmpty()) {
                    setOnClickListener {
                        typeText(e)
                        recordRecentEmoji(e)
                    }
                    if (skinToneBaseEmojis.any { e.startsWith(it) }) {
                        setOnLongClickListener {
                            showSkinTonePopup(it, e.substring(0, 2))
                            true
                        }
                    }
                }
            }
            recentRowContainer.addView(tv, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
    }

    private fun buildEmojiPage(): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        loadRecentEmojis()

        val titleRecent = TextView(this).apply {
            text = "Son kullanılanlar"
            textSize = 13f
            setTextColor(SUBTXT)
            setPadding(dp(16), dp(10), dp(16), dp(2))
        }
        col.addView(titleRecent)

        recentRowContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        populateRecentRow()
        col.addView(recentRowContainer)

        val titleAll = TextView(this).apply {
            text = "Yüz ifadeleri ve insanlar"
            textSize = 13f
            setTextColor(SUBTXT)
            setPadding(dp(16), dp(14), dp(16), dp(2))
        }
        col.addView(titleAll)

        EMOJIS.split(" ").filter { it.isNotEmpty() }.chunked(8).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 8) {
                val e = chunk.getOrNull(i)
                val tv = TextView(this).apply {
                    text = e ?: ""; textSize = 26f; gravity = Gravity.CENTER
                    if (e != null) {
                        setOnClickListener {
                            typeText(e)
                            recordRecentEmoji(e)
                        }
                        if (e in skinToneBaseEmojis) {
                            setOnLongClickListener {
                                showSkinTonePopup(it, e)
                                true
                            }
                        }
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            col.addView(row)
        }
        scroll.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(bottom, key("ABC", accent = true, size = 18f) { showPage(t9Page) }, 1.5f)
        add(bottom, spaceKey("", 1f), 4f)
        add(bottom, key("⌫", accent = true, repeat = true) { backspace() }, 1.5f)
        panel.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
        return panel
    }

    // ---------- İmleç menüsü ----------

    // Ok / Giriş / Son tuşları; "Seç" açıkken imleç hareketi metni seçer
    private fun sendNav(code: Int) {
        commitWord(false)
        attach = null
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        val meta = if (selectMode) KeyEvent.META_SHIFT_ON else 0
        if (selectMode) ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, KeyEvent.META_SHIFT_ON))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        if (selectMode) ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    private fun updateSelLook() {
        if (!::selKey.isInitialized) return
        (selKey.background as? GradientDrawable)?.setColor(if (selectMode) SELBG else KEY)
        (selKey.tag as? TextView)?.setTextColor(if (selectMode) ACCENT else TXT)
    }

    private fun buildCursorPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(vararg k: View): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            k.forEach { add(r, it, 1f) }
            return r
        }
        fun txt(label: String, f: () -> Unit) = key(label, size = 16f) { f() }
        selKey = key("Seç", size = 16f) { selectMode = !selectMode; updateSelLook() }
        updateSelLook()
        add(page, row(
            txt("Pano") { showPage(clipPage) },
            iconKey(IconView.UP, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_UP) },
            txt("Tümü") { menuAction(android.R.id.selectAll) },
            txt("Kopyala") { menuAction(android.R.id.copy) },
            key("⌫", accent = true, bg = DIM, repeat = true) { backspace() }), 1f)
        add(page, row(
            iconKey(IconView.LEFT, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_LEFT) },
            selKey,
            iconKey(IconView.RIGHT, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_RIGHT) },
            txt("Kes") { menuAction(android.R.id.cut) },
            iconKey(IconView.SPACE) { commitWord(true) }), 1f)
        add(page, row(
            txt("Giriş") { sendNav(KeyEvent.KEYCODE_MOVE_HOME) },
            iconKey(IconView.DOWN, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_DOWN) },
            txt("Son") { sendNav(KeyEvent.KEYCODE_MOVE_END) },
            txt("Yapıştır") { menuAction(android.R.id.paste) },
            key("↩", accent = true, bg = DIM) { showPage(t9Page) }), 1f)
        return page
    }

    // ---------- Pano geçmişi ----------

    // Kopyalanan metni geçmişe ekler. force=false: panel açılırken, aynı metin tekrar eklenmesin
    private fun captureClip(force: Boolean) {
        try {
            val c = clipboard.primaryClip ?: return
            if (c.itemCount == 0) return
            // Şifre yöneticisi gibi "hassas" işaretli kopyaları kaydetme
            if (c.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val t = c.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (!force && t == lastClip) return
            lastClip = t
            if (t.isNotBlank() && t.length <= 5000) ClipStore.add(this, t)
        } catch (e: Exception) { }
    }

    private fun openClipEditor(text: String?, pinned: Boolean) {
        val i = Intent(this, ClipEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (text != null) { i.putExtra("text", text); i.putExtra("pinned", pinned) }
        startActivity(i)
    }

    private fun pasteClip(text: String) {
        commitWord(false)
        attach = null
        currentInputConnection?.commitText(text, 1)
        prevWord = null
        showPage(t9Page)
        updateSuggestions()
    }

    // Kart: dokun = yapıştır, uzun bas = düzenle/kilitle, sola-sağa kaydır = sil
    private fun makeCard(
        text: String, bg: Int, pinned: Boolean, swipeable: Boolean,
        onTap: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit
    ): View {
        val card = FrameLayout(this)
        card.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(6).toFloat() }
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.parseColor("#222222"))
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, FrameLayout.LayoutParams(MATCH_PARENT, -2))
        if (pinned) {
            card.addView(TextView(this).apply { this.text = "📌"; textSize = 12f },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        var downX = 0f
        var downY = 0f
        var dragging = false
        var longFired = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longRunnable = Runnable { longFired = true; onLong() }
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; longFired = false
                    handler.postDelayed(longRunnable, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (swipeable && !dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true
                        handler.removeCallbacks(longRunnable)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        handler.removeCallbacks(longRunnable)
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.alpha = 1f - Math.min(1f, Math.abs(dx) / v.width)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    val dx = e.rawX - downX
                    if (dragging) {
                        if (Math.abs(dx) > v.width / 3) onDelete()
                        else v.animate().translationX(0f).alpha(1f).setDuration(120).start()
                    } else if (!longFired && Math.abs(dx) < slop && Math.abs(e.rawY - downY) < slop) {
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    v.translationX = 0f
                    v.alpha = 1f
                }
            }
            true
        }
        return card
    }

    private fun refreshClips() {
        if (!::clipLeft.isInitialized) return
        captureClip(false)
        clipLeft.removeAllViews()
        clipRight.removeAllViews()
        var hl = 0
        var hr = 0
        fun put(v: View, lines: Int) {
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            if (hl <= hr) { clipLeft.addView(v, lp); hl += lines } else { clipRight.addView(v, lp); hr += lines }
        }
        val hintBg = Color.parseColor("#F8EDC8")
        if (!prefs.getBoolean("hint_del", false)) {
            put(makeCard("Kartı silmek için sola ya da sağa kaydırın", hintBg, false, true, {}, {}) {
                prefs.edit().putBoolean("hint_del", true).apply(); refreshClips()
            }, 3)
        }
        if (!prefs.getBoolean("hint_edit", false)) {
            put(makeCard("Düzenlemek ve kilitlemek için uzun basın", hintBg, false, true, {}, {}) {
                prefs.edit().putBoolean("hint_edit", true).apply(); refreshClips()
            }, 3)
        }
        val all = ClipStore.load(this)
        val sorted = all.filter { it.pinned } + all.filter { !it.pinned }
        if (sorted.isEmpty()) {
            put(makeCard("Henüz kopyalanan bir şey yok. Bir metni kopyalayınca burada görünür.",
                hintBg, false, false, {}, {}, {}), 3)
        }
        sorted.forEach { c ->
            put(makeCard(c.text, Color.parseColor("#FAFAFA"), c.pinned, !c.pinned,
                { pasteClip(c.text) },
                { openClipEditor(c.text, c.pinned) },
                { ClipStore.remove(this, c.text); refreshClips() }),
                minOf(8, c.text.length / 16 + 1))
        }
    }

    private fun buildClipPage(): View {
        val page = FrameLayout(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = TextView(this).apply {
            text = "+   YENİ OLUŞTUR"
            textSize = 17f
            setTextColor(TXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(16), 0)
            setOnClickListener { openClipEditor(null, false) }
        }
        box.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        val scroll = ScrollView(this)
        val cols = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        clipLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(clipLeft, LinearLayout.LayoutParams(0, -2, 1f))
        cols.addView(clipRight, LinearLayout.LayoutParams(0, -2, 1f))
        scroll.addView(cols)
        box.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        page.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return page
    }

    private fun buildSettingsPage(): View {
        val page = FrameLayout(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }

        var predLabel: TextView? = null

        fun makeTile(iconText: String?, iconKind: Int?, titleText: String, onClick: () -> Unit, onLabelCreated: ((TextView) -> Unit)? = null): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(4), dp(8), dp(4), dp(8))
                val iconView: View = if (iconKind != null) {
                    FrameLayout(context).apply {
                        addView(IconView(context, iconKind, TXT, dp(2).toFloat()), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
                    }
                } else {
                    TextView(context).apply {
                        text = iconText ?: ""
                        textSize = 28f
                        gravity = Gravity.CENTER
                        setTextColor(TXT)
                    }
                }
                val label = TextView(context).apply {
                    text = titleText
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(ICON)
                    setPadding(0, dp(6), 0, 0)
                }
                onLabelCreated?.invoke(label)
                addView(iconView, LinearLayout.LayoutParams(MATCH_PARENT, -2))
                addView(label, LinearLayout.LayoutParams(MATCH_PARENT, -2))
                setOnClickListener { onClick() }
            }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tTemalar = makeTile("👕", null, "Temalar", {
            showPage(themePage)
        })
        var sesLabel: TextView? = null
        val tSes = makeTile(null, IconView.MIC, if (voiceInputEnabled) "Sesle Yazma AÇIK" else "Sesle Yazma KAPALI", {
            voiceInputEnabled = !voiceInputEnabled
            sesLabel?.text = if (voiceInputEnabled) "Sesle Yazma AÇIK" else "Sesle Yazma KAPALI"
            sesLabel?.setTextColor(if (voiceInputEnabled) ACCENT else ICON)
            android.widget.Toast.makeText(this, if (voiceInputEnabled) "Sesle Yazma AÇIK" else "Sesle Yazma KAPALI", android.widget.Toast.LENGTH_SHORT).show()
        }, onLabelCreated = { lbl ->
            sesLabel = lbl
            lbl.setTextColor(if (voiceInputEnabled) ACCENT else ICON)
        })
        val tBoyut = makeTile("⤢", null, "Yeniden Boyutlandır", {
            showResizeOverlay()
        })
        val tDuzen = makeTile("⌨", null, "Düzen", {
            showPage(t9Page)
        })
        listOf(tTemalar, tSes, tBoyut, tDuzen).forEach {
            row1.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val tTahmin = makeTile("🕮", null, if (predictive) "Tahmin AÇIK" else "Tahmin KAPALI", {
            predictive = !predictive
            updatePredLook()
            predLabel?.text = if (predictive) "Tahmin AÇIK" else "Tahmin KAPALI"
            predLabel?.setTextColor(if (predictive) ACCENT else ICON)
            val ic = currentInputConnection
            if (seq.isNotEmpty() && ic != null && attach == null) refreshPredictive(ic) else updateSuggestions()
            android.widget.Toast.makeText(this, if (predictive) "Tahmin AÇIK" else "Tahmin KAPALI", android.widget.Toast.LENGTH_SHORT).show()
        }, onLabelCreated = { lbl ->
            predLabel = lbl
            lbl.setTextColor(if (predictive) ACCENT else ICON)
        })
        val tDil = makeTile("🌐", null, "Dil", {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        val tPano = makeTile("📋", null, "Pano", {
            showPage(clipPage)
        })
        var ayarlarLabel: TextView? = null
        val tAyarlar = makeTile("⚙", null, if (vibrateEnabled) "Titreşim AÇIK" else "Titreşim KAPALI", {
            vibrateEnabled = !vibrateEnabled
            ayarlarLabel?.text = if (vibrateEnabled) "Titreşim AÇIK" else "Titreşim KAPALI"
            ayarlarLabel?.setTextColor(if (vibrateEnabled) ACCENT else ICON)
            android.widget.Toast.makeText(this, if (vibrateEnabled) "Titreşim AÇIK" else "Titreşim KAPALI", android.widget.Toast.LENGTH_SHORT).show()
        }, onLabelCreated = { lbl ->
            ayarlarLabel = lbl
            lbl.setTextColor(if (vibrateEnabled) ACCENT else ICON)
        })
        listOf(tTahmin, tDil, tPano, tAyarlar).forEach {
            row2.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }

        container.addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, -2))
        container.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, -2))
        page.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return page
    }

    // ---------- Rakam sayfası (sayı / telefon / PIN alanlarında otomatik açılır) ----------

    private fun isNumericField(info: EditorInfo?): Boolean {
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
    }

    // Sağ alttaki tuşun yazısı alanın eylemine göre değişir: Bitti, Git, İleri, Ara, Gönder
    private fun updateNumActionLabel(info: EditorInfo?) {
        val lbl: String = run {
            if (info == null) return@run "↵"
            val custom = info.actionLabel
            if (!custom.isNullOrEmpty()) return@run custom.toString()
            when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_DONE -> "Bitti"
                EditorInfo.IME_ACTION_GO -> "Git"
                EditorInfo.IME_ACTION_NEXT -> "İleri"
                EditorInfo.IME_ACTION_SEARCH -> "Ara"
                EditorInfo.IME_ACTION_SEND -> "Gönder"
                EditorInfo.IME_ACTION_PREVIOUS -> "Geri"
                else -> "↵"
            }
        }
        numActionLabel?.apply {
            text = lbl
            textSize = if (lbl == "↵") 26f else 16f
        }
    }

    // Rakamlar: tür bilgisi olmayan (özel PIN) alanlara tuş olayı olarak, diğerlerine metin olarak gider
    private fun typeNum(ch: String) {
        val t = currentInputEditorInfo?.inputType ?: 0
        if (t == 0 && ch.length == 1 && ch[0] in '0'..'9') {
            commitWord(false)
            sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + (ch[0] - '0'))
        } else {
            typeText(ch)
        }
    }

    private fun numBackspace() {
        if ((currentInputEditorInfo?.inputType ?: 0) == 0) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            backspace()
        }
    }

    // Alanın bir eylemi varsa (Bitti, Git...) onu çalıştırır, yoksa Enter gönderir
    private fun numAction() {
        val ei = currentInputEditorInfo
        val a = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED) editorAction() else enter()
    }

    private fun buildNumPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun digit(d: String) = key(d, size = 28f) { typeNum(d) }
        fun sym(s: String) = key(s, accent = true, bg = DIM, size = 24f) { typeNum(s) }
        fun row(vararg v: View): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            v.forEachIndexed { i, view -> add(r, view, if (i == 0 || i == v.size - 1) 0.85f else 1f) }
            return r
        }
        val back = key("↩", accent = true, bg = DIM, size = 26f) { showPage(t9Page) }
        val space = iconKey(IconView.SPACE) { currentInputConnection?.commitText(" ", 1) }
        val action = key("↵", accent = true, bg = DIM, size = 26f) { numAction() }
        numActionLabel = action.tag as? TextView
        add(page, row(sym("+"), digit("1"), digit("2"), digit("3"),
            key("⌫", accent = true, bg = DIM, repeat = true) { numBackspace() }), 1f)
        add(page, row(sym("-"), digit("4"), digit("5"), digit("6"), back), 1f)
        add(page, row(sym("."), digit("7"), digit("8"), digit("9"), space), 1f)
        add(page, row(sym("/"), sym("*"), digit("0"), sym("#"), action), 1f)
        return page
    }

    // ---------- Temalar (Koyu / Beyaz) ----------

    private fun applyTheme() {
        if (lightTheme) {
            BG = Color.parseColor("#E4E7EB")
            KEY = Color.parseColor("#FFFFFF")
            DIM = Color.parseColor("#D3D8DD")
            ACCENT = Color.parseColor("#00897B")
            TXT = Color.parseColor("#202124")
            SUBTXT = Color.parseColor("#6F757A")
            ICON = Color.parseColor("#5F6368")
            SELBG = Color.parseColor("#B2DFDB")
            SHIFTOFF = Color.parseColor("#5FA79D")
        } else {
            BG = Color.parseColor("#212121")
            KEY = Color.parseColor("#2E3236")
            DIM = Color.parseColor("#262829")
            ACCENT = Color.parseColor("#5FD3C5")
            TXT = Color.WHITE
            SUBTXT = Color.parseColor("#8A8E92")
            ICON = Color.LTGRAY
            SELBG = Color.parseColor("#3F6B66")
            SHIFTOFF = Color.parseColor("#7FA8A3")
        }
    }

    // Alt gezinme çubuğu simgeleri: beyaz temada koyu, koyu temada açık görünsün
    private fun applyNavBarAppearance() {
        try {
            val w = window?.window ?: return
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = lightTheme
        } catch (e: Exception) { }
    }

    private fun setLightTheme(light: Boolean) {
        if (lightTheme == light) return
        lightTheme = light
        prefs.edit().putBoolean("theme_light", light).apply()
        applyTheme()
        // Bütün görünümü yeni renklerle baştan kur, tema sayfasında kal
        setInputView(onCreateInputView())
        showPage(themePage)
        android.widget.Toast.makeText(this, if (light) "Beyaz tema" else "Koyu tema", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildThemePage(): View {
        val page = FrameLayout(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun card(name: String, light: Boolean): View {
            val selected = lightTheme == light
            val pBg = Color.parseColor(if (light) "#E4E7EB" else "#212121")
            val pKey = Color.parseColor(if (light) "#FFFFFF" else "#2E3236")
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(10).toFloat()
                    if (selected) setStroke(dp(2), ACCENT)
                }
                setOnClickListener { setLightTheme(light) }
            }
            // Küçük klavye önizlemesi
            val preview = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    setColor(pBg)
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.GRAY)
                }
            }
            for (r in 0 until 3) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until 4) {
                    val k = View(this).apply {
                        background = GradientDrawable().apply { setColor(pKey); cornerRadius = dp(3).toFloat() }
                    }
                    line.addView(k, LinearLayout.LayoutParams(0, dp(16), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                }
                preview.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, -2))
            }
            box.addView(preview, LinearLayout.LayoutParams(dp(130), -2))
            box.addView(TextView(this).apply {
                text = if (selected) "✓ $name" else name
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(if (selected) ACCENT else TXT)
                setPadding(0, dp(8), 0, 0)
            })
            return box
        }
        row.addView(card("Koyu", false), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(card("Beyaz", true), LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return page
    }

    // =====================================================
    //  Ana görünüm
    // =====================================================

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        keyboardRootView = root
        // Alt gezinme çubuğunun altında kalmaması için boşluk bırak; sol/sağ boşluklar yeniden boyutlandırmadan gelir
        navBottomPx = navInset()
        applyRootPadding()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            navBottomPx = if (nav > 0) nav else navInset()
            applyRootPadding()
            insets
        }

        // Üst şerit: simgeler veya kelime önerileri
        val topFrame = FrameLayout(this)
        iconBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tool(txt: String, f: () -> Unit) = TextView(this).apply {
            text = txt; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(ICON); setOnClickListener { f() }
        }
        val hideBtn = tool("⌄") {
            if (::t9Page.isInitialized && t9Page.visibility != View.VISIBLE && numPage.visibility != View.VISIBLE) {
                showPage(t9Page)
            } else {
                requestHideSelf(0)
            }
        }
        fun iconTool(kind: Int, f: () -> Unit): FrameLayout {
            val fv = FrameLayout(this)
            fv.addView(IconView(this, kind, ICON, dp(2).toFloat()), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            fv.setOnClickListener { f() }
            return fv
        }
        hideOrReturnTool = hideBtn
        listOf(
            tool("☰") { showPage(settingsPage) },
            iconTool(IconView.CURSOR) { showPage(cursorPage) },
            tool("📋") { showPage(clipPage) },
            tool("☺") { showPage(emojiPage) },
            hideBtn
        ).forEach { iconBar.addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        suggestLeftSpacer = View(this)
        suggestScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        suggestRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestScroll.addView(suggestRow, FrameLayout.LayoutParams(-2, MATCH_PARENT))
        closeBtn = TextView(this).apply {
            text = "✕"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(ICON); setOnClickListener { dismissBar() }
        }
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        suggestBox.addView(suggestLeftSpacer, LinearLayout.LayoutParams(dp(48), MATCH_PARENT))
        suggestBox.addView(suggestScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        suggestBox.addView(closeBtn, LinearLayout.LayoutParams(dp(48), MATCH_PARENT))
        topFrame.addView(iconBar, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        topFrame.addView(suggestBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(topFrame, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        // Sayfalar
        t9Page = buildT9Page()
        sym1 = buildSym1()
        sym2 = buildSym2()
        emojiPage = buildEmojiPage()
        cursorPage = buildCursorPage()
        clipPage = buildClipPage()
        settingsPage = buildSettingsPage()
        themePage = buildThemePage()
        numPage = buildNumPage()
        pages = listOf(t9Page, sym1, sym2, emojiPage, cursorPage, clipPage, settingsPage, themePage, numPage)
        val content = FrameLayout(this)
        contentLayoutView = content
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        pages.forEach { it.visibility = if (it === t9Page) View.VISIBLE else View.GONE }
        val savedHeight = prefs.getInt("kb_height", 0).let {
            if (it > 0) it.coerceIn(dp(160), dp(520)) else dp(4 * 58)
        }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, savedHeight))

        renderSuggestions()

        // Klavye, yükseltilebilsin diye şeffaf bir kabın içinde; alt boşluk "kaldırma" ile ayarlanır
        val kbFrame = FrameLayout(this).apply { setBackgroundColor(BG) }
        kbFrame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, -2))
        keyboardFrameView = kbFrame
        val outer = FrameLayout(this)
        outer.addView(kbFrame, FrameLayout.LayoutParams(MATCH_PARENT, -2, Gravity.BOTTOM).apply {
            bottomMargin = prefs.getInt("kb_lift", 0).coerceIn(0, dp(200))
        })
        applyNavBarAppearance()
        return outer
    }

    private fun applyRootPadding() {
        keyboardRootView?.setPadding(
            dp(3) + prefs.getInt("kb_ml", 0), 0,
            dp(3) + prefs.getInt("kb_mr", 0), dp(4) + navBottomPx
        )
        syncResizeOverlay()
    }

    // Yeniden boyutlandırma kaplamasını klavyenin (üst şerit + tuşlar) alanına oturtur
    private fun syncResizeOverlay() {
        val frame = keyboardFrameView ?: return
        val ov = frame.findViewWithTag<View>("resize_overlay") ?: return
        val lp = ov.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = dp(3) + prefs.getInt("kb_ml", 0)
        lp.rightMargin = dp(3) + prefs.getInt("kb_mr", 0)
        lp.bottomMargin = dp(4) + navBottomPx
        ov.layoutParams = lp
    }

    // TouchPal tarzı: klavye kararır, kesikli çerçeve ve mavi tutamaçlar çıkar.
    // Üstteki tutamaç = yükseklik, soldaki/sağdaki = o taraftan daralma, ortadaki daire = klavyeyi yukarı/aşağı kaldırma.
    private fun showResizeOverlay() {
        showPage(t9Page)
        val frame = keyboardFrameView ?: return
        val content = contentLayoutView ?: return
        frame.findViewWithTag<View>("resize_overlay")?.let { frame.removeView(it) }

        val screenW = resources.displayMetrics.widthPixels
        val minH = dp(170)
        val maxH = dp(520)
        val maxSide = (screenW * 0.35f).toInt()
        val maxLift = dp(200)
        val blue = Color.parseColor("#2F8CFF")

        val overlay = FrameLayout(this).apply {
            tag = "resize_overlay"
            isClickable = true                      // altındaki tuşlara dokunuş geçmesin
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                setStroke(dp(1), Color.WHITE, dp(6).toFloat(), dp(4).toFloat())
            }
        }

        fun setHeight(h: Int) {
            val nh = h.coerceIn(minH, maxH)
            content.layoutParams = content.layoutParams.apply { height = nh }
            content.requestLayout()
            prefs.edit().putInt("kb_height", nh).apply()
        }
        fun setSide(key: String, m: Int) {
            prefs.edit().putInt(key, m.coerceIn(0, maxSide)).apply()
            applyRootPadding()
        }
        fun setLift(v: Int) {
            val nv = v.coerceIn(0, maxLift)
            prefs.edit().putInt("kb_lift", nv).apply()
            (frame.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = nv
                frame.layoutParams = it
            }
        }

        // horizontal: sürükleme ekseni; startValue: sürükleme başındaki değer; onDelta(başlangıç, fark)
        fun makeDrag(target: View, horizontal: Boolean, startValue: () -> Int, onDelta: (Int, Int) -> Unit) {
            var down = 0f
            var start = 0
            target.setOnTouchListener { v, e ->
                val pos = if (horizontal) e.rawX else e.rawY
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        down = pos
                        start = startValue()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> onDelta(start, (pos - down).toInt())
                }
                true
            }
        }

        val stroke = dp(3).toFloat()

        // Ortadaki daire + düğmeler
        val joy = JoystickView(this)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun btn(label: String, bg: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(6).toFloat() }
            setOnClickListener { f() }
        }
        buttons.addView(btn("Sıfırla", KEY, TXT) {
            prefs.edit().remove("kb_height").remove("kb_ml").remove("kb_mr").remove("kb_lift").apply()
            content.layoutParams = content.layoutParams.apply { height = dp(4 * 58) }
            content.requestLayout()
            (frame.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = 0
                frame.layoutParams = it
            }
            applyRootPadding()
            android.widget.Toast.makeText(this, "Boyut sıfırlandı", android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        buttons.addView(btn("✓ Tamam", ACCENT, Color.parseColor("#10201A")) {
            frame.removeView(overlay)
            android.widget.Toast.makeText(this, "Klavye boyutu kaydedildi", android.widget.Toast.LENGTH_SHORT).show()
        })
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        center.addView(joy, LinearLayout.LayoutParams(dp(76), dp(76)))
        center.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        overlay.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        makeDrag(joy, false, { prefs.getInt("kb_lift", 0) }) { s0, d -> setLift(s0 - d) }

        // Üst tutamaç (yükseklik): ^ ve v
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        top.addView(IconView(this, IconView.UP, blue, stroke), LinearLayout.LayoutParams(dp(64), dp(30)))
        top.addView(IconView(this, IconView.DOWN, blue, stroke), LinearLayout.LayoutParams(dp(64), dp(30)))
        overlay.addView(top, FrameLayout.LayoutParams(dp(150), dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        makeDrag(top, false, { if (content.height > 0) content.height else dp(4 * 58) }) { s0, d -> setHeight(s0 - d) }

        // Sol ve sağ tutamaçlar (genişlik)
        val left = IconView(this, IconView.RIGHT, blue, stroke)
        overlay.addView(left, FrameLayout.LayoutParams(dp(56), dp(140), Gravity.START or Gravity.CENTER_VERTICAL))
        makeDrag(left, true, { prefs.getInt("kb_ml", 0) }) { s0, d -> setSide("kb_ml", s0 + d) }
        val right = IconView(this, IconView.LEFT, blue, stroke)
        overlay.addView(right, FrameLayout.LayoutParams(dp(56), dp(140), Gravity.END or Gravity.CENTER_VERTICAL))
        makeDrag(right, true, { prefs.getInt("kb_mr", 0) }) { s0, d -> setSide("kb_mr", s0 - d) }

        frame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            leftMargin = dp(3) + prefs.getInt("kb_ml", 0)
            rightMargin = dp(3) + prefs.getInt("kb_mr", 0)
            bottomMargin = dp(4) + navBottomPx
        })
    }

    private companion object {
        val SUFFIXES = ("lar ler da de dan den ın in un ün a e ı i u ü ya ye yı yi yu yü nın nin nun nün " +
            "la le ım im um üm sı si su sü ta te tan ten dı di du dü tı ti tu tü mı mi mu mü lı li lu lü " +
            "lık lik luk lük cı ci cu cü çı çi çu çü ki nda nde ndan nden na ne ca ce ça çe sız siz suz süz " +
            "m n k ız iz uz üz ları leri lara lere ların lerin ında inde ımız imiz umuz ümüz yor yorum yorsun " +
            "yoruz dır dir dur dür tır tir ma me mak mek an en ar er ır ir ur ür ı").split(" ").distinct()

        val EMOJIS = "😀 😁 😂 🤣 😊 😍 😘 😎 🤔 😢 😭 😡 👍 👎 👏 🙏 💪 ❤️ 💔 🔥 ✨ 🎉 🎂 🌹 " +
            "☀️ 🌙 ⭐ 😴 🤗 😅 😉 🙂 🙄 😬 😱 😇 🥰 🤩 😜 🤪 🥳 😏 😒 😞 😔 😟 😤 🤯 " +
            "😳 🥺 😋 🤤 🤭 🤫 🤐 😷 🤒 🤮 😈 👻 💀 🙈 🙉 🙊 🐶 🐱 🐻 🦁 🐸 🐵 🐔 🦋 " +
            "🌸 🌳 🍎 🍕 🍔 🍟 ☕ 🍺 🍷 ⚽ 🏀 🎮 🎵 🎶 📱 💻 📷 🚗 ✈️ 🏠 💰 🎁 📞 " +
            "✅ ❌ ⚠️ 💯 👋 🤝 ✌️ 👌 🤞 👀 💋 💐 🌈 ☔"

        val DEFAULT_BIGRAMS = "merhaba:nasılsın,hoş,canım;selam:nasılsın,canım;nasılsın:iyiyim,nasıl;" +
            "nereye:gidiyorsun,gidiyoruz,gidiyor;nerede:buluşuyoruz,kaldın;neredesin:ben,şimdi;" +
            "ne:yapıyorsun,zaman,var,yaptın;nasıl:gidiyor,bir,yani;teşekkür:ederim,ederiz;" +
            "sağ:ol;iyi:akşamlar,geceler,günler,misin;günaydın:canım,nasılsın;hoş:geldin,bulduk;" +
            "görüşürüz:canım,kendine;kendine:iyi;çok:güzel,teşekkürler,iyi;bir:şey,saniye,dakika;" +
            "ben:de,şimdi,geliyorum;sen:de,nerdesin;bugün:ne,nasıl,akşam;yarın:görüşürüz,görüşelim,sabah;" +
            "tamam:canım,olur,görüşürüz;lütfen:bana,beni;özür:dilerim;şimdi:geliyorum,müsait,gidiyorum;" +
            "evet:tabii,kesinlikle,ben;hayır:değil,olmaz;aşkım:nasılsın,ne;canım:benim,nasılsın"

        val DEFAULT_WORDS = "bir bu ve da de için ne çok ama daha var gibi ben sen o biz siz onlar evet hayır " +
            "değil mi ki en kadar sonra şimdi nasıl neden nerede nereye nereden kim kime hangi şey her hiç bile " +
            "ile ya ise çünkü yani tamam merhaba selam nasılsın iyiyim teşekkürler teşekkür ederim sağol rica " +
            "lütfen özür dilerim günaydın akşamlar geceler hoş geldin görüşürüz hadi haydi bak bakalım olur " +
            "olmaz olmuş oldu olacak olarak olan gel gelmek geliyorum geliyor geldi gelecek git gitmek " +
            "gidiyorum gidiyorsun gidiyor gidiyoruz gitti gideceğim yap yapmak yapıyorum yapıyorsun yaptım " +
            "yapacağım ver vermek al almak bul bulmak söyle söylemek biliyorum bilmiyorum biliyor istiyorum " +
            "istiyorsun istiyor istemiyorum sevgilim canım aşkım güzel kötü iyi büyük küçük yeni eski bugün " +
            "yarın dün şu an akşam sabah öğle gece hafta ay yıl saat dakika zaman gün ev iş okul araba yol " +
            "para su yemek çay kahve ekmek telefon mesaj arıyorum arıyorsun arayacağım ara görmek görüyorum " +
            "gördüm göreceğim bekle bekliyorum bekliyor geliyorsun nerdesin neredesin yapıyorsun napıyorsun " +
            "kaç kaçta nerde burada orada şurada buraya oraya bana sana ona bize size onlara benim senin onun " +
            "bizim sizin onların beni seni onu bizi sizi onları benimle seninle onunla bizimle sizinle " +
            "müsait müsaitsin buluşalım buluşuruz buluşuyoruz kaldın kaldım yoldayım yoldayız geç geç kaldım " +
            "erken geç acil önemli sorun yok var mı tabii tabi kesinlikle belki galiba sanırım herhalde " +
            "aslında özellikle sadece bile hem hem de veya ya da fakat ancak üzgünüm mutlu mutluyum yorgunum " +
            "acıktım susadım uyuyorum uyudum uyanık hasta iyileş geçmiş olsun doğum günü kutlu olsun " +
            "bayram tebrik ederim kolay zor güzel güzeldi harika süper mükemmel berbat fena değil " +
            "anne baba kardeş abi abla amca teyze dayı hala arkadaş dost eş çocuk bebek aile " +
            "toplantı ders sınav ödev proje rapor dosya belge mail eposta mesajı gönder gönderdim " +
            "göndereceğim aldım aldık verdim verdik yaptık yapalım gidelim gelelim bakalım konuşalım " +
            "konuşuruz konuşuyoruz anlıyorum anladım anlamadım tekrar bir daha lütfen tekrar söyle " +
            "doğru yanlış evet tamamdır peki olur mu olmuyor oluyor olabilir olmaz mı hayırlı olsun " +
            "inşallah maşallah selamün aleyküm aleyküm selam allah'a emanet kendine iyi bak"
    }
}

// Ok ve boşluk simgeleri (yazı karakteri yerine çizilir, her telefonda aynı görünür)
private class IconView(ctx: Context, private var kind: Int, color: Int, strokePx: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    // Simgenin türünü ve rengini değiştirir (ör. ⇧ tuşu: kapalı / açık / sabit)
    fun setLook(newKind: Int, newColor: Int) {
        kind = newKind
        paint.color = newColor
        fillPaint.color = newColor
        invalidate()
    }

    // Mikrofon: kapsülün içi dolu
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float) {
        val m = minOf(width, height).toFloat()
        val capsule = RectF(cx - 0.17f * m, cy - 0.42f * m, cx + 0.17f * m, cy + 0.06f * m)
        canvas.drawRoundRect(capsule, 0.17f * m, 0.17f * m, fillPaint)
        canvas.drawRoundRect(capsule, 0.17f * m, 0.17f * m, paint)
        canvas.drawArc(RectF(cx - 0.30f * m, cy - 0.24f * m, cx + 0.30f * m, cy + 0.26f * m), 20f, 140f, false, paint)
        canvas.drawLine(cx, cy + 0.26f * m, cx, cy + 0.42f * m, paint)
        canvas.drawLine(cx - 0.12f * m, cy + 0.42f * m, cx + 0.12f * m, cy + 0.42f * m, paint)
    }

    // Büyük harf oku: kalın çizgili (kapalı), dolu (açık) ya da dolu + altında çizgi (sabit büyük harf)
    private fun drawShift(canvas: Canvas, cx: Float, cy: Float, filled: Boolean, caps: Boolean) {
        val m = minOf(width, height).toFloat() * 0.6f
        val dy = if (caps) -0.06f * m else 0f
        val p = Path().apply {
            moveTo(cx, cy - 0.34f * m + dy)
            lineTo(cx - 0.30f * m, cy + dy)
            lineTo(cx - 0.13f * m, cy + dy)
            lineTo(cx - 0.13f * m, cy + 0.26f * m + dy)
            lineTo(cx + 0.13f * m, cy + 0.26f * m + dy)
            lineTo(cx + 0.13f * m, cy + dy)
            lineTo(cx + 0.30f * m, cy + dy)
            close()
        }
        if (filled) canvas.drawPath(p, fillPaint)
        canvas.drawPath(p, paint)
        if (caps) {
            canvas.drawRoundRect(
                RectF(cx - 0.13f * m, cy + 0.29f * m, cx + 0.13f * m, cy + 0.37f * m),
                0.03f * m, 0.03f * m, fillPaint
            )
        }
    }

    // Alt satıra geç oku (↵): kalın ve büyük
    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val m = minOf(width, height).toFloat() * 0.6f
        val oldWidth = paint.strokeWidth
        paint.strokeWidth = oldWidth * 1.5f
        val line = Path().apply {
            moveTo(cx + 0.26f * m, cy - 0.22f * m)
            lineTo(cx + 0.26f * m, cy + 0.06f * m)
            lineTo(cx - 0.24f * m, cy + 0.06f * m)
        }
        canvas.drawPath(line, paint)
        val head = Path().apply {
            moveTo(cx - 0.06f * m, cy - 0.16f * m)
            lineTo(cx - 0.26f * m, cy + 0.06f * m)
            lineTo(cx - 0.06f * m, cy + 0.28f * m)
        }
        canvas.drawPath(head, paint)
        paint.strokeWidth = oldWidth
    }

    private fun drawCursor(canvas: Canvas, cx: Float, cy: Float) {
        val m = minOf(width, height).toFloat() * 0.7f
        canvas.drawLine(cx, cy - 0.32f * m, cx, cy + 0.32f * m, paint)
        canvas.drawLine(cx - 0.12f * m, cy - 0.32f * m, cx + 0.12f * m, cy - 0.32f * m, paint)
        canvas.drawLine(cx - 0.12f * m, cy + 0.32f * m, cx + 0.12f * m, cy + 0.32f * m, paint)

        val leftArrow = Path().apply {
            moveTo(cx - 0.28f * m, cy - 0.15f * m)
            lineTo(cx - 0.15f * m, cy)
            lineTo(cx - 0.28f * m, cy + 0.15f * m)
            close()
        }
        canvas.drawPath(leftArrow, fillPaint)

        val rightArrow = Path().apply {
            moveTo(cx + 0.28f * m, cy - 0.15f * m)
            lineTo(cx + 0.15f * m, cy)
            lineTo(cx + 0.28f * m, cy + 0.15f * m)
            close()
        }
        canvas.drawPath(rightArrow, fillPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (kind) {
            MIC -> { drawMic(canvas, cx, cy); return }
            SHIFT_OFF -> { drawShift(canvas, cx, cy, false, false); return }
            SHIFT_ON -> { drawShift(canvas, cx, cy, true, false); return }
            SHIFT_CAPS -> { drawShift(canvas, cx, cy, true, true); return }
            ENTER -> { drawEnter(canvas, cx, cy); return }
            CURSOR -> { drawCursor(canvas, cx, cy); return }
        }
        val r = minOf(width, height) * 0.16f
        val p = Path()
        when (kind) {
            UP -> { p.moveTo(cx - 1.3f * r, cy + 0.6f * r); p.lineTo(cx, cy - 0.7f * r); p.lineTo(cx + 1.3f * r, cy + 0.6f * r) }
            DOWN -> { p.moveTo(cx - 1.3f * r, cy - 0.6f * r); p.lineTo(cx, cy + 0.7f * r); p.lineTo(cx + 1.3f * r, cy - 0.6f * r) }
            LEFT -> { p.moveTo(cx + 0.6f * r, cy - 1.3f * r); p.lineTo(cx - 0.7f * r, cy); p.lineTo(cx + 0.6f * r, cy + 1.3f * r) }
            RIGHT -> { p.moveTo(cx - 0.6f * r, cy - 1.3f * r); p.lineTo(cx + 0.7f * r, cy); p.lineTo(cx - 0.6f * r, cy + 1.3f * r) }
            else -> {
                p.moveTo(cx - 1.4f * r, cy - 0.3f * r); p.lineTo(cx - 1.4f * r, cy + 0.5f * r)
                p.lineTo(cx + 1.4f * r, cy + 0.5f * r); p.lineTo(cx + 1.4f * r, cy - 0.3f * r)
            }
        }
        canvas.drawPath(p, paint)
    }

    companion object {
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
        const val SPACE = 4
        const val MIC = 5
        const val SHIFT_OFF = 6
        const val SHIFT_ON = 7
        const val SHIFT_CAPS = 8
        const val ENTER = 9
        const val CURSOR = 10
    }
}

// Yeniden boyutlandırmadaki ortadaki daire: yukarı/aşağı oklu, yarı saydam
private class JoystickView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#99FFFFFF")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 6f
    }
    private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4f
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        val s = r * 0.32f
        val up = Path().apply {
            moveTo(cx, cy - r * 0.62f); lineTo(cx - s, cy - r * 0.2f); lineTo(cx + s, cy - r * 0.2f); close()
        }
        val down = Path().apply {
            moveTo(cx, cy + r * 0.62f); lineTo(cx - s, cy + r * 0.2f); lineTo(cx + s, cy + r * 0.2f); close()
        }
        canvas.drawPath(up, tri)
        canvas.drawPath(down, tri)
    }
}