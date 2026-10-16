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

    private fun load(key: String): JSONObject = try { JSONObject(sp.getString(key, "{}")) } catch (e: Exception) { JSONObject() }

    /** A whole misread seen before, replaced by what the user said it really was. */
    fun fix(raw: String): String = load("map").optString(raw, raw)

    fun teach(raw: String, truth: String) {
        if (raw.isEmpty() || raw == truth) return
        val o = load("map")
        o.put(raw, truth)
        // If the corrected plate was itself a learned wrong read, drop that entry.
        if (o.has(truth) && o.optString(truth) != truth) o.remove(truth)
        // Character mix-ups (for example 8 read where the plate has B), counted per pair.
        val pairs = load("pairs")
        if (raw.length == truth.length) {
            for (i in raw.indices) {
                if (raw[i] != truth[i]) {
                    val k = "" + raw[i] + ">" + truth[i]
                    pairs.put(k, pairs.optInt(k, 0) + 1)
                }
            }
        }
        sp.edit().putString("map", o.toString()).putString("pairs", pairs.toString()).apply()
    }

    /**
     * True when [read] differs from [plate] only by character mix-ups the user has corrected before
     * (at most two of them), so the read is very likely that plate.
     */
    fun explains(read: String, plate: String): Boolean {
        if (read.length != plate.length || read == plate) return false
        val pairs = load("pairs")
        var diffs = 0
        for (i in read.indices) {
            if (read[i] != plate[i]) {
                diffs++
                if (diffs > 2 || pairs.optInt("" + read[i] + ">" + plate[i], 0) < 1) return false
            }
        }
        return diffs > 0
    }

    fun count(): Int = load("map").length()
}
