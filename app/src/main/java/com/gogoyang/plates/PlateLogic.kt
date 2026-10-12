package com.gogoyang.plates

import java.text.Normalizer
import kotlin.math.abs

data class Rec(val plate: String, val t: Long, val raw: String = "")

object PlateLogic {
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '8' to 'B', '5' to 'S', '2' to 'Z', '6' to 'G')
    private val toDigit = mapOf('O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1', 'B' to '8', 'S' to '5', 'Z' to '2', 'G' to '6')

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun format(p: String): String {
        val m = Regex("^([A-Z]+)([0-9]+)$").find(p) ?: Regex("^([0-9]+)([A-Z]+)$").find(p)
        return if (m != null) m.groupValues[1] + "-" + m.groupValues[2] else p
    }

    /** Fold look-alike letters onto digits so 0/O, 1/I, 8/B ... compare equal. */
    fun fold(p: String): String = p.map { toDigit[it] ?: it }.joinToString("")

    /** True when the two strings differ by at most one inserted, missing or changed character. */
    fun lev1(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (abs(la - lb) > 1) return false
        var i = 0
        while (i < la && i < lb && a[i] == b[i]) i++
        return when {
            la == lb -> a.substring(i + 1) == b.substring(i + 1)
            la > lb -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }

    fun findNear(n: String, records: List<Rec>): List<Rec> {
        val f = fold(n)
        return records.filter { r ->
            if (r.plate == n) return@filter false
            val rf = fold(r.plate)
            rf == f || (n.length >= 5 && r.plate.length >= 5 && (lev1(n, r.plate) || lev1(f, rf)))
        }
    }

    fun search(q: String, records: List<Rec>): List<Rec> {
        if (q.length < 2) return emptyList()
        val fq = fold(q)
        return records.filter { it.plate.contains(q) || fold(it.plate).contains(fq) }
    }

    private data class Fmt(val lettersFirst: Boolean, val nL: Int, val nD: Int)

    // Common Taiwan plate layouts: ABC-1234, 123-ABC, AB-1234, 1234-AB, ABC-123, 123-AB
    private val formats = listOf(
        Fmt(true, 3, 4), Fmt(false, 3, 3), Fmt(true, 2, 4),
        Fmt(false, 2, 4), Fmt(true, 3, 3), Fmt(false, 2, 3)
    )

    private data class Cand(val plate: String, val len: Int, val fixes: Int)

    /**
     * Pull the most plate-like string out of raw OCR text. Characters in the wrong class
     * (a digit where a letter belongs) are corrected, at most one per plate.
     */
    fun extract(text: String): String? {
        val lines = text.split('\n').map { normalize(it) }.filter { it.isNotEmpty() }
        val pieces = ArrayList<String>(lines)
        for (i in 0 until lines.size - 1) pieces.add(lines[i] + lines[i + 1])
        var best: Cand? = null
        for (s in pieces) {
            if (s.length < 5) continue
            for (f in formats) {
                val n = f.nL + f.nD
                if (s.length < n) continue
                for (off in 0..(s.length - n)) {
                    var fixes = 0
                    var ok = true
                    val sb = StringBuilder()
                    for (k in 0 until n) {
                        val ch = s[off + k]
                        val wantLetter = if (f.lettersFirst) k < f.nL else k >= f.nD
                        val isLetter = ch in 'A'..'Z'
                        if (wantLetter == isLetter) {
                            sb.append(ch)
                        } else {
                            val c = if (wantLetter) toLetter[ch] else toDigit[ch]
                            if (c == null) { ok = false; break }
                            sb.append(c)
                            fixes++
                        }
                    }
                    if (!ok || fixes > 1) continue
                    val cand = Cand(sb.toString(), n, fixes)
                    val cur = best
                    if (cur == null || cand.len > cur.len || (cand.len == cur.len && cand.fixes < cur.fixes)) best = cand
                }
            }
        }
        return best?.plate
    }
}
