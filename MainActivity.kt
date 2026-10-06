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
        if (st == "taken" && prev != "taken" && stock >= 0) {
            m.put("stock", maxOf(0, stock - m.optInt("per", 1)))
            saveMeds(c, meds(c).map { if (it.getString("id") == id) m else it })
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
class MainActivity : Activity() {
    private lateinit var box: LinearLayout
    private var tab = 0
    private var ai: List<JSONObject> = emptyList()
    private var aiMsg = ""
    private var aiText = ""
    private var editing: JSONObject? = null

    private fun tv(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 12, 0, 12) }
    private fun btn(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }
    private fun et(h: String, v: String = "") = EditText(this).apply { hint = h; setText(v) }
    private fun describe(m: JSONObject): String {
        val dn = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val d = Core.ints(m.getJSONArray("days"))
        val st = m.optInt("stock", -1)
        return Core.strs(m.getJSONArray("times")).joinToString(", ") + " · " +
            (if (d.size == 7) "every day" else d.joinToString(" ") { dn[it - 1] }) +
            (if (m.optString("end") != "") " · until " + m.optString("end") else "") +
            (if (st >= 0) " · stock $st" else "")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Core.channel(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true }
        val bar = LinearLayout(this)
        listOf("Today", "Meds", "AI", "Settings").forEachIndexed { i, n ->
            bar.addView(btn(n) { tab = i; editing = null; show() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar)
        root.addView(ScrollView(this).apply { addView(box) })
        setContentView(root)
        Core.scheduleAll(this)
        show()
    }

    private fun show() {
        box.removeAllViews()
        when (tab) { 0 -> today(); 1 -> if (editing != null) form(editing!!) else medsList(); 2 -> aiTab(); else -> settings() }
    }

    private fun today() {
        Core.meds(this).filter { it.optInt("stock", -1) in 0..5 }.forEach {
            box.addView(tv("Refill soon: ${it.getString("name")} (${it.getInt("stock")} left)"))
        }
        val ds = Core.dosesToday(this)
        if (ds.isEmpty()) box.addView(tv("Nothing scheduled today. Add medicines in Meds or AI."))
        ds.forEach { (m, t) ->
            val id = m.getString("id")
            val st = Core.getLog(this, Core.key(id, t))
            box.addView(tv("$t  ${m.getString("name")} ${m.optString("dose")}\n${m.optString("notes")}"))
            val r = LinearLayout(this)
            if (st == null) {
                r.addView(btn("Taken") { Core.mark(this, id, t, "taken"); show() })
                r.addView(btn("Skip") { Core.mark(this, id, t, "skipped"); show() })
            } else r.addView(tv("Marked: $st"))
            box.addView(r)
        }
    }

    private fun medsList() {
        box.addView(btn("+ Add manually") {
            editing = JSONObject().put("id", "m" + System.currentTimeMillis()).put("name", "").put("dose", "").put("per", 1)
                .put("times", JSONArray().put("08:00")).put("days", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                .put("end", "").put("stock", -1).put("notes", "").put("isNew", true)
            show()
        })
        val l = Core.meds(this)
        if (l.isEmpty()) box.addView(tv("No medicines yet."))
        l.forEach { m ->
            box.addView(tv("${m.getString("name")} ${m.optString("dose")}\n${describe(m)}"))
            box.addView(btn("Edit") { editing = m; show() })
        }
    }

    private fun form(m: JSONObject) {
        val name = et("Name", m.getString("name")); val dose = et("Dose (e.g. 500 mg)", m.optString("dose"))
        val per = et("Units per dose", m.optInt("per", 1).toString())
        val times = et("Times, comma separated (08:00,20:00)", Core.strs(m.getJSONArray("times")).joinToString(","))
        val end = et("Last day yyyy-mm-dd (optional)", m.optString("end"))
        val stock = et("Stock in units (optional)", if (m.optInt("stock", -1) >= 0) m.getInt("stock").toString() else "")
        val notes = et("Notes", m.optString("notes"))
        listOf(name, dose, per, times, end, stock, notes).forEach { box.addView(it) }
        val dn = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val cur = Core.ints(m.getJSONArray("days"))
        val row = LinearLayout(this)
        val cbs = dn.mapIndexed { i, n -> CheckBox(this).apply { text = n; isChecked = (i + 1) in cur; textSize = 11f } }
        cbs.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        box.addView(row)
        box.addView(btn("Save") {
            val ts = times.text.toString().split(",").map { it.trim() }.filter { Regex("\\d{1,2}:\\d{2}").matches(it) }
                .map { it.padStart(5, '0') }.sorted()
            val ds = cbs.indices.filter { cbs[it].isChecked }.map { it + 1 }
            if (name.text.isBlank() || ts.isEmpty() || ds.isEmpty()) {
                Toast.makeText(this, "Need a name, a time like 08:00, and a day", Toast.LENGTH_LONG).show()
            } else {
                val o = JSONObject().put("id", m.getString("id")).put("name", name.text.toString().trim())
                    .put("dose", dose.text.toString()).put("per", per.text.toString().toIntOrNull() ?: 1)
                    .put("times", JSONArray(ts)).put("days", JSONArray(ds)).put("end", end.text.toString().trim())
                    .put("stock", stock.text.toString().toIntOrNull() ?: -1).put("notes", notes.text.toString())
                Core.upsert(this, o); editing = null; show()
            }
        })
        box.addView(btn("Cancel") { editing = null; show() })
        if (!m.optBoolean("isNew")) box.addView(btn("Delete") { Core.delete(this, m.getString("id")); editing = null; show() })
    }

    private fun aiTab() {
        box.addView(tv("Describe your medicines, e.g. \"Metformin 500mg after breakfast and dinner for 30 days, 60 tablets\". You can also paste prescription text."))
        val q = et("Describe your medicines", aiText).apply { minLines = 4 }
        box.addView(q)
        box.addView(btn("Create with AI") {
            aiText = q.text.toString()
            val key = Core.sp(this).getString("key", "") ?: ""
            val model = Core.sp(this).getString("model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
            if (key.isEmpty()) { aiMsg = "Add your Gemini API key in Settings first."; show() }
            else if (aiText.isNotBlank()) {
                aiMsg = "Thinking..."; show()
                thread {
                    try {
                        val r = Core.ask(key, model, aiText)
                        runOnUiThread { ai = r; aiMsg = if (r.isEmpty()) "No medicines found." else "Check the result, then save."; show() }
                    } catch (e: Exception) { runOnUiThread { ai = emptyList(); aiMsg = "Error: " + e.message; show() } }
                }
            }
        })
        box.addView(tv(aiMsg))
        ai.forEach { box.addView(tv("${it.getString("name")} ${it.optString("dose")}\n${describe(it)}\n${it.optString("notes")}")) }
        if (ai.isNotEmpty()) {
            box.addView(btn("Save all ${ai.size}") { ai.forEach { Core.upsert(this, it) }; ai = emptyList(); aiMsg = "Saved."; aiText = ""; tab = 0; show() })
            box.addView(btn("Discard") { ai = emptyList(); aiMsg = ""; show() })
        }
    }

    private fun settings() {
        val sp = Core.sp(this)
        box.addView(tv("Gemini API key (from aistudio.google.com)"))
        val key = et("AIza...", sp.getString("key", "") ?: "")
        val model = et("Model", sp.getString("model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite")
        box.addView(key); box.addView(model)
        box.addView(btn("Save") {
            sp.edit().putString("key", key.text.toString().trim()).putString("model", model.text.toString().trim().ifEmpty { "gemini-3.5-flash-lite" }).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })
        box.addView(tv("Reminders"))
        if (Build.VERSION.SDK_INT in 31..32) box.addView(btn("Allow exact alarms") {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        })
        box.addView(btn("Test notification") {
            Core.meds(this).firstOrNull()?.let { Core.notify(this, it, Core.strs(it.getJSONArray("times")).first()) }
                ?: Toast.makeText(this, "Add a medicine first", Toast.LENGTH_SHORT).show()
        })
    }
}
