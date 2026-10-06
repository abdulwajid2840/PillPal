package com.jaby.pillpal

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

/* ---------------- Core: storage, scheduling, notifications, Gemini ---------------- */
object Core {
    fun sp(c: Context) = c.getSharedPreferences("pp", 0)
    fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }
    fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }

    fun meds(c: Context): List<JSONObject> {
        val a = JSONArray(sp(c).getString("meds", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }
    fun saveMeds(c: Context, l: List<JSONObject>) {
        val a = JSONArray(); l.forEach { a.put(it) }
        sp(c).edit().putString("meds", a.toString()).apply()
    }
    fun med(c: Context, id: String) = meds(c).firstOrNull { it.getString("id") == id }

    fun upsert(c: Context, m: JSONObject) {
        val l = meds(c).toMutableList()
        val i = l.indexOfFirst { it.getString("id") == m.getString("id") }
        if (i >= 0) { cancelAll(c, l[i]); l[i] = m } else l.add(m)
        saveMeds(c, l); scheduleAll(c)
    }
    fun delete(c: Context, id: String) {
        val l = meds(c).toMutableList()
        l.firstOrNull { it.getString("id") == id }?.let { cancelAll(c, it) }
        saveMeds(c, l.filter { it.getString("id") != id })
    }

    fun ymd(c: Calendar) = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    fun key(id: String, t: String) = ymd(Calendar.getInstance()) + "|" + id + "|" + t

    fun getLog(c: Context, k: String): String? = JSONObject(sp(c).getString("log", "{}")).optString(k, "").ifEmpty { null }
    fun mark(c: Context, id: String, t: String, st: String) {
        val lg = JSONObject(sp(c).getString("log", "{}"))
        val k = key(id, t)
        val prev = lg.optString(k, "")
        lg.put(k, st); sp(c).edit().putString("log", lg.toString()).apply()
        val m = med(c, id) ?: return
        val stock = m.optInt("stock", -1)
        if (stock >= 0) {
            val per = m.optInt("per", 1)
            val ns = if (st == "taken" && prev != "taken") maxOf(0, stock - per)
                else if (st != "taken" && prev == "taken") stock + per else stock
            if (ns != stock) {
                m.put("stock", ns)
                saveMeds(c, meds(c).map { if (it.getString("id") == id) m else it })
            }
        }
    }

    fun activeOn(m: JSONObject, cal: Calendar): Boolean {
        val end = m.optString("end", "")
        return cal.get(Calendar.DAY_OF_WEEK) in ints(m.getJSONArray("days")) && (end == "" || ymd(cal) <= end)
    }
    fun dosesToday(c: Context): List<Pair<JSONObject, String>> {
        val now = Calendar.getInstance()
        return meds(c).filter { activeOn(it, now) }
            .flatMap { m -> strs(m.getJSONArray("times")).map { m to it } }.sortedBy { it.second }
    }

    // ---- alarms ----
    private fun pi(c: Context, id: String, t: String) = PendingIntent.getBroadcast(
        c, 0,
        Intent(c, AlarmReceiver::class.java).setData(Uri.parse("pp://alarm/$id/$t")).putExtra("id", id).putExtra("t", t),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    fun next(m: JSONObject, t: String): Long? {
        val (h, mi) = t.split(":").map { it.toInt() }
        for (d in 0..8) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > System.currentTimeMillis() + 1000 && activeOn(m, c)) return c.timeInMillis
        }
        return null
    }
    fun scheduleOne(c: Context, m: JSONObject, t: String) {
        val at = next(m, t) ?: return
        val am = c.getSystemService(AlarmManager::class.java)
        val p = pi(c, m.getString("id"), t)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    }
    fun scheduleAll(c: Context) = meds(c).forEach { m -> strs(m.getJSONArray("times")).forEach { scheduleOne(c, m, it) } }
    fun cancelAll(c: Context, m: JSONObject) {
        val am = c.getSystemService(AlarmManager::class.java)
        strs(m.getJSONArray("times")).forEach { am.cancel(pi(c, m.getString("id"), it)) }
    }

    // ---- notifications ----
    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("meds", "Medicine reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }
    fun notify(c: Context, m: JSONObject, t: String) {
        channel(c)
        val id = m.getString("id"); val nid = (id + t).hashCode()
        fun act(st: String, label: String): Notification.Action {
            val i = Intent(c, ActionReceiver::class.java).setData(Uri.parse("pp://act/$id/$t/$st"))
                .putExtra("id", id).putExtra("t", t).putExtra("st", st).putExtra("n", nid)
            val p = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, p).build()
        }
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stock = m.optInt("stock", -1)
        val low = if (stock in 0..5) " · only $stock left" else ""
        val n = Notification.Builder(c, "meds")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time for " + m.getString("name"))
            .setContentText((m.optString("dose") + " " + m.optString("notes") + low).trim())
            .setAutoCancel(true).setContentIntent(open)
            .addAction(act("taken", "Taken")).addAction(act("skipped", "Skip"))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(nid, n)
    }

    // ---- backup ----
    fun exportJson(c: Context): String = JSONObject().put("app", "PillPal").put("version", 1)
        .put("meds", JSONArray(sp(c).getString("meds", "[]")))
        .put("log", JSONObject(sp(c).getString("log", "{}"))).toString(2)
    fun importJson(c: Context, txt: String): Int {
        val o = JSONObject(txt)
        val a = o.getJSONArray("meds")
        for (i in 0 until a.length()) {
            val m = a.getJSONObject(i)
            m.getString("id"); m.getString("name"); m.getJSONArray("times"); m.getJSONArray("days")
        }
        meds(c).forEach { cancelAll(c, it) }
        sp(c).edit().putString("meds", a.toString()).putString("log", (o.optJSONObject("log") ?: JSONObject()).toString()).apply()
        scheduleAll(c)
        return a.length()
    }

    // ---- Gemini ----
    fun ask(key: String, model: String, q: String): List<JSONObject> {
        val prompt = """Extract medicine schedules from the text. Return ONLY a JSON array of objects:
{"name":string,"dose":string,"unitsPerDose":number,"times":["HH:MM" 24h],"days":[1-7, Sunday=1 ... Saturday=7; all seven if daily],"durationDays":number or null,"stock":number or null,"notes":string}
Defaults: once daily 08:00; twice daily 08:00,20:00; thrice daily 08:00,14:00,20:00; morning 08:00; afternoon 14:00; evening 18:00; night 21:00; after breakfast 09:00; after lunch 14:00; after dinner 21:00.
Never invent medicines or doses that are not in the text.
Text: $q"""
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        var txt = ""
        var lastErr = "Unknown error"
        val models = listOf(model, "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-flash-latest").distinct()
        loop@ for (md in models) for (attempt in 1..2) {
            val cn = URL("https://generativelanguage.googleapis.com/v1beta/models/$md:generateContent").openConnection() as HttpURLConnection
            cn.requestMethod = "POST"
            cn.setRequestProperty("Content-Type", "application/json")
            cn.setRequestProperty("x-goog-api-key", key)
            cn.doOutput = true
            cn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = cn.responseCode
            val r = (if (code in 200..299) cn.inputStream else cn.errorStream).bufferedReader().readText()
            if (code in 200..299) { txt = r; break@loop }
            lastErr = runCatching { JSONObject(r).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")
            if (code !in listOf(404, 429, 500, 503, 504)) throw Exception(lastErr)
            Thread.sleep(1500)
        }
        if (txt.isEmpty()) throw Exception(lastErr)
        val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text").trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = if (out.startsWith("[")) JSONArray(out) else JSONArray().put(JSONObject(out))
        return List(arr.length()) { i ->
            val a = arr.getJSONObject(i)
            val end = a.optInt("durationDays", 0).let { d ->
                if (d > 0) ymd(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, d - 1) }) else ""
            }
            JSONObject()
                .put("id", "m" + System.currentTimeMillis() + i)
                .put("name", a.optString("name", "Medicine"))
                .put("dose", a.optString("dose", ""))
                .put("per", a.optInt("unitsPerDose", 1).coerceAtLeast(1))
                .put("times", a.optJSONArray("times") ?: JSONArray().put("08:00"))
                .put("days", a.optJSONArray("days")?.takeIf { it.length() > 0 } ?: JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                .put("end", end)
                .put("stock", if (a.isNull("stock")) -1 else a.optInt("stock", -1))
                .put("notes", a.optString("notes", ""))
        }
    }
}

/* ---------------- Receivers ---------------- */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        val t = i.getStringExtra("t") ?: return
        val m = Core.med(c, id) ?: return
        if (t !in Core.strs(m.getJSONArray("times"))) return
        Core.notify(c, m, t)
        Core.scheduleOne(c, m, t)
    }
}
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Core.mark(c, i.getStringExtra("id") ?: return, i.getStringExtra("t") ?: return, i.getStringExtra("st") ?: return)
        c.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("n", 0))
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Core.scheduleAll(c)
}

/* ---------------- UI ---------------- */
class Pal(
    val dark: Boolean, val bg: Int, val card: Int, val ink: Int, val mut: Int, val gold: Int,
    val ok: Int, val bad: Int, val time: Int, val line: Int, val hi: Int, val onAcc: Int
)

class MainActivity : Activity() {
    private lateinit var box: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var p: Pal
    private var tab = 0
    private var ai: List<JSONObject> = emptyList()
    private var aiMsg = ""
    private var aiText = ""
    private var editing: JSONObject? = null

    // ---------- theme ----------
    private fun c(s: String) = Color.parseColor(s)
    private fun isDark(): Boolean = when (Core.sp(this).getString("theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private fun pal(dark: Boolean) = if (dark)
        Pal(true, c("#1B1712"), c("#2A241C"), c("#EFE4CC"), c("#A99A80"), c("#D8AE62"), c("#8DB27C"), c("#E08A7B"), c("#8FAAD0"), c("#3E352A"), c("#3A2F1F"), c("#1B1712"))
    else
        Pal(false, c("#F2E8D0"), c("#FBF6E9"), c("#3A2E26"), c("#7C6C57"), c("#9A6B2F"), c("#5B7A4A"), c("#A8473C"), c("#3E5C86"), c("#DCCBA6"), c("#F6E7BF"), c("#FFF8E8"))
    private val medLight = listOf("#B5483A", "#3D5A80", "#4F7A5A", "#C98B2B", "#8C4A6B", "#7A5C3E")
    private val medDark = listOf("#E07A68", "#86A8D4", "#80B68E", "#E6B456", "#CC8FB0", "#BE9A78")
    private fun medColor(id: String) = c((if (p.dark) medDark else medLight)[(id.hashCode() and 0x7fffffff) % 6])

    // ---------- view helpers ----------
    private fun dp(x: Number) = (x.toFloat() * resources.displayMetrics.density).toInt()
    private fun withA(col: Int, a: Int) = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
    private fun bg(color: Int, r: Float, stroke: Int = 0, sw: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (sw > 0) setStroke(dp(sw), stroke)
    }
    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun tx(s: String, size: Float = 15f, color: Int = p.ink, bold: Boolean = false, serif: Boolean = false, italic: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = Typeface.create(if (serif) "serif" else "sans-serif", (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
            setPadding(0, dp(2), 0, dp(2))
        }
    private fun chip(s: String, col: Int) = tx(s, 12f, col, bold = true).apply {
        background = bg(withA(col, 0x2A), 12f); setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    private fun btn(label: String, fill: Int, textCol: Int, outline: Boolean = false, f: () -> Unit) = Button(this).apply {
        text = label; setAllCaps(false); textSize = 15f; setTextColor(textCol)
        typeface = Typeface.create("serif", Typeface.BOLD)
        minHeight = 0; minimumHeight = dp(44); minWidth = 0; minimumWidth = dp(64)
        stateListAnimator = null; elevation = 0f
        val shape = if (outline) bg(Color.TRANSPARENT, 22f, fill, 1) else bg(fill, 22f)
        background = RippleDrawable(ColorStateList.valueOf(withA(fill, 0x33)), shape, null)
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { f() }
    }
    private fun field(h: String, v: String = "", lines: Int = 1) = EditText(this).apply {
        hint = h; setText(v); setTextColor(p.ink); setHintTextColor(p.mut); textSize = 16f
        background = bg(p.bg, 12f, p.line, 1); setPadding(dp(12), dp(10), dp(12), dp(10)); minLines = lines
        if (lines > 1) gravity = Gravity.TOP
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun addCard(accent: Int, highlight: Boolean = false, build: LinearLayout.() -> Unit) {
        val outer = LinearLayout(this).apply {
            background = bg(if (highlight) p.hi else p.card, 16f, if (highlight) p.gold else p.line, if (highlight) 2 else 1)
            elevation = dp(if (highlight) 6 else 2).toFloat()
            clipToOutline = true
        }
        outer.addView(View(this).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(dp(6), -1))
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        inner.build()
        outer.addView(inner, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(outer, lp(-1, -2, 2, 7, 2, 7))
    }
    private fun ornament(): LinearLayout {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(View(this).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(0, dp(1), 1f))
        r.addView(tx("❖", 14f, p.gold).apply { setPadding(dp(10), 0, dp(10), 0) })
        r.addView(View(this).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(0, dp(1), 1f))
        return r
    }
    private fun heading(title: String, sub: String) {
        box.addView(tx(title, 32f, p.ink, bold = true, serif = true))
        box.addView(tx(sub, 14f, p.mut, italic = true, serif = true))
        box.addView(ornament(), lp(-1, -2, 0, 6, 0, 8))
    }
    private fun describe(m: JSONObject): String {
        val dn = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val d = Core.ints(m.getJSONArray("days"))
        return Core.strs(m.getJSONArray("times")).joinToString(", ") + " · " +
            (if (d.size == 7) "every day" else d.joinToString(" ") { dn[it - 1] })
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tab = b?.getInt("tab") ?: 0
        p = pal(isDark())
        Core.channel(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.card
        lightBars(!p.dark)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); fitsSystemWindows = true }
        scroll = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(24)) }
        scroll.addView(box)
        nav = LinearLayout(this).apply { setBackgroundColor(p.card); elevation = dp(8).toFloat(); setPadding(dp(8), dp(6), dp(8), dp(6)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        Core.scheduleAll(this)
        show()
    }
    @Suppress("DEPRECATION")
    private fun lightBars(light: Boolean) {
        var f = 0
        if (light) f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("tab", tab) }

    private fun show() {
        box.removeAllViews()
        when (tab) { 0 -> today(); 1 -> if (editing != null) form(editing!!) else medsList(); 2 -> aiTab(); else -> settings() }
        nav.removeAllViews()
        listOf("Today", "Remedies", "Ask AI", "Settings").forEachIndexed { i, n ->
            val on = tab == i
            val t = tx(n, 14f, if (on) p.gold else p.mut, bold = on, serif = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
  
