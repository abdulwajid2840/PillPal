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
    private fun isDark(): Boolean {
        val choice = Core.sp(this).getString("theme", "system")
        if (choice == "dark") {
            return true
        }
        if (choice == "light") {
            return false
        }
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
    private fun darkPal(): Pal {
        return Pal(true, c("#1B1712"), c("#2A241C"), c("#EFE4CC"), c("#A99A80"), c("#D8AE62"), c("#8DB27C"), c("#E08A7B"), c("#8FAAD0"), c("#3E352A"), c("#3A2F1F"), c("#1B1712"))
    }
    private fun lightPal(): Pal {
        return Pal(false, c("#F2E8D0"), c("#FBF6E9"), c("#3A2E26"), c("#7C6C57"), c("#9A6B2F"), c("#5B7A4A"), c("#A8473C"), c("#3E5C86"), c("#DCCBA6"), c("#F6E7BF"), c("#FFF8E8"))
    }
    private fun pal(dark: Boolean): Pal {
        if (dark) {
            return darkPal()
        }
        return lightPal()
    }
    private val medLight = listOf("#B5483A", "#3D5A80", "#4F7A5A", "#C98B2B", "#8C4A6B", "#7A5C3E")
    private val medDark = listOf("#E07A68", "#86A8D4", "#80B68E", "#E6B456", "#CC8FB0", "#BE9A78")
    private fun medColor(id: String) = c((if (p.dark) medDark else medLight)[(id.hashCode() and 0x7fffffff) % 6])

    // ---------- view helpers ----------
    private fun dp(x: Number) = (x.toFloat() * resources.displayMetrics.density).toInt()
    private fun withA(col: Int, a: Int) = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
    private fun bg(color: Int, r: Float, stroke: Int = 0, sw: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (sw > 0) {
            setStroke(dp(sw), stroke)
        }
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
        if (lines > 1) {
            gravity = Gravity.TOP
        }
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
        if (light) {
            f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = f
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    private fun show() {
        box.removeAllViews()
        renderTab()
        buildNav()
    }

    private fun renderTab() {
        val e = editing
        if (tab == 0) {
            today()
        } else if (tab == 1) {
            if (e != null) {
                form(e)
            } else {
                medsList()
            }
        } else if (tab == 2) {
            aiTab()
        } else {
            settings()
        }
    }

    private fun buildNav() {
        nav.removeAllViews()
        val names = listOf("Today", "Remedies", "Ask AI", "Settings")
        for (i in names.indices) {
            val on = (tab == i)
            val tc = if (on) p.gold else p.mut
            val t = tx(names[i], 14f, tc, bold = on, serif = true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            if (on) {
                t.background = bg(p.hi, 20f, p.gold, 1)
            }
            t.setOnClickListener {
                tab = i
                editing = null
                scroll.scrollTo(0, 0)
                show()
            }
            val params = lp(0, -2, 4, 0, 4, 0)
            params.weight = 1f
            nav.addView(t, params)
        }
    }

    // ---------- Today ----------
    private fun today() {
        val now = Calendar.getInstance()
        heading("Today", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.time))
        val ds = Core.dosesToday(this)
        val stat = ds.map { Core.getLog(this, Core.key(it.first.getString("id"), it.second)) }
        val done = stat.count { it != null }
        if (ds.isEmpty()) {
            addCard(p.gold) {
                addView(tx("A quiet day", 18f, p.ink, bold = true, serif = true))
                addView(tx("Nothing is scheduled. Add a remedy under Remedies or Ask AI.", 14f, p.mut))
            }
        } else {
            addCard(p.gold) {
                addView(tx("$done of ${ds.size} doses marked", 15f, p.mut, italic = true, serif = true))
                val bar = LinearLayout(context).apply { background = bg(p.line, 6f); clipToOutline = true }
                bar.addView(View(context).apply { setBackgroundColor(p.ok) }, LinearLayout.LayoutParams(0, -1, done.toFloat()))
                bar.addView(View(context), LinearLayout.LayoutParams(0, -1, (ds.size - done).toFloat()))
                addView(bar, lp(-1, dp(8), 0, 8, 0, 2))
            }
        }
        Core.meds(this).filter { it.optInt("stock", -1) in 0..5 }.forEach { m ->
            addCard(p.bad) {
                addView(tx("Refill soon", 12f, p.bad, bold = true))
                addView(tx("${m.getString("name")} · ${m.getInt("stock")} left", 16f, p.ink, serif = true))
            }
        }
        val nowS = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val nextIdx = ds.indices.firstOrNull { stat[it] == null && ds[it].second >= nowS }
            ?: ds.indices.firstOrNull { stat[it] == null } ?: -1
        ds.forEachIndexed { i, (m, t) ->
            val id = m.getString("id"); val st = stat[i]; val col = medColor(id); val isNext = i == nextIdx
            addCard(col, isNext) {
                val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(tx(t, 24f, p.time, bold = true, serif = true))
                top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                if (st == "taken") top.addView(chip("✓ Taken", p.ok))
                else if (st == "skipped") top.addView(chip("Skipped", p.bad))
                else if (isNext) top.addView(chip("Next dose", p.gold))
                addView(top)
                addView(tx(m.getString("name"), 20f, p.ink, bold = true, serif = true))
                if (m.optString("dose") != "") addView(tx(m.optString("dose"), 15f, col, bold = true))
                if (m.optString("notes") != "") addView(tx(m.optString("notes"), 14f, p.mut, italic = true, serif = true))
                val chips = LinearLayout(context)
                chips.addView(chip("Take ${m.optInt("per", 1)}", p.gold), lp(-2, -2, 0, 6, 6, 0))
                val sk = m.optInt("stock", -1)
                if (sk >= 0) chips.addView(chip("Stock $sk", if (sk <= 5) p.bad else p.ok), lp(-2, -2, 0, 6, 0, 0))
                addView(chips)
                val r = LinearLayout(context)
                if (st == null) {
                    r.addView(btn("Taken", p.ok, p.onAcc) { Core.mark(this@MainActivity, id, t, "taken"); show() }, lp(-2, -2, 0, 10, 8, 0))
                    r.addView(btn("Skip", p.bad, p.bad, true) { Core.mark(this@MainActivity, id, t, "skipped"); show() }, lp(-2, -2, 0, 10, 0, 0))
                } else {
                    r.addView(btn("Undo", p.mut, p.mut, true) { Core.mark(this@MainActivity, id, t, ""); show() }, lp(-2, -2, 0, 10, 0, 0))
                }
                addView(r)
            }
        }
    }

    // ---------- Remedies ----------
    private fun medsList() {
        heading("Remedies", "Your medicine shelf")
        box.addView(btn("+ Add remedy", p.gold, p.onAcc) {
            editing = JSONObject().put("id", "m" + System.currentTimeMillis()).put("name", "").put("dose", "").put("per", 1)
                .put("times", JSONArray().put("08:00")).put("days", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                .put("end", "").put("stock", -1).put("notes", "").put("isNew", true)
            scroll.scrollTo(0, 0); show()
        }, lp(-2, -2, 0, 4, 0, 8))
        val l = Core.meds(this)
        if (l.isEmpty()) addCard(p.gold) { addView(tx("The shelf is empty. Add a remedy by hand or with Ask AI.", 14f, p.mut)) }
        l.forEach { m ->
            val col = medColor(m.getString("id"))
            addCard(col) {
                addView(tx(m.getString("name"), 20f, p.ink, bold = true, serif = true))
                if (m.optString("dose") != "") addView(tx(m.optString("dose"), 15f, col, bold = true))
                addView(tx(describe(m), 14f, p.time))
                if (m.optString("end") != "") addView(tx("Until " + m.optString("end"), 13f, p.mut, italic = true, serif = true))
                if (m.optString("notes") != "") addView(tx(m.optString("notes"), 14f, p.mut, italic = true, serif = true))
                val sk = m.optInt("stock", -1)
                if (sk >= 0) addView(chip("Stock $sk", if (sk <= 5) p.bad else p.ok), lp(-2, -2, 0, 6, 0, 0))
                addView(btn("Edit", p.gold, p.gold, true) { editing = m; scroll.scrollTo(0, 0); show() }, lp(-2, -2, 0, 10, 0, 0))
            }
        }
    }

    private fun form(m: JSONObject) {
        val isNew = m.optBoolean("isNew")
        heading(if (isNew) "New remedy" else "Edit remedy", "Fill in the details")
        val name = field("Name", m.getString("name")); val dose = field("Dose (e.g. 500 mg)", m.optString("dose"))
        val per = field("Units per dose", m.optInt("per", 1).toString())
        val times = field("Times, comma separated (08:00,20:00)", Core.strs(m.getJSONArray("times")).joinToString(","))
        val end = field("Last day yyyy-mm-dd (optional)", m.optString("end"))
        val stock = field("Stock in units (optional)", if (m.optInt("stock", -1) >= 0) m.getInt("stock").toString() else "")
        val notes = field("Notes (e.g. after food)", m.optString("notes"))
        val dn = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val cur = Core.ints(m.getJSONArray("days"))
        val cbs = dn.mapIndexed { i, n ->
            CheckBox(this).apply { text = n; isChecked = (i + 1) in cur; textSize = 10f; setTextColor(p.ink); buttonTintList = ColorStateList.valueOf(p.gold) }
        }
        val dayRow = LinearLayout(this)
        cbs.forEach { dayRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        addCard(p.gold) {
            for (v in listOf<View>(name, dose, per, times, end, stock, notes)) addView(v, lp(-1, -2, 0, 6, 0, 0))
            addView(tx("Days", 13f, p.mut, italic = true, serif = true), lp(-2, -2, 0, 10, 0, 0))
            addView(dayRow)
            val r = LinearLayout(context)
            r.addView(btn("Save", p.gold, p.onAcc) {
                val ts = times.text.toString().split(",").map { it.trim() }.filter { Regex("\\d{1,2}:\\d{2}").matches(it) }
                    .map { it.padStart(5, '0') }.sorted()
                val ds = cbs.indices.filter { cbs[it].isChecked }.map { it + 1 }
                if (name.text.isBlank() || ts.isEmpty() || ds.isEmpty()) {
                    toast("Need a name, a time like 08:00, and at least one day")
                } else {
                    val o = JSONObject().put("id", m.getString("id")).put("name", name.text.toString().trim())
                        .put("dose", dose.text.toString()).put("per", per.text.toString().toIntOrNull() ?: 1)
                        .put("times", JSONArray(ts)).put("days", JSONArray(ds)).put("end", end.text.toString().trim())
                        .put("stock", stock.text.toString().toIntOrNull() ?: -1).put("notes", notes.text.toString())
                    Core.upsert(this@MainActivity, o); editing = null; show()
                }
            }, lp(-2, -2, 0, 12, 8, 0))
            r.addView(btn("Cancel", p.mut, p.mut, true) { editing = null; show() }, lp(-2, -2, 0, 12, 0, 0))
            addView(r)
            if (!isNew) addView(btn("Delete remedy", p.bad, p.bad, true) {
                Core.delete(this@MainActivity, m.getString("id")); editing = null; show()
            }, lp(-2, -2, 0, 10, 0, 0))
        }
    }

    // ---------- Ask AI ----------
    private fun aiTab() {
        heading("Ask AI", "Describe it, and the scribe fills the schedule")
        val q = field("e.g. Metformin 500mg after breakfast and dinner for 30 days, 60 tablets", aiText, 4)
        addCard(p.gold) {
            addView(tx("You can also paste text from a prescription.", 14f, p.mut, italic = true, serif = true))
            addView(q, lp(-1, -2, 0, 8, 0, 0))
            addView(btn("Create with AI", p.gold, p.onAcc) { runAI(q.text.toString()) }, lp(-2, -2, 0, 10, 0, 0))
            if (aiMsg != "") addView(tx(aiMsg, 14f, p.time, italic = true, serif = true), lp(-2, -2, 0, 8, 0, 0))
        }
        ai.forEach { m ->
            val col = medColor(m.getString("id"))
            addCard(col) {
                addView(tx(m.getString("name"), 18f, p.ink, bold = true, serif = true))
                if (m.optString("dose") != "") addView(tx(m.optString("dose"), 15f, col, bold = true))
                addView(tx(describe(m), 14f, p.time))
                if (m.optString("end") != "") addView(tx("Until " + m.optString("end"), 13f, p.mut, italic = true, serif = true))
                if (m.optInt("stock", -1) >= 0) addView(tx("Stock " + m.getInt("stock"), 13f, p.ok))
                if (m.optString("notes") != "") addView(tx(m.optString("notes"), 14f, p.mut, italic = true, serif = true))
            }
        }
        if (ai.isNotEmpty()) {
            val r = LinearLayout(this)
            r.addView(btn("Save all ${ai.size}", p.ok, p.onAcc) {
                ai.forEach { Core.upsert(this, it) }; ai = emptyList(); aiMsg = "Saved."; aiText = ""; tab = 0; show()
            }, lp(-2, -2, 0, 6, 8, 0))
            r.addView(btn("Discard", p.mut, p.mut, true) { ai = emptyList(); aiMsg = ""; show() }, lp(-2, -2, 0, 6, 0, 0))
            box.addView(r)
        }
    }

    private fun runAI(q: String) {
        aiText = q
        val key = Core.sp(this).getString("key", "") ?: ""
        val model = Core.sp(this).getString("model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
        if (key.isEmpty()) { aiMsg = "Add your Gemini API key in Settings first."; show(); return }
        if (q.isBlank()) return
        aiMsg = "Consulting the scribe..."; show()
        thread {
            try {
                val r = Core.ask(key, model, q)
                runOnUiThread { ai = r; aiMsg = if (r.isEmpty()) "No medicines found." else "Check the result, then save."; show() }
            } catch (e: Exception) {
                runOnUiThread { ai = emptyList(); aiMsg = "Error: " + e.message; show() }
            }
        }
    }

    // ---------- Settings ----------
    private fun settings() {
        val sp = Core.sp(this)
        heading("Settings", "Make the app your own")
        val cur = sp.getString("theme", "system") ?: "system"
        addCard(p.gold) {
            addView(tx("Appearance", 18f, p.ink, bold = true, serif = true))
            val r = LinearLayout(context)
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (k, n) ->
                r.addView(
                    if (cur == k) btn(n, p.gold, p.onAcc) {} else btn(n, p.mut, p.mut, true) {
                        sp.edit().putString("theme", k).apply(); recreate()
                    }, lp(-2, -2, 0, 8, 8, 0)
                )
            }
            addView(r)
        }
        val key = field("AIza...", sp.getString("key", "") ?: "")
        val model = field("Model", sp.getString("model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite")
        addCard(p.time) {
            addView(tx("Gemini API", 18f, p.ink, bold = true, serif = true))
            addView(tx("Key from aistudio.google.com", 13f, p.mut, italic = true, serif = true))
            addView(key, lp(-1, -2, 0, 6, 0, 0)); addView(model, lp(-1, -2, 0, 6, 0, 0))
            addView(btn("Save", p.gold, p.onAcc) {
                sp.edit().putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifEmpty { "gemini-3.5-flash-lite" }).apply()
                toast("Saved")
            }, lp(-2, -2, 0, 10, 0, 0))
        }
        addCard(p.ok) {
            addView(tx("Reminders", 18f, p.ink, bold = true, serif = true))
            if (Build.VERSION.SDK_INT in 31..32) addView(btn("Allow exact alarms", p.gold, p.gold, true) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }, lp(-2, -2, 0, 8, 0, 0))
            addView(btn("Test notification", p.gold, p.gold, true) {
                Core.meds(this@MainActivity).firstOrNull()?.let { Core.notify(this@MainActivity, it, Core.strs(it.getJSONArray("times")).first()) }
                    ?: toast("Add a remedy first")
            }, lp(-2, -2, 0, 8, 0, 0))
        }
        addCard(p.bad) {
            addView(tx("Backup", 18f, p.ink, bold = true, serif = true))
            addView(tx("Saves your remedies and history to a file. Your API key is not included.", 13f, p.mut, italic = true, serif = true))
            val r = LinearLayout(context)
            r.addView(btn("Export", p.gold, p.onAcc) { exportBackup() }, lp(-2, -2, 0, 10, 8, 0))
            r.addView(btn("Import", p.gold, p.gold, true) { importBackup() }, lp(-2, -2, 0, 10, 0, 0))
            addView(r)
        }
    }

    // ---------- Backup ----------
    private fun exportBackup() {
        val name = "pillpal-backup-" + Core.ymd(Calendar.getInstance()) + ".json"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 101
        )
    }
    private fun importBackup() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 102)
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == 101) {
                contentResolver.openOutputStream(uri)?.use { it.write(Core.exportJson(this).toByteArray()) }
                toast("Backup saved")
            } else if (requestCode == 102) {
                val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                AlertDialog.Builder(this).setTitle("Import backup?")
                    .setMessage("This replaces your current remedies and history.")
                    .setPositiveButton("Import") { _, _ ->
                        try { val n = Core.importJson(this, txt); toast("Imported $n remedies"); show() }
                        catch (e: Exception) { toast("Not a valid backup: " + e.message) }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        } catch (e: Exception) { toast("Failed: " + e.message) }
    }
}
