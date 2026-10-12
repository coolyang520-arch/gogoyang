package com.gogoyang.plates

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Keeps today's ticketed plates on this phone only. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("plates", Context.MODE_PRIVATE)
    var date: String = ""
    val records = mutableListOf<Rec>()

    fun load(today: String) {
        date = sp.getString("date", today) ?: today
        records.clear()
        try {
            val arr = JSONArray(sp.getString("records", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                records.add(Rec(o.getString("p"), o.getLong("t"), o.optString("r", "")))
            }
        } catch (e: Exception) {
            records.clear()
        }
        if (date != today) reset(today)
    }

    fun reset(today: String) {
        date = today
        records.clear()
        save()
    }

    fun find(plate: String): Rec? = records.firstOrNull { it.plate == plate }

    fun add(plate: String, raw: String = ""): Rec {
        val r = Rec(plate, System.currentTimeMillis(), raw)
        records.add(0, r)
        save()
        return r
    }

    /** Change a record's plate in place. If the new plate is already recorded, the old entry is dropped. */
    fun replace(old: Rec, newPlate: String): Rec? {
        val i = records.indexOf(old)
        if (i < 0) return null
        val dup = find(newPlate)
        if (dup != null && dup !== old) {
            records.removeAt(i)
            save()
            return dup
        }
        val r = Rec(newPlate, old.t, old.raw)
        records[i] = r
        save()
        return r
    }

    fun remove(r: Rec) {
        records.remove(r)
        save()
    }

    fun save() {
        val arr = JSONArray()
        records.forEach { arr.put(JSONObject().put("p", it.plate).put("t", it.t).put("r", it.raw)) }
        sp.edit().putString("date", date).putString("records", arr.toString()).apply()
    }
}


/**
 * Remembers corrections the user made (what the camera read -> what the plate really was),
 * so the same misread is fixed automatically next time. It does not retrain the OCR model.
 * Kept across days.
 */
class Learn(ctx: Context) {
    private val sp = ctx.getSharedPreferences("learn", Context.MODE_PRIVATE)

    private fun load(): JSONObject = try { JSONObject(sp.getString("map", "{}")) } catch (e: Exception) { JSONObject() }

    fun fix(raw: String): String = load().optString(raw, raw)

    fun teach(raw: String, truth: String) {
        if (raw.isEmpty() || raw == truth) return
        val o = load()
        o.put(raw, truth)
        // If the corrected plate was itself a learned wrong read, drop that entry.
        if (o.has(truth) && o.optString(truth) != truth) o.remove(truth)
        sp.edit().putString("map", o.toString()).apply()
    }

    fun count(): Int = load().length()
}
