package com.gogoyang.plates

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.max

/** Visual fingerprint of one photo: ORB keypoints (pixel positions) and their 32-byte descriptors. */
class Feat(val w: Int, val h: Int, val pts: FloatArray, val desc: ByteArray) {
    val n: Int get() = pts.size / 2
    private var mat: Mat? = null

    fun descMat(): Mat = mat ?: Mat(n, 32, CvType.CV_8U).also { it.put(0, 0, desc); mat = it }

    fun write(f: File) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(w); o.writeInt(h); o.writeInt(n)
            pts.forEach { o.writeFloat(it) }
            o.write(desc)
        }
    }

    companion object {
        fun read(f: File): Feat? = try {
            DataInputStream(f.inputStream().buffered()).use { i ->
                val w = i.readInt(); val h = i.readInt(); val n = i.readInt()
                val pts = FloatArray(n * 2) { i.readFloat() }
                val desc = ByteArray(n * 32)
                i.readFully(desc)
                Feat(w, h, pts, desc)
            }
        } catch (e: Exception) { null }
    }
}

/**
 * Compares scooter rears by their look, not by reading the plate: keypoints are matched between
 * two photos and only matches that agree on one consistent perspective (RANSAC homography) count.
 * Unrelated scooters rarely produce more than a handful of such matches.
 * Not thread safe: call from one worker thread.
 */
object Matcher {
    private const val SIDE = 640
    private val orb by lazy { ORB.create(800) }
    private val clahe by lazy { Imgproc.createCLAHE(2.0, Size(8.0, 8.0)) }
    private val bf by lazy { DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING) }

    /** The photo as kept on the phone and compared: longest side [SIDE] pixels. */
    fun shrink(b: Bitmap): Bitmap {
        val s = SIDE.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    fun extract(b: Bitmap): Feat {
        val rgba = Mat()
        val gray = Mat()
        val kp = MatOfKeyPoint()
        val d = Mat()
        try {
            Utils.bitmapToMat(b, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            // Even out shade and sunlight so the same scooter at another hour still matches.
            clahe.apply(gray, gray)
            orb.detectAndCompute(gray, Mat(), kp, d)
            val arr = kp.toArray()
            val n = minOf(arr.size, d.rows())
            val pts = FloatArray(n * 2)
            for (i in 0 until n) {
                pts[i * 2] = arr[i].pt.x.toFloat()
                pts[i * 2 + 1] = arr[i].pt.y.toFloat()
            }
            val desc = ByteArray(n * 32)
            if (n > 0) d.get(0, 0, desc)
            return Feat(b.width, b.height, pts, desc)
        } finally {
            rgba.release(); gray.release(); kp.release(); d.release()
        }
    }

    /** Geometrically consistent matches from [q] (new photo) to [r] (recorded one); plate-box matches count double. */
    fun score(q: Feat, r: Feat): Int {
        if (q.n < 10 || r.n < 10) return 0
        val knn = ArrayList<MatOfDMatch>()
        bf.knnMatch(q.descMat(), r.descMat(), knn, 2)
        // Ratio test, then keep one match per recorded keypoint.
        val best = HashMap<Int, Pair<Int, Float>>()
        for (m in knn) {
            val a = m.toArray()
            m.release()
            if (a.size < 2 || a[0].distance >= 0.75f * a[1].distance) continue
            val prev = best[a[0].trainIdx]
            if (prev == null || a[0].distance < prev.second) best[a[0].trainIdx] = a[0].queryIdx to a[0].distance
        }
        if (best.size < 8) return 0
        val pairs = best.map { (t, qd) -> qd.first to t }
        val src = MatOfPoint2f(*pairs.map { Point(q.pts[it.first * 2].toDouble(), q.pts[it.first * 2 + 1].toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f(*pairs.map { Point(r.pts[it.second * 2].toDouble(), r.pts[it.second * 2 + 1].toDouble()) }.toTypedArray())
        val mask = Mat()
        try {
            val hm = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 6.0, mask)
            if (hm.empty()) return 0
            // Reject warps no second photo of the same scooter would need (flips, huge zoom).
            val det = hm.get(0, 0)[0] * hm.get(1, 1)[0] - hm.get(0, 1)[0] * hm.get(1, 0)[0]
            hm.release()
            if (det < 0.25 || det > 4.0) return 0
            var score = 0
            for (i in pairs.indices) {
                if (mask.get(i, 0)[0] == 0.0) continue
                val qi = pairs[i].first
                score += if (Decide.inPlate(q.pts[qi * 2] / q.w, q.pts[qi * 2 + 1] / q.h)) 2 else 1
            }
            return score
        } catch (e: Exception) {
            return 0
        } finally {
            src.release(); dst.release(); mask.release()
        }
    }
}
