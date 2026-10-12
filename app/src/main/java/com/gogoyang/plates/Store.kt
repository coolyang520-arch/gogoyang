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
                records.add(Rec(o.getString("p"), o.getLong("t")))
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

    fun add(plate: String): Rec {
        val r = Rec(plate, System.currentTimeMillis())
        records.add(0, r)
        save()
        return r
    }

    fun remove(r: Rec) {
        records.remove(r)
        save()
    }

    fun save() {
        val arr = JSONArray()
        records.forEach { arr.put(JSONObject().put("p", it.plate).put("t", it.t)) }
        sp.edit().putString("date", date).putString("records", arr.toString()).apply()
    }
}
