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
import android.view.animation.OvershootInterpolator
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.widget.ImageView
import android.widget.HorizontalScrollView
import java.time.format.DateTimeFormatter
import android.app.TimePickerDialog
import android.app.DatePickerDialog
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
private const val GITHUB_PROFILE = "https://github.com/abdulwajid2840"
private const val GITHUB_HELP = "https://github.com/abdulwajid2840/PillPal#get-a-gemini-api-key"

object Core {
    fun sp(c: Context) = c.getSharedPreferences("pp", 0)
    fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }
    fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }

    fun meds(c: Context): List<JSONObject> {
        val a = JSONArray(sp(c).getString("meds", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }
    fun saveMeds(c: Context, l: List<JSONObject>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        sp(c).edit().putString("meds", a.toString()).apply()
    }
    fun med(c: Context, id: String) = meds(c).firstOrNull { it.getString("id") == id }

    fun upsert(c: Context, m: JSONObject) {
        val l = meds(c).toMutableList()
        val i = l.indexOfFirst { it.getString("id") == m.getString("id") }
        if (i >= 0) {
            cancelAll(c, l[i])
            l[i] = m
        } else {
            l.add(m)
        }
        saveMeds(c, l)
        scheduleAll(c)
    }
    fun delete(c: Context, id: String) {
        val l = meds(c).toMutableList()
        l.firstOrNull { it.getString("id") == id }?.let { cancelAll(c, it) }
        saveMeds(c, l.filter { it.getString("id") != id })
    }

    // ---- helpers ----
    fun ymd(c: Calendar) = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    fun key(id: String, t: String) = ymd(Calendar.getInstance()) + "|" + id + "|" + t
    fun mode(m: JSONObject) = m.optString("mode", "times")
    fun validHm(s: String) = Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$").matches(s.trim())
    fun hm(s: String): Int {
        val p = s.trim().split(":")
        return p[0].toInt() * 60 + p[1].toInt()
    }
    fun parseInterval(s: String): Int {
        val r = Regex("^([0-9]+)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)?$")
        val mt = r.find(s.trim().lowercase()) ?: return 0
        val n = mt.groupValues[1].toIntOrNull() ?: return 0
        val mult = when (mt.groupValues[2]) {
            "h", "hr", "hrs", "hour", "hours" -> 60
            "d", "day", "days" -> 1440
            else -> 1
        }
        return n * mult
    }
    fun fmtInterval(min: Int): String {
        if (min >= 1440 && min % 1440 == 0) return (min / 1440).toString() + " day" + (if (min / 1440 > 1) "s" else "")
        if (min >= 60 && min % 60 == 0) return (min / 60).toString() + " h"
        return "$min min"
    }
    fun shortInterval(min: Int): String {
        if (min <= 0) return ""
        if (min % 60 == 0) return (min / 60).toString() + "h"
        return min.toString() + "m"
    }

    // ---- safety hints (never block, only warn) ----
    fun doseMg(s: String): Double? {
        val r = Regex("([0-9][0-9,]*\\.?[0-9]*)\\s*(lakh|lac|thousand|k)?\\s*(mg|mcg|g)\\b", RegexOption.IGNORE_CASE)
        val mt = r.find(s) ?: return null
        val num = mt.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (mt.groupValues[2].lowercase()) {
            "lakh", "lac" -> 100000.0
            "thousand", "k" -> 1000.0
            else -> 1.0
        }
        val unit = when (mt.groupValues[3].lowercase()) {
            "g" -> 1000.0
            "mcg" -> 0.001
            else -> 1.0
        }
        return num * mult * unit
    }
    fun warn(m: JSONObject): String? {
        val mg = doseMg(m.optString("dose", ""))
        if (mg != null && mg > 5000) {
            return "This dose looks unusually high. Please check it with a doctor or pharmacist before using it."
        }
        if (mode(m) == "interval" && m.optInt("intervalMin", 0) in 1..59) {
            return "Reminders more often than every hour. Make sure this schedule is really what your doctor prescribed."
        }
        return null
    }

    // ---- log ----
    fun getLog(c: Context, k: String): String? = JSONObject(sp(c).getString("log", "{}")).optString(k, "").ifEmpty { null }
    fun count(c: Context, id: String): Int = getLog(c, key(id, "~"))?.toIntOrNull() ?: 0

    fun mark(c: Context, id: String, t: String, st: String) {
        val lg = JSONObject(sp(c).getString("log", "{}"))
        val k = key(id, t)
        val prev = lg.optString(k, "")
        var delta = 0
        if (t == "~") {
            val cnt = prev.toIntOrNull() ?: 0
            if (st == "taken") {
                lg.put(k, (cnt + 1).toString())
                delta = 1
            } else if (st == "" && cnt > 0) {
                lg.put(k, (cnt - 1).toString())
                delta = -1
            }
        } else {
            lg.put(k, st)
            if (st == "taken" && prev != "taken") {
                delta = 1
            } else if (st != "taken" && prev == "taken") {
                delta = -1
            }
        }
        sp(c).edit().putString("log", lg.toString()).apply()
        val m = med(c, id) ?: return
        val stock = m.optInt("stock", -1)
        if (delta != 0 && stock >= 0) {
            val per = m.optInt("per", 1)
            m.put("stock", if (delta > 0) maxOf(0, stock - per) else stock + per)
            saveMeds(c, meds(c).map { if (it.getString("id") == id) m else it })
        }
    }

    fun fmt12(s: String): String {
        if (!validHm(s)) return s
        val mins = hm(s)
        val h = mins / 60
        val mi = mins % 60
        val ap = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, mi, ap)
    }
    fun weekStats(c: Context): List<Triple<String, Int, Int>> {
        val lg = JSONObject(sp(c).getString("log", "{}"))
        val ms = meds(c).filter { mode(it) == "times" }
        val dn = listOf("S", "M", "T", "W", "T", "F", "S")
        return (6 downTo 0).map { back ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -back)
            val day = ymd(cal)
            var total = 0
            var taken = 0
            ms.forEach { m ->
                if (activeOn(m, cal)) {
                    strs(m.getJSONArray("times")).forEach { t ->
                        total++
                        if (lg.optString(day + "|" + m.getString("id") + "|" + t) == "taken") taken++
                    }
                }
            }
            Triple(dn[cal.get(Calendar.DAY_OF_WEEK) - 1], taken, total)
        }
    }
    fun snooze(c: Context, id: String, t: String, minutes: Int = 10) {
        val am = c.getSystemService(AlarmManager::class.java)
        val p = PendingIntent.getBroadcast(
            c, 0,
            Intent(c, AlarmReceiver::class.java).setData(Uri.parse("pp://snooze/$id/$t"))
                .putExtra("id", id).putExtra("t", t).putExtra("snooze", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + minutes * 60000L
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    // ---- when is a medicine active on a day ----
    fun activeOn(m: JSONObject, cal: Calendar): Boolean {
        val day = ymd(cal)
        val end = m.optString("end", "")
        val start = m.optString("start", "")
        if (end != "" && day > end) return false
        if (start != "" && day < start) return false
        if (cal.get(Calendar.DAY_OF_WEEK) !in ints(m.getJSONArray("days"))) return false
        val sd = runCatching { LocalDate.parse(start) }.getOrNull()
        if (sd != null) {
            val today = LocalDate.of(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val idx = ChronoUnit.DAYS.between(sd, today).toInt()
            val every = m.optInt("every", 1).coerceAtLeast(1)
            if (idx % every != 0) return false
            val on = m.optInt("cycleOn", 0)
            val off = m.optInt("cycleOff", 0)
            if (on > 0 && off > 0 && (idx / every) % (on + off) >= on) return false
        }
        return true
    }
    fun dosesToday(c: Context): List<Pair<JSONObject, String>> {
        val now = Calendar.getInstance()
        return meds(c).filter { mode(it) == "times" && activeOn(it, now) }
            .flatMap { m -> strs(m.getJSONArray("times")).map { m to it } }.sortedBy { it.second }
    }
    fun repeatingToday(c: Context): List<JSONObject> {
        val now = Calendar.getInstance()
        return meds(c).filter { mode(it) != "times" && activeOn(it, now) }
    }

    // ---- alarms ----
    private fun pi(c: Context, id: String, t: String) = PendingIntent.getBroadcast(
        c, 0,
        Intent(c, AlarmReceiver::class.java).setData(Uri.parse("pp://alarm/$id/$t")).putExtra("id", id).putExtra("t", t),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun dayStart(d: Int): Calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, d)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    fun nextTime(m: JSONObject, t: String): Long? {
        val mins = hm(t)
        val nowMs = System.currentTimeMillis()
        for (d in 0..400) {
            val day = dayStart(d)
            if (!activeOn(m, day)) continue
            val at = day.timeInMillis + mins * 60000L
            if (at > nowMs + 1000) return at
        }
        return null
    }
    fun nextInterval(m: JSONObject): Long? {
        val iv = m.optInt("intervalMin", 0)
        if (iv < 1) return null
        val s = hm(m.optString("winStart", "00:00"))
        val e = hm(m.optString("winEnd", "23:59"))
        val step = iv * 60000L
        val nowMs = System.currentTimeMillis() + 1000
        for (d in 0..400) {
            val day = dayStart(d)
            if (!activeOn(m, day)) continue
            val first = day.timeInMillis + s * 60000L
            val k = if (nowMs >= first) (nowMs - first) / step + 1 else 0L
            val slot = first + k * step
            if (slot > nowMs - 1000 + 1000 && slot <= day.timeInMillis + e * 60000L + 59000L) return slot
        }
        return null
    }
    fun scheduleOne(c: Context, m: JSONObject, t: String) {
        val at = (if (t == "~") nextInterval(m) else nextTime(m, t)) ?: return
        val am = c.getSystemService(AlarmManager::class.java)
        val p = pi(c, m.getString("id"), t)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }
    fun scheduleMed(c: Context, m: JSONObject) {
        when (mode(m)) {
            "times" -> strs(m.getJSONArray("times")).forEach { scheduleOne(c, m, it) }
            "interval" -> scheduleOne(c, m, "~")
        }
    }
    fun scheduleAll(c: Context) = meds(c).forEach { scheduleMed(c, it) }
    fun cancelAll(c: Context, m: JSONObject) {
        val am = c.getSystemService(AlarmManager::class.java)
        strs(m.getJSONArray("times")).forEach { am.cancel(pi(c, m.getString("id"), it)) }
        am.cancel(pi(c, m.getString("id"), "~"))
    }

    // ---- notifications ----
    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("meds", "Medicine reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }
    fun notify(c: Context, m: JSONObject, t: String) {
        channel(c)
        val id = m.getString("id")
        val nid = (id + t).hashCode()
        fun act(st: String, label: String): Notification.Action {
            val i = Intent(c, ActionReceiver::class.java).setData(Uri.parse("pp://act/$id/$t/$st"))
                .putExtra("id", id).putExtra("t", t).putExtra("st", st).putExtra("n", nid)
            val p = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, p).build()
        }
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stock = m.optInt("stock", -1)
        val low = if (stock in 0..5) " · only $stock left" else ""
        val extra = if (t == "~") "Every " + fmtInterval(m.optInt("intervalMin", 0)) else m.optString("notes")
        val n = Notification.Builder(c, "meds")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time for " + m.getString("name"))
            .setContentText((m.optString("dose") + " " + extra + low).trim())
            .setAutoCancel(true).setContentIntent(open)
            .addAction(act("taken", "Taken")).addAction(act("skipped", "Skip")).addAction(act("snooze", "Snooze 10 min"))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(nid, n)
    }

    // ---- backup ----
    fun exportJson(c: Context): String = JSONObject().put("app", "PillPal").put("version", 2)
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
{"name":string,"dose":string (copy exactly as written),"unitsPerDose":number,"mode":"times" or "interval" or "asneeded","times":["HH:MM" 24h] (for mode times),"intervalMinutes":number (for mode interval),"windowStart":"HH:MM","windowEnd":"HH:MM" (for mode interval; default 00:00 and 23:59),"days":[1-7, Sunday=1 ... Saturday=7; all seven if daily],"everyNDays":number (1 if every day),"cycleOnDays":number or null,"cycleOffDays":number or null,"durationDays":number or null,"stock":number or null,"notes":string}
Rules: "every 5 min" means mode interval with intervalMinutes 5. "every 8 hours" means interval 480. "as needed", "if fever", "SOS" mean mode asneeded. "alternate days" means everyNDays 2. "5 days on, 2 days off" means cycleOnDays 5 and cycleOffDays 2.
Defaults for mode times: once daily 08:00; twice daily 08:00,20:00; thrice daily 08:00,14:00,20:00; morning 08:00; afternoon 14:00; evening 18:00; night 21:00; after breakfast 09:00; after lunch 14:00; after dinner 21:00.
Use exactly the numbers the user gave. Never change a dose or frequency and never invent medicines.
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
        val today = ymd(Calendar.getInstance())
        return List(arr.length()) { i ->
            val a = arr.getJSONObject(i)
            val dur = a.optInt("durationDays", 0)
            val end = if (dur > 0) ymd(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dur - 1) }) else ""
            var md = a.optString("mode", "times")
            val iv = a.optInt("intervalMinutes", 0)
            if (md !in listOf("times", "interval", "asneeded")) md = "times"
            if (md == "interval" && iv < 1) md = "times"
            val ts = (a.optJSONArray("times") ?: JSONArray()).let { arr2 -> strs(arr2).filter { validHm(it) }.map { it.padStart(5, '0') } }
            val ws = a.optString("windowStart", "00:00").let { if (validHm(it)) it else "00:00" }
            val we = a.optString("windowEnd", "23:59").let { if (validHm(it)) it else "23:59" }
            JSONObject()
                .put("id", "m" + System.currentTimeMillis() + i)
                .put("name", a.optString("name", "Medicine"))
                .put("dose", a.optString("dose", ""))
                .put("per", a.optInt("unitsPerDose", 1).coerceAtLeast(1))
                .put("mode", md)
                .put("times", JSONArray(if (ts.isEmpty()) listOf("08:00") else ts))
                .put("intervalMin", if (md == "interval") iv else 0)
                .put("winStart", if (ws <= we) ws else "00:00")
                .put("winEnd", if (ws <= we) we else "23:59")
                .put("days", a.optJSONArray("days")?.takeIf { it.length() > 0 } ?: JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                .put("every", a.optInt("everyNDays", 1).coerceAtLeast(1))
                .put("cycleOn", a.optInt("cycleOnDays", 0))
                .put("cycleOff", a.optInt("cycleOffDays", 0))
                .put("start", today)
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
        if (i.getBooleanExtra("snooze", false)) {
            Core.notify(c, m, t)
            return
        }
        val md = Core.mode(m)
        if (t == "~") {
            if (md != "interval") return
        } else if (md != "times" || t !in Core.strs(m.getJSONArray("times"))) {
            return
        }
        Core.notify(c, m, t)
        Core.scheduleOne(c, m, t)
    }
}
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        val t = i.getStringExtra("t") ?: return
        val st = i.getStringExtra("st") ?: return
        if (st == "snooze") {
            Core.snooze(c, id, t)
        } else {
            Core.mark(c, id, t, st)
        }
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
    private var host: LinearLayout? = null
    private var query = ""
    private var navShown = -1

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
    private fun colorFor(m: JSONObject): Int {
        val ci = m.optInt("color", -1)
        return if (ci in 0..5) c((if (p.dark) medDark else medLight)[ci]) else medColor(m.getString("id"))
    }

    private fun weekCard() {
        val st = Core.weekStats(this)
        if (st.all { it.third == 0 }) return
        var streak = 0
        for (k in st.indices.reversed()) {
            val tk = st[k].second
            val tt = st[k].third
            if (tt == 0) continue
            if (tk == tt) {
                streak++
            } else if (k == st.size - 1) {
                continue
            } else {
                break
            }
        }
        addCard(p.time) {
            val hdr = LinearLayout(context)
            hdr.gravity = Gravity.CENTER_VERTICAL
            hdr.addView(tx("This week", 15f, p.ink, bold = true, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (streak > 0) hdr.addView(chip("$streak-day streak", p.ok))
            addView(hdr)
            val row = LinearLayout(context)
            st.forEachIndexed { k, d ->
                val tk = d.second
                val tt = d.third
                val colr = if (tt == 0) p.line else if (tk == tt) p.ok else if (tk > 0) p.gold else if (k == st.size - 1) p.line else p.bad
                val colm = LinearLayout(context)
                colm.orientation = LinearLayout.VERTICAL
                colm.gravity = Gravity.CENTER_HORIZONTAL
                val dot = tx(if (tt == 0) "–" else "$tk/$tt", 10f, if (tt == 0) p.mut else p.onAcc, bold = true)
                dot.gravity = Gravity.CENTER
                dot.setPadding(0, 0, 0, 0)
                dot.background = bg(colr, 18f)
                colm.addView(dot, LinearLayout.LayoutParams(dp(36), dp(36)))
                colm.addView(tx(d.first, 11f, p.mut), lp(-2, -2, 0, 4, 0, 0))
                row.addView(colm, lp(0, -2).apply { weight = 1f })
            }
            addView(row, lp(-1, -2, 0, 8, 0, 0))
        }
    }

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
        (host ?: box).addView(outer, lp(-1, -2, 2, 7, 2, 7))
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
        val md = Core.mode(m)
        if (md == "asneeded") {
            return "As needed"
        }
        val head = if (md == "interval") {
            "Every " + Core.fmtInterval(m.optInt("intervalMin", 0)) + " · " + Core.fmt12(m.optString("winStart", "00:00")) + " – " + Core.fmt12(m.optString("winEnd", "23:59"))
        } else {
            Core.strs(m.getJSONArray("times")).joinToString(", ") { Core.fmt12(it) }
        }
        var s = head + " · " + (if (d.size == 7) "every day" else d.joinToString(" ") { dn[it - 1] })
        val ev = m.optInt("every", 1)
        if (ev > 1) {
            s += " · every $ev days"
        }
        if (m.optInt("cycleOn", 0) > 0 && m.optInt("cycleOff", 0) > 0) {
            s += " · " + m.optInt("cycleOn") + " on / " + m.optInt("cycleOff") + " off"
        }
        return s
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tab = b?.getInt("tab") ?: 0
        p = pal(isDark())
        Core.channel(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        lightBars(!p.dark)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); fitsSystemWindows = true }
        scroll = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(24)) }
        scroll.addView(box)
        nav = LinearLayout(this)
        nav.background = bg(p.card, 30f, p.line, 1)
        nav.elevation = dp(10).toFloat()
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.setPadding(dp(8), dp(8), dp(8), dp(8))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, lp(-1, -2, 14, 4, 14, 12))
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
        val names = listOf("Today", "Medicines", "Ask AI", "Settings")
        val icons = listOf(R.drawable.ic_today, R.drawable.ic_meds, R.drawable.ic_ai, R.drawable.ic_settings)
        for (i in names.indices) {
            val on = (tab == i)
            val item = LinearLayout(this)
            item.gravity = Gravity.CENTER
            item.setPadding(dp(10), dp(11), dp(10), dp(11))
            if (on) {
                item.background = bg(p.hi, 22f, p.gold, 1)
            } else {
                item.background = RippleDrawable(ColorStateList.valueOf(withA(p.gold, 0x33)), null, bg(Color.WHITE, 22f))
            }
            val ic = ImageView(this)
            ic.setImageResource(icons[i])
            ic.setColorFilter(if (on) p.gold else p.mut)
            item.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
            if (on) {
                val lb = tx(names[i], 13f, p.gold, bold = true, serif = true)
                lb.setPadding(dp(8), 0, 0, 0)
                lb.maxLines = 1
                item.addView(lb)
                if (navShown != i) {
                    lb.alpha = 0f
                    lb.animate().alpha(1f).setDuration(300).start()
                    ic.scaleX = 0.5f
                    ic.scaleY = 0.5f
                    ic.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(2.5f)).start()
                }
            }
            item.setOnClickListener {
                if (tab != i) {
                    tab = i
                    editing = null
                    scroll.scrollTo(0, 0)
                    box.alpha = 0f
                    show()
                    box.animate().alpha(1f).setDuration(220).start()
                }
            }
            val params = lp(0, -2, 2, 0, 2, 0)
            params.weight = if (on) 2.3f else 1f
            nav.addView(item, params)
        }
        navShown = tab
    }

    // ---------- Today ----------
    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            toast("No browser found")
        }
    }
    private fun icon(res: Int, col: Int): Drawable {
        val d = resources.getDrawable(res, theme).mutate()
        d.setTint(col)
        return d
    }
    private fun hit(m: JSONObject, extra: String, q: String): Boolean {
        if (q.isBlank()) return true
        val hay = (m.optString("name") + " " + m.optString("dose") + " " + m.optString("notes") + " " + extra + " " + describe(m)).lowercase()
        return q.lowercase().split(" ").filter { it.isNotBlank() }.all { hay.contains(it) }
    }

    private fun today() {
        val now = Calendar.getInstance()
        heading("Today", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.time))
        val search = field("Search name, dose, time, notes...", query)
        search.background = bg(p.card, 22f, p.line, 1)
        search.isSingleLine = true
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_search, p.mut), null, null, null)
        search.compoundDrawablePadding = dp(10)
        box.addView(search, lp(-1, -2, 0, 0, 0, 6))
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        box.addView(list)
        fun fill() {
            list.removeAllViews()
            host = list
            renderToday(now, query)
            host = null
        }
        fill()
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                fill()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val f = tx("Made with ♥ by @abdulwajid2840", 13f, p.gold, italic = true, serif = true)
        f.gravity = Gravity.CENTER
        f.setPadding(0, dp(12), 0, dp(12))
        f.setOnClickListener { openUrl(GITHUB_PROFILE) }
        box.addView(f, lp(-1, -2, 0, 16, 0, 0))
    }

    private fun renderToday(now: Calendar, q: String) {
        val ds = Core.dosesToday(this)
        val rep0 = Core.repeatingToday(this)
        val stat = ds.map { Core.getLog(this, Core.key(it.first.getString("id"), it.second)) }
        val done = stat.count { it != null }
        val filtering = q.isNotBlank()
        val idx = ds.indices.filter { hit(ds[it].first, ds[it].second + " " + Core.fmt12(ds[it].second) + " " + (stat[it] ?: "pending"), q) }
        val rep = rep0.filter { hit(it, if (Core.mode(it) == "interval") "repeating" else "as needed", q) }
        if (ds.isEmpty() && rep0.isEmpty()) {
            addCard(p.gold) {
                addView(tx("A quiet day", 18f, p.ink, bold = true, serif = true))
                addView(tx("Nothing is scheduled. Add a medicine under Medicines or Ask AI.", 14f, p.mut))
            }
        } else if (filtering && idx.isEmpty() && rep.isEmpty()) {
            addCard(p.gold) {
                addView(tx("No match", 18f, p.ink, bold = true, serif = true))
                addView(tx("Nothing today matches \"$q\". Try a name, dose, time or word like taken.", 14f, p.mut))
            }
        } else if (!filtering && ds.isNotEmpty()) {
            addCard(p.gold) {
                addView(tx("$done of ${ds.size} doses marked", 15f, p.mut, italic = true, serif = true))
                val bar = LinearLayout(context).apply { background = bg(p.line, 6f); clipToOutline = true }
                bar.addView(View(context).apply { setBackgroundColor(p.ok) }, LinearLayout.LayoutParams(0, -1, done.toFloat()))
                bar.addView(View(context), LinearLayout.LayoutParams(0, -1, (ds.size - done).toFloat()))
                addView(bar, lp(-1, dp(8), 0, 8, 0, 2))
            }
        }
        if (!filtering) weekCard()
        if (!filtering) {
            Core.meds(this).filter { it.optInt("stock", -1) in 0..5 }.forEach { m ->
                addCard(p.bad) {
                    addView(tx("Refill soon", 12f, p.bad, bold = true))
                    addView(tx("${m.getString("name")} · ${m.getInt("stock")} left", 16f, p.ink, serif = true))
                }
            }
        }
        val nowS = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val nextIdx = ds.indices.firstOrNull { stat[it] == null && ds[it].second >= nowS }
            ?: ds.indices.firstOrNull { stat[it] == null } ?: -1
        idx.forEach { i ->
            val m = ds[i].first
            val t = ds[i].second
            val id = m.getString("id")
            val st = stat[i]
            val col = colorFor(m)
            val isNext = i == nextIdx
            addCard(col, isNext) {
                val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(tx(Core.fmt12(t), 24f, p.time, bold = true, serif = true))
                top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                if (st == "taken") {
                    top.addView(chip("✓ Taken", p.ok))
                } else if (st == "skipped") {
                    top.addView(chip("Skipped", p.bad))
                } else if (isNext) {
                    top.addView(chip("Next dose", p.gold))
                }
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
        rep.forEach { m ->
            val id = m.getString("id")
            val col = colorFor(m)
            val n = Core.count(this, id)
            val isIv = Core.mode(m) == "interval"
            addCard(col) {
                val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(chip(if (isIv) "Repeating" else "As needed", p.time))
                top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                top.addView(chip("Taken today: $n", if (n > 0) p.ok else p.mut))
                addView(top, lp(-1, -2, 0, 0, 0, 4))
                addView(tx(m.getString("name"), 20f, p.ink, bold = true, serif = true))
                if (m.optString("dose") != "") addView(tx(m.optString("dose"), 15f, col, bold = true))
                addView(tx(describe(m), 14f, p.time))
                if (isIv) {
                    val nx = Core.nextInterval(m)
                    val label = if (nx == null) "No more reminders" else "Next reminder " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(nx))
                    addView(tx(label, 13f, p.mut, italic = true, serif = true))
                }
                if (m.optString("notes") != "") addView(tx(m.optString("notes"), 14f, p.mut, italic = true, serif = true))
                val w = Core.warn(m)
                if (w != null) addView(tx(w, 13f, p.bad, italic = true, serif = true))
                val r = LinearLayout(context)
                r.addView(btn(if (isIv) "Log dose" else "Take now", p.ok, p.onAcc) { Core.mark(this@MainActivity, id, "~", "taken"); show() }, lp(-2, -2, 0, 10, 8, 0))
                if (n > 0) r.addView(btn("Undo", p.mut, p.mut, true) { Core.mark(this@MainActivity, id, "~", ""); show() }, lp(-2, -2, 0, 10, 0, 0))
                addView(r)
            }
        }
    }

    // ---------- Medicines ----------
    private fun newMed(): JSONObject = JSONObject().put("id", "m" + System.currentTimeMillis()).put("name", "").put("dose", "").put("per", 1)
        .put("mode", "times").put("times", JSONArray().put("08:00")).put("intervalMin", 0)
        .put("winStart", "00:00").put("winEnd", "23:59")
        .put("days", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7))).put("every", 1).put("cycleOn", 0).put("cycleOff", 0)
        .put("start", Core.ymd(Calendar.getInstance())).put("end", "").put("stock", -1).put("notes", "").put("color", Core.meds(this).size % 6).put("isNew", true)

    private fun medsList() {
        heading("Medicines", "Your medicine shelf")
        box.addView(btn("+ Add medicine", p.gold, p.onAcc) {
            editing = newMed()
            scroll.scrollTo(0, 0)
            show()
        }, lp(-2, -2, 0, 4, 0, 8))
        val l = Core.meds(this)
        if (l.isEmpty()) addCard(p.gold) { addView(tx("The shelf is empty. Add a medicine by hand or with Ask AI.", 14f, p.mut)) }
        l.forEach { m ->
            val col = colorFor(m)
            addCard(col) {
                addView(tx(m.getString("name"), 20f, p.ink, bold = true, serif = true))
                if (m.optString("dose") != "") addView(tx(m.optString("dose"), 15f, col, bold = true))
                addView(tx(describe(m), 14f, p.time))
                if (m.optString("end") != "") addView(tx("Until " + m.optString("end"), 13f, p.mut, italic = true, serif = true))
                if (m.optString("notes") != "") addView(tx(m.optString("notes"), 14f, p.mut, italic = true, serif = true))
                val w = Core.warn(m)
                if (w != null) addView(tx(w, 13f, p.bad, italic = true, serif = true))
                val sk = m.optInt("stock", -1)
                if (sk >= 0) addView(chip("Stock $sk", if (sk <= 5) p.bad else p.ok), lp(-2, -2, 0, 6, 0, 0))
                addView(btn("Edit", p.gold, p.gold, true) { editing = m; scroll.scrollTo(0, 0); show() }, lp(-2, -2, 0, 10, 0, 0))
            }
        }
    }

    private fun lab(s: String) = tx(s, 12f, p.mut, bold = true).apply { setPadding(0, dp(12), 0, dp(3)) }
    private fun pill(label: String, on: Boolean, f: () -> Unit): TextView {
        val t = tx(label, 13f, if (on) p.onAcc else p.gold, bold = true, serif = true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        t.background = if (on) bg(p.gold, 18f) else bg(Color.TRANSPARENT, 18f, p.gold, 1)
        t.setOnClickListener { f() }
        return t
    }
    private fun hrow(vararg v: View): HorizontalScrollView {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val r = LinearLayout(this)
        for (x in v) r.addView(x, lp(-2, -2, 0, 0, 8, 0))
        hs.addView(r)
        return hs
    }
    private fun pickTime(initial: String?, onDone: (String) -> Unit) {
        val mins = if (initial != null && Core.validHm(initial)) Core.hm(initial) else 8 * 60
        TimePickerDialog(this, { _, hh, mm -> onDone("%02d:%02d".format(hh, mm)) }, mins / 60, mins % 60, false).show()
    }
    private fun pickDate(initial: String, onDone: (String) -> Unit) {
        val cal = Calendar.getInstance()
        runCatching {
            val d = LocalDate.parse(initial)
            cal.set(d.year, d.monthValue - 1, d.dayOfMonth)
        }
        DatePickerDialog(
            this, { _, y, mo, d -> onDone("%04d-%02d-%02d".format(y, mo + 1, d)) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    private fun fmtDate(s: String): String {
        if (s.isBlank()) return "Not set"
        return runCatching {
            SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!!)
        }.getOrDefault(s)
    }
    private fun setTimes(m: JSONObject, l: List<String>) {
        m.put("times", JSONArray(l.distinct().sorted()))
    }

    private fun fmtShort(s: String): String {
        if (s.isBlank()) return ""
        return runCatching {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!!)
        }.getOrDefault(s)
    }

    private fun rangePicker(startS: String, endS: String, onDone: (String, String) -> Unit) {
        var from: LocalDate? = runCatching { LocalDate.parse(startS) }.getOrNull()
        var to: LocalDate? = runCatching { LocalDate.parse(endS) }.getOrNull()
        var month: LocalDate = (from ?: LocalDate.now()).withDayOfMonth(1)
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(14), dp(14), dp(14), dp(6))
        wrap.background = bg(p.card, 24f)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val title = tx("", 18f, p.ink, bold = true, serif = true)
        title.gravity = Gravity.CENTER
        val prev = tx("‹", 26f, p.gold, bold = true)
        val next = tx("›", 26f, p.gold, bold = true)
        prev.setPadding(dp(14), 0, dp(14), 0)
        next.setPadding(dp(14), 0, dp(14), 0)
        head.addView(prev)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(next)
        val summary = tx("", 13f, p.time, italic = true, serif = true)
        summary.gravity = Gravity.CENTER
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        wrap.addView(head)
        wrap.addView(summary, lp(-1, -2, 0, 4, 0, 6))
        wrap.addView(grid)
        val today = LocalDate.now()
        fun render() {
            title.text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            val f = from
            val t = to
            summary.text = if (f == null) {
                "Tap a start date"
            } else if (t == null) {
                "From " + fmtShort(f.toString()) + " · tap an end date"
            } else {
                fmtShort(f.toString()) + "  →  " + fmtShort(t.toString())
            }
            grid.removeAllViews()
            val hdr = LinearLayout(this)
            for (d in listOf("S", "M", "T", "W", "T", "F", "S")) {
                val h = tx(d, 12f, p.mut, bold = true)
                h.gravity = Gravity.CENTER
                hdr.addView(h, LinearLayout.LayoutParams(0, -2, 1f))
            }
            grid.addView(hdr)
            val offset = month.dayOfWeek.value % 7
            val len = month.lengthOfMonth()
            for (row in 0..5) {
                val rl = LinearLayout(this)
                for (col in 0..6) {
                    val dayNum = row * 7 + col - offset + 1
                    val cell = tx(if (dayNum in 1..len) dayNum.toString() else "", 14f, p.ink)
                    cell.gravity = Gravity.CENTER
                    cell.setPadding(0, dp(10), 0, dp(10))
                    if (dayNum in 1..len) {
                        val date = month.withDayOfMonth(dayNum)
                        val edge = (date == f) || (date == t)
                        val inside = f != null && t != null && date.isAfter(f) && date.isBefore(t)
                        if (edge) {
                            cell.setTextColor(p.onAcc)
                            cell.background = bg(p.gold, 20f)
                        } else if (inside) {
                            cell.background = bg(p.hi, 6f)
                        } else if (date == today) {
                            cell.background = bg(Color.TRANSPARENT, 20f, p.time, 1)
                        }
                        cell.setOnClickListener {
                            val cf = from
                            if (cf == null || to != null) {
                                from = date
                                to = null
                            } else if (date.isBefore(cf)) {
                                to = cf
                                from = date
                            } else {
                                to = date
                            }
                            render()
                        }
                    }
                    rl.addView(cell, lp(0, -2, 1, 1, 1, 1).apply { weight = 1f })
                }
                grid.addView(rl)
            }
        }
        prev.setOnClickListener {
            month = month.minusMonths(1)
            render()
        }
        next.setOnClickListener {
            month = month.plusMonths(1)
            render()
        }
        render()
        val dlg = AlertDialog.Builder(this).setView(wrap)
            .setPositiveButton("Apply") { _, _ ->
                val f = from
                if (f != null) onDone(f.toString(), to?.toString() ?: "")
            }
            .setNeutralButton("Ongoing") { _, _ ->
                val f = from
                onDone(f?.toString() ?: startS, "")
            }
            .setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(bg(p.card, 24f))
            for (b in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)) {
                dlg.getButton(b).setTextColor(p.gold)
            }
        }
        dlg.show()
    }

    private fun form(m: JSONObject) {
        val isNew = m.optBoolean("isNew")
        val mode = Core.mode(m)
        heading(if (isNew) "New medicine" else "Edit medicine", "Fill in the details")
        val name = field("e.g. Metformin", m.getString("name"))
        val dose = field("e.g. 500 mg", m.optString("dose"))
        val per = field("1", m.optInt("per", 1).toString())
        val ival = field("e.g. 5m, 30m, 8h", Core.shortInterval(m.optInt("intervalMin", 0)))
        val every = field("1", m.optInt("every", 1).toString())
        val con = field("On", if (m.optInt("cycleOn", 0) > 0) m.optInt("cycleOn").toString() else "")
        val coff = field("Off", if (m.optInt("cycleOff", 0) > 0) m.optInt("cycleOff").toString() else "")
        val stock = field("Optional", if (m.optInt("stock", -1) >= 0) m.getInt("stock").toString() else "")
        val notes = field("e.g. after food", m.optString("notes"))

        fun collect() {
            m.put("name", name.text.toString().trim())
            m.put("dose", dose.text.toString())
            m.put("per", per.text.toString().toIntOrNull() ?: 1)
            m.put("intervalMin", Core.parseInterval(ival.text.toString()))
            m.put("every", every.text.toString().toIntOrNull() ?: 1)
            m.put("cycleOn", con.text.toString().toIntOrNull() ?: 0)
            m.put("cycleOff", coff.text.toString().toIntOrNull() ?: 0)
            m.put("stock", stock.text.toString().toIntOrNull() ?: -1)
            m.put("notes", notes.text.toString())
        }
        fun redo(f: () -> Unit) {
            collect()
            f()
            show()
        }
        fun validate(): String? {
            val md = Core.mode(m)
            if (m.getString("name").isBlank()) return "Enter a name"
            if (md != "asneeded" && m.getJSONArray("days").length() == 0) return "Pick at least one day"
            if (md == "times" && m.getJSONArray("times").length() == 0) return "Add at least one time"
            if (md == "interval" && m.optInt("intervalMin", 0) < 1) return "Enter how often, like 5m or 8h"
            if (md == "interval" && Core.hm(m.optString("winStart", "00:00")) > Core.hm(m.optString("winEnd", "23:59"))) {
                return "Until must be later than From"
            }
            val s = m.optString("start")
            val e = m.optString("end")
            if (s != "" && e != "" && e < s) return "The last day is before the start date"
            return null
        }

        // colour picker
        val ci = m.optInt("color", -1).let { if (it in 0..5) it else (m.getString("id").hashCode() and 0x7fffffff) % 6 }
        val colorRow = LinearLayout(this)
        for (k in 0..5) {
            val cc = c((if (p.dark) medDark else medLight)[k])
            val dotv = View(this)
            dotv.background = if (k == ci) bg(cc, 20f, p.ink, 3) else bg(cc, 20f)
            dotv.setOnClickListener { redo { m.put("color", k) } }
            colorRow.addView(dotv, lp(dp(32), dp(32), 0, 0, 10, 0))
        }

        // days
        val dn2 = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        val cur = Core.ints(m.getJSONArray("days"))
        val dayRow = LinearLayout(this)
        for (i in 0..6) {
            val on = (i + 1) in cur
            val t = tx(dn2[i], 12f, if (on) p.onAcc else p.mut, bold = true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(11), 0, dp(11))
            t.background = if (on) bg(p.gold, 20f) else bg(Color.TRANSPARENT, 20f, p.line, 1)
            t.setOnClickListener {
                redo {
                    val set = Core.ints(m.getJSONArray("days")).toMutableSet()
                    if (!set.add(i + 1)) set.remove(i + 1)
                    m.put("days", JSONArray(set.sorted()))
                }
            }
            dayRow.addView(t, lp(0, -2, 2, 0, 2, 0).apply { weight = 1f })
        }

        // fixed times
        val tl = Core.strs(m.getJSONArray("times"))
        val timeRows = LinearLayout(this)
        timeRows.orientation = LinearLayout.VERTICAL
        tl.forEachIndexed { idx, t ->
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(btn(Core.fmt12(t), p.gold, p.gold, true) {
                collect()
                pickTime(t) { nt ->
                    val l = tl.toMutableList()
                    l[idx] = nt
                    setTimes(m, l)
                    show()
                }
            }, lp(0, -2, 0, 4, 8, 0).apply { weight = 1f })
            if (tl.size > 1) {
                row.addView(btn("✕", p.bad, p.bad, true) {
                    redo {
                        val l = tl.toMutableList()
                        l.removeAt(idx)
                        setTimes(m, l)
                    }
                }, lp(-2, -2, 0, 4, 0, 0))
            }
            timeRows.addView(row)
        }
        val presets = hrow(
            pill("Once daily", false) { redo { setTimes(m, listOf("08:00")) } },
            pill("Twice daily", false) { redo { setTimes(m, listOf("08:00", "20:00")) } },
            pill("3 times", false) { redo { setTimes(m, listOf("08:00", "14:00", "20:00")) } },
            pill("4 times", false) { redo { setTimes(m, listOf("08:00", "12:00", "16:00", "20:00")) } }
        )
        val ivPills = hrow(*listOf(5, 15, 30, 60, 120, 240, 360, 480, 720).map { v ->
            pill(Core.shortInterval(v), m.optInt("intervalMin", 0) == v) { redo { m.put("intervalMin", v) } } as View
        }.toTypedArray())

        // duration
        val start = m.optString("start")
        val end = m.optString("end")
        fun endFor(n: Int): String {
            val s = if (start.isBlank()) Core.ymd(Calendar.getInstance()) else start
            return LocalDate.parse(s).plusDays((n - 1).toLong()).toString()
        }
        val durPills = hrow(*(listOf(3, 5, 7, 14, 30).map { n ->
            pill("$n days", end == endFor(n)) {
                redo {
                    if (start.isBlank()) m.put("start", Core.ymd(Calendar.getInstance()))
                    m.put("end", endFor(n))
                }
            } as View
        } + (pill("Ongoing", end == "") { redo { m.put("end", "") } } as View)).toTypedArray())
        val foodPills = hrow(*listOf("Before food", "After food", "With food", "At bedtime").map { f ->
            pill(f, false) {
                redo {
                    val cn = m.optString("notes")
                    if (!cn.contains(f)) m.put("notes", if (cn.isBlank()) f else "$cn, $f")
                }
            } as View
        }.toTypedArray())

        val modeRow = LinearLayout(this)
        listOf("times" to "Fixed times", "interval" to "Repeating", "asneeded" to "As needed").forEach { (k, n) ->
            val b = if (mode == k) btn(n, p.gold, p.onAcc) { } else btn(n, p.mut, p.mut, true) {
                collect()
                m.put("mode", k)
                show()
            }
            b.textSize = 13f
            b.setPadding(dp(6), 0, dp(6), 0)
            modeRow.addView(b, lp(0, -2, 0, 0, 6, 0).apply { weight = 1f })
        }

        addCard(colorFor(m)) {
            addView(tx("Medicine", 18f, p.ink, bold = true, serif = true))
            addView(lab("NAME")); addView(name)
            addView(lab("DOSE")); addView(dose)
            addView(lab("UNITS PER DOSE")); addView(per)
            addView(lab("COLOUR")); addView(colorRow)
        }
        addCard(p.time) {
            addView(tx("Schedule", 18f, p.ink, bold = true, serif = true))
            addView(modeRow, lp(-1, -2, 0, 8, 0, 0))
            if (mode == "times") {
                addView(lab("QUICK SETUP")); addView(presets)
                addView(lab("TIMES")); addView(timeRows)
                addView(btn("+ Add time", p.gold, p.onAcc) {
                    collect()
                    pickTime(null) { nt -> setTimes(m, tl + nt); show() }
                }, lp(-2, -2, 0, 8, 0, 0))
            }
            if (mode == "interval") {
                addView(lab("REPEAT EVERY")); addView(ival); addView(ivPills, lp(-1, -2, 0, 8, 0, 0))
                addView(lab("ONLY BETWEEN"))
                val wr = LinearLayout(context)
                val ws = m.optString("winStart", "00:00")
                val we = m.optString("winEnd", "23:59")
                wr.addView(btn("From " + Core.fmt12(ws), p.gold, p.gold, true) {
                    collect()
                    pickTime(ws) { nt -> m.put("winStart", nt); show() }
                }, lp(0, -2, 0, 0, 8, 0).apply { weight = 1f })
                wr.addView(btn("Until " + Core.fmt12(we), p.gold, p.gold, true) {
                    collect()
                    pickTime(we) { nt -> m.put("winEnd", nt); show() }
                }, lp(0, -2).apply { weight = 1f })
                addView(wr)
            }
            if (mode == "asneeded") {
                addView(tx("No alarms. Use Take now on the Today screen when you need a dose.", 13f, p.mut, italic = true, serif = true), lp(-2, -2, 0, 8, 0, 0))
            } else {
                addView(lab("DAYS")); addView(dayRow)
                addView(lab("REPEAT EVERY N DAYS (1 = DAILY)")); addView(every)
                addView(lab("CYCLE: DAYS ON / DAYS OFF (OPTIONAL)"))
                val cr = LinearLayout(context)
                cr.addView(con, lp(0, -2, 0, 0, 8, 0).apply { weight = 1f })
                cr.addView(coff, lp(0, -2).apply { weight = 1f })
                addView(cr)
            }
        }
        addCard(p.ok) {
            addView(tx("Duration", 18f, p.ink, bold = true, serif = true))
            addView(lab("DATES"))
            val rl = (if (start.isBlank()) "Today" else fmtShort(start)) + "  →  " + (if (end.isBlank()) "Ongoing" else fmtShort(end))
            addView(btn(rl, p.gold, p.gold, true) {
                collect()
                rangePicker(start.ifBlank { Core.ymd(Calendar.getInstance()) }, end) { f, t ->
                    m.put("start", f)
                    m.put("end", t)
                    show()
                }
            })
            addView(lab("QUICK DURATION")); addView(durPills)
        }
        addCard(p.bad) {
            addView(tx("Extras", 18f, p.ink, bold = true, serif = true))
            addView(lab("STOCK (UNITS LEFT)")); addView(stock)
            addView(lab("NOTES")); addView(notes)
            addView(foodPills, lp(-1, -2, 0, 8, 0, 0))
        }

        val saveB = btn("Save", p.gold, p.onAcc) {
            collect()
            if (m.optString("start") == "") m.put("start", Core.ymd(Calendar.getInstance()))
            val err = validate()
            if (err != null) {
                toast(err)
            } else {
                val doSave = {
                    m.remove("isNew")
                    Core.upsert(this@MainActivity, m)
                    editing = null
                    show()
                }
                val w = Core.warn(m)
                if (w == null) {
                    doSave()
                } else {
                    AlertDialog.Builder(this@MainActivity).setTitle("Please double-check").setMessage(w)
                        .setPositiveButton("Save anyway") { _, _ -> doSave() }
                        .setNegativeButton("Edit", null).show()
                }
            }
        }
        val cancelB = btn("Cancel", p.mut, p.mut, true) { editing = null; show() }
        val items = mutableListOf<Button>(saveB, cancelB)
        if (!isNew) {
            items.add(btn("Duplicate", p.gold, p.gold, true) {
                collect()
                val cp = JSONObject(m.toString())
                cp.put("id", "m" + System.currentTimeMillis())
                cp.put("name", m.getString("name") + " (copy)")
                cp.put("isNew", true)
                editing = cp
                show()
            })
            items.add(btn("Delete", p.bad, p.bad, true) {
                Core.delete(this@MainActivity, m.getString("id"))
                editing = null
                show()
            })
        }
        val bar = LinearLayout(this)
        items.forEach { b ->
            b.textSize = 13f
            b.setPadding(dp(4), 0, dp(4), 0)
            bar.addView(b, lp(0, -2, 0, 0, 6, 0).apply { weight = 1f })
        }
        box.addView(bar, lp(-1, -2, 0, 12, 0, 0))
    }

    // ---------- Ask AI ----------
    private fun aiTab() {
        heading("Ask AI", "Describe it, and the scribe fills the schedule")
        val q = field("e.g. Amoxicillin 500mg every 8 hours for 5 days", aiText, 4)
        addCard(p.gold) {
            addView(tx("Fixed times, every few minutes or hours, every other day, on/off cycles, or as needed. You can also paste prescription text.", 14f, p.mut, italic = true, serif = true))
            addView(q, lp(-1, -2, 0, 8, 0, 0))
            addView(btn("Create with AI", p.gold, p.onAcc) { runAI(q.text.toString()) }, lp(-2, -2, 0, 10, 0, 0))
            if (aiMsg != "") addView(tx(aiMsg, 14f, p.time, italic = true, serif = true), lp(-2, -2, 0, 8, 0, 0))
        }
        ai.forEach { m ->
            val col = colorFor(m)
            addCard(col) {
                addView(tx(m.getString("name"), 18f, p.ink, bold = true, serif = true))
                if (m.optString("dose") != "") addView(tx(m.optString("dose"), 15f, col, bold = true))
                addView(tx(describe(m), 14f, p.time))
                if (m.optString("end") != "") addView(tx("Until " + m.optString("end"), 13f, p.mut, italic = true, serif = true))
                if (m.optInt("stock", -1) >= 0) addView(tx("Stock " + m.getInt("stock"), 13f, p.ok))
                if (m.optString("notes") != "") addView(tx(m.optString("notes"), 14f, p.mut, italic = true, serif = true))
                val w = Core.warn(m)
                if (w != null) addView(tx(w, 13f, p.bad, italic = true, serif = true))
            }
        }
        if (ai.isNotEmpty()) {
            val r = LinearLayout(this)
            r.addView(btn("Save all ${ai.size}", p.ok, p.onAcc) {
                ai.forEach { Core.upsert(this, it) }
                ai = emptyList()
                aiMsg = "Saved."
                aiText = ""
                tab = 0
                show()
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
                        sp.edit().putString("theme", k).apply()
                        recreate()
                    }, lp(-2, -2, 0, 8, 8, 0)
                )
            }
            addView(r)
        }
        val key = field("AIza...", sp.getString("key", "") ?: "")
        key.transformationMethod = PasswordTransformationMethod.getInstance()
        val model = field("Model", sp.getString("model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite")
        var shown = false
        val tog = btn("Show", p.mut, p.mut, true) {}
        tog.setOnClickListener {
            shown = !shown
            key.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
            tog.text = if (shown) "Hide" else "Show"
        }
        val help = tx("?", 17f, p.gold, bold = true, serif = true)
        help.gravity = Gravity.CENTER
        help.background = bg(p.hi, 18f, p.gold, 1)
        help.setOnClickListener { openUrl(GITHUB_HELP) }
        addCard(p.time) {
            val hdr = LinearLayout(context)
            hdr.gravity = Gravity.CENTER_VERTICAL
            hdr.addView(tx("Gemini API", 18f, p.ink, bold = true, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
            hdr.addView(help, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(hdr)
            addView(tx("Tap ? for a step by step guide to get your key", 13f, p.mut, italic = true, serif = true))
            addView(key, lp(-1, -2, 0, 6, 0, 0))
            addView(tog, lp(-2, -2, 0, 6, 0, 0))
            addView(model, lp(-1, -2, 0, 6, 0, 0))
            addView(btn("Save", p.gold, p.onAcc) {
                sp.edit().putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifEmpty { "gemini-3.5-flash-lite" }).apply()
                toast("Saved")
            }, lp(-2, -2, 0, 10, 0, 0))
        }
        addCard(p.ok) {
            addView(tx("Reminders", 18f, p.ink, bold = true, serif = true))
            if (Build.VERSION.SDK_INT in 31..32) {
                addView(btn("Allow exact alarms", p.gold, p.gold, true) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }, lp(-2, -2, 0, 8, 0, 0))
            }
            addView(btn("Test notification", p.gold, p.gold, true) {
                val first = Core.meds(this@MainActivity).firstOrNull()
                if (first == null) {
                    toast("Add a medicine first")
                } else {
                    val tm = if (Core.mode(first) == "times") Core.strs(first.getJSONArray("times")).firstOrNull() ?: "~" else "~"
                    Core.notify(this@MainActivity, first, tm)
                }
            }, lp(-2, -2, 0, 8, 0, 0))
        }
        addCard(p.bad) {
            addView(tx("Backup", 18f, p.ink, bold = true, serif = true))
            addView(tx("Saves your medicines and history to a file. Your API key is not included.", 13f, p.mut, italic = true, serif = true))
            val r = LinearLayout(context)
            r.addView(btn("Export", p.gold, p.onAcc) { exportBackup() }, lp(-2, -2, 0, 10, 8, 0))
            r.addView(btn("Import", p.gold, p.gold, true) { importBackup() }, lp(-2, -2, 0, 10, 0, 0))
            addView(r)
        }
        addCard(p.gold, true) {
            addView(tx("About PillPal", 18f, p.ink, bold = true, serif = true))
            addView(tx("Made with ♥ by @abdulwajid2840", 16f, p.gold, bold = true, serif = true))
            addView(tx("Built on a phone, with Gemini helping to read schedules.", 13f, p.mut, italic = true, serif = true))
            addView(btn("Visit my GitHub", p.gold, p.onAcc) { openUrl(GITHUB_PROFILE) }, lp(-2, -2, 0, 10, 0, 0))
            addView(btn("Setup guide", p.gold, p.gold, true) { openUrl(GITHUB_HELP) }, lp(-2, -2, 0, 8, 0, 0))
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
                    .setMessage("This replaces your current medicines and history.")
                    .setPositiveButton("Import") { _, _ ->
                        try { val n = Core.importJson(this, txt); toast("Imported $n medicines"); show() }
                        catch (e: Exception) { toast("Not a valid backup: " + e.message) }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        } catch (e: Exception) { toast("Failed: " + e.message) }
    }
}
