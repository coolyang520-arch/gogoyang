package com.gogoyang.plates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One ticketed scooter: the rear photo taken in round 1. */
data class Rec(val id: String, val t: Long)

/** Keeps today's ticketed scooters (photo + fingerprint) on this phone only. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("shots", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
    private val feats = HashMap<String, Feat>()
    var date: String = ""
    val records = mutableListOf<Rec>()

    fun load(today: String) {
        date = sp.getString("date", today) ?: today
        records.clear()
        try {
            val arr = JSONArray(sp.getString("records", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                records.add(Rec(o.getString("id"), o.getLong("t")))
            }
        } catch (e: Exception) {
            records.clear()
        }
        if (date != today) reset(today)
    }

    fun reset(today: String) {
        date = today
        records.clear()
        synchronized(feats) { feats.clear() }
        dir.listFiles()?.forEach { it.delete() }
        save()
    }

    fun find(id: String): Rec? = records.firstOrNull { it.id == id }

    fun photoFile(id: String) = File(dir, "$id.jpg")

    /** Photo scaled down by [sample] (1 = full size), or null if it is gone. */
    fun photo(id: String, sample: Int = 1): Bitmap? =
        BitmapFactory.decodeFile(photoFile(id).path, BitmapFactory.Options().apply { inSampleSize = sample })

    /** Fingerprint of a recorded scooter; safe to call from the worker thread. */
    fun feat(id: String): Feat? = synchronized(feats) {
        feats[id] ?: Feat.read(File(dir, "$id.orb"))?.also { feats[id] = it }
    }

    fun add(photo: Bitmap, f: Feat): Rec {
        var t = System.currentTimeMillis()
        while (find(t.toString()) != null) t++
        val r = Rec(t.toString(), t)
        photoFile(r.id).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f.write(File(dir, "${r.id}.orb"))
        synchronized(feats) { feats[r.id] = f }
        records.add(0, r)
        save()
        return r
    }

    fun remove(r: Rec) {
        records.remove(r)
        synchronized(feats) { feats.remove(r.id) }
        photoFile(r.id).delete()
        File(dir, "${r.id}.orb").delete()
        save()
    }

    fun save() {
        val arr = JSONArray()
        records.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.t)) }
        sp.edit().putString("date", date).putString("records", arr.toString()).apply()
    }
}
