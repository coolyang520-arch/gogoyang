package com.gogoyang.plates

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val cInk = Color.parseColor("#131F2A")
    private val cMuted = Color.parseColor("#566676")
    private val cBg = Color.parseColor("#E8ECF0")
    private val cAccent = Color.parseColor("#0B5FA5")
    private val cOk = Color.parseColor("#13803F")
    private val cNo = Color.parseColor("#C62828")
    private val cWarn = Color.parseColor("#E0A100")

    // Guide frame as a share of the preview: the scooter rear fills it, the plate sits in the
    // dashed box inside (see Decide). Only this area is photographed and compared, so a
    // neighbouring scooter at the edge of the picture is left out: one photo, one scooter.
    private val gLeft = 0.08f
    private val gWidth = 0.84f
    private val gTop = 0.03f
    private val gHeight = 0.94f
    @Volatile private var pvW = 0
    @Volatile private var pvH = 0

    private lateinit var store: Store
    private lateinit var previewView: PreviewView
    private lateinit var guide: View
    private lateinit var mask: MaskView
    private lateinit var statusBox: LinearLayout
    private lateinit var tvLabel: TextView
    private lateinit var photoRow: LinearLayout
    private lateinit var tvDetail: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var tvCount: TextView
    private lateinit var tvInfo: TextView
    private lateinit var adapter: BaseAdapter
    private lateinit var btnMark: Button
    private lateinit var btnCheck: Button
    private lateinit var btnShoot: Button
    private lateinit var btnTorch: Button

    private var mode = "mark"
    private var cvReady = false
    private var cameraStarted = false
    private var pendingCameraStart = false
    private var camera: Camera? = null
    private var torchOn = false
    // Set when the shutter is pressed; the next camera frame becomes the photo.
    @Volatile private var wantShot = false
    @Volatile private var shotRecs: List<Rec> = emptyList()
    private var busy = false
    private var shotMode = "mark"

    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newSingleThreadExecutor()
    private var tone: ToneGenerator? = null

    /** A photo just taken, with its fingerprint, waiting to be recorded or compared. */
    private class Shot(val photo: Bitmap, val feat: Feat)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            showStatus("warn", "沒有相機權限", "請到手機設定允許相機。", emptyList(), emptyList())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cvReady = try { OpenCVLoader.initLocal() } catch (e: Throwable) { false }
        store = Store(this)
        store.load(todayStr())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        refreshList()
        updateModeUi()
        showIdle()
        if (!cvReady) showStatus("warn", "比對元件無法載入", "這支手機無法執行照片比對。", emptyList(), emptyList())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (store.date != todayStr()) {
            store.reset(todayStr())
            thumbs.evictAll()
            refreshList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        tone?.release()
    }

    // Volume keys work as a shutter, so the phone can be used with one hand.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
    private fun ago(t: Long): String {
        val m = maxOf(0L, (System.currentTimeMillis() - t) / 60000)
        return when {
            m < 1 -> "剛剛"
            m < 60 -> "$m 分鐘前"
            else -> "${m / 60} 小時 ${m % 60} 分前"
        }
    }
    private fun rounded(color: Int, r: Int = 12, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }
    private fun button(text: String, bg: Int, fg: Int, sizeSp: Float = 16f): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = sizeSp
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(bg)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
        }
    private fun thumb(id: String): Bitmap? =
        thumbs.get(id) ?: store.photo(id, 4)?.also { thumbs.put(id, it) }
    /** Number of a record as the officer counts them: the first scooter of the day is 1. */
    private fun number(r: Rec): Int = store.records.size - store.records.indexOf(r)

    // ---------- UI ----------
    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(10), dp(4), dp(10), dp(6))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvCount = TextView(this).apply { textSize = 16f; setTextColor(cInk); typeface = Typeface.DEFAULT_BOLD }
        header.addView(tvCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnClear = button("清除", Color.WHITE, cNo, 14f).apply { minHeight = dp(40); minimumHeight = dp(40) }
        btnClear.setOnClickListener { confirmClear() }
        header.addView(btnClear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        root.addView(header)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMark = button("第一圈・記錄", Color.WHITE, cMuted, 15f)
        btnCheck = button("第二圈・巡查", Color.WHITE, cMuted, 15f)
        btnMark.setOnClickListener { setMode("mark") }
        btnCheck.setOnClickListener { setMode("check") }
        modeRow.addView(btnMark, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        modeRow.addView(btnCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val previewFrame = FrameLayout(this).apply { background = rounded(Color.BLACK, 12) }
        previewView = PreviewView(this)
        previewFrame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            pvW = r - l
            pvH = b - t
            val lp = FrameLayout.LayoutParams((pvW * gWidth).toInt(), (pvH * gHeight).toInt())
            lp.leftMargin = (pvW * gLeft).toInt()
            lp.topMargin = (pvH * gTop).toInt()
            val hole = RectF(lp.leftMargin.toFloat(), lp.topMargin.toFloat(), (lp.leftMargin + lp.width).toFloat(), (lp.topMargin + lp.height).toFloat())
            guide.post {
                guide.layoutParams = lp
                mask.hole = hole
                mask.invalidate()
            }
            if (pendingCameraStart && pvW > 0) {
                pendingCameraStart = false
                previewView.post { startCamera() }
            }
        }
        // Tapping the picture also takes the photo.
        previewView.setOnClickListener { shoot() }
        mask = MaskView(this)
        previewFrame.addView(mask, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        guide = View(this).apply { background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(3), Color.WHITE)
        } }
        previewFrame.addView(guide, FrameLayout.LayoutParams(dp(260), dp(120)))
        tvInfo = TextView(this).apply {
            text = "車尾放進白框，車牌對準黃色虛線框"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        previewFrame.addView(tvInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(6); bottomMargin = dp(6) })
        btnTorch = button("補光", Color.parseColor("#99000000"), Color.WHITE, 13f).apply {
            minHeight = dp(36); minimumHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
        }
        btnTorch.setOnClickListener { toggleTorch() }
        previewFrame.addView(btnTorch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.TOP or Gravity.END).apply { rightMargin = dp(6); topMargin = dp(6) })
        root.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.0f).apply { topMargin = dp(4) })

        btnShoot = button("拍照", cAccent, Color.WHITE, 20f)
        btnShoot.setOnClickListener { shoot() }
        root.addView(btnShoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(6) })

        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(6))
        }
        tvLabel = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        tvDetail = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER }
        actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        statusBox.addView(tvLabel)
        statusBox.addView(photoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        statusBox.addView(tvDetail)
        statusBox.addView(actionsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(statusBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // Today's scooters as photos, newest first.
        val grid = GridView(this).apply {
            background = rounded(Color.WHITE, 12)
            numColumns = 4
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        adapter = object : BaseAdapter() {
            override fun getCount(): Int = store.records.size
            override fun getItem(position: Int): Any = store.records[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                    addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
                    addView(TextView(this@MainActivity).apply {
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#99000000"))
                        setPadding(dp(4), 0, dp(4), 0)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                }
                val r = store.records[position]
                (cell.getChildAt(0) as ImageView).setImageBitmap(thumb(r.id))
                (cell.getChildAt(1) as TextView).text = "#" + number(r) + " " + hhmm(r.t)
                return cell
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val rec = store.records.getOrNull(pos) ?: return@setOnItemClickListener
            showRecordDialog(rec)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.0f).apply { topMargin = dp(6) })
        return root
    }

    private fun setMode(m: String) {
        mode = m
        updateModeUi()
        showIdle()
    }

    private fun updateModeUi() {
        fun style(b: Button, on: Boolean) {
            b.background = rounded(if (on) cAccent else Color.WHITE)
            b.setTextColor(if (on) Color.WHITE else cMuted)
        }
        style(btnMark, mode == "mark")
        style(btnCheck, mode == "check")
        btnShoot.text = if (mode == "mark") "拍照記錄" else "拍照查詢"
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        tvCount.text = store.date + " · 已開單 " + store.records.size + " 台"
    }

    private fun idleText(): String =
        if (mode == "mark") "開完一台，拍車尾（含車牌）就記錄為已開單。同一台不會記兩次。"
        else "拍車尾（含車牌），馬上顯示有沒有開過單。"

    private fun showIdle() = showStatus("idle", "準備就緒", idleText(), emptyList(), emptyList())

    /**
     * [photos] are shown side by side with a caption under each; tapping one calls its action if it has one.
     * The first of [actions] is the main choice.
     */
    private fun showStatus(
        kind: String, label: String, detail: String,
        photos: List<Triple<Bitmap?, String, (() -> Unit)?>>,
        actions: List<Pair<String, () -> Unit>>, sound: Boolean = true
    ) {
        val (bg, fg) = when (kind) {
            "ok" -> cOk to Color.WHITE
            "no" -> cNo to Color.WHITE
            "warn" -> cWarn to Color.parseColor("#201500")
            else -> Color.WHITE to cMuted
        }
        statusBox.background = rounded(bg, 16)
        tvLabel.text = label
        tvLabel.setTextColor(fg)
        tvDetail.text = detail
        tvDetail.setTextColor(fg)
        photoRow.removeAllViews()
        photoRow.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        // Several candidates must fit across the screen; one or two comparison photos get more room.
        val size = if (photos.size > 2) dp(76) else dp(120)
        photos.forEach { (bmp, caption, onTap) ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
                background = rounded(Color.BLACK, 6, if (onTap != null) Color.WHITE else null, 3)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                if (onTap != null) setOnClickListener { onTap() }
            }
            col.addView(iv, LinearLayout.LayoutParams(size, size))
            col.addView(TextView(this).apply { text = caption; textSize = 12f; setTextColor(fg); gravity = Gravity.CENTER })
            photoRow.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        actionsRow.removeAllViews()
        actions.forEachIndexed { i, a ->
            val b = button(a.first, if (i == 0) Color.WHITE else Color.TRANSPARENT, if (i == 0) Color.BLACK else fg, 15f)
            if (i != 0) b.background = rounded(Color.TRANSPARENT, 12, fg, 2)
            b.setOnClickListener { a.second() }
            actionsRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        if (kind != "idle" && sound) feedback(kind)
    }

    private fun feedback(kind: String) {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            when (kind) {
                "ok" -> tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                "no" -> tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                else -> tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
            }
        } catch (e: Exception) { }
        try {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(if (kind == "ok") 30 else 90, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    private fun showRecordDialog(rec: Rec) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(store.photo(rec.id))
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("#" + number(rec) + " · " + hhmm(rec.t) + " 開單")
            .setView(iv)
            .setPositiveButton("關閉", null)
            .setNegativeButton("刪除") { _, _ -> confirmRemove(rec) }
            .show()
    }

    private fun confirmRemove(rec: Rec) {
        AlertDialog.Builder(this)
            .setMessage("刪除 #" + number(rec) + "（" + hhmm(rec.t) + "）這台的記錄？")
            .setPositiveButton("刪除") { _, _ -> store.remove(rec); thumbs.remove(rec.id); refreshList(); showIdle() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- core flow ----------
    private fun mine(s: Shot): Triple<Bitmap?, String, (() -> Unit)?> = Triple(s.photo, "剛拍的", null)
    private fun theirs(r: Rec, onTap: (() -> Unit)? = null): Triple<Bitmap?, String, (() -> Unit)?> =
        Triple(thumb(r.id), "#" + number(r) + " " + hhmm(r.t), onTap)

    private fun record(s: Shot) {
        val r = store.add(s.photo, s.feat)
        refreshList()
        showStatus("ok", "已記錄", "今天第 " + store.records.size + " 台", listOf(theirs(r)),
            listOf("復原" to { store.remove(r); thumbs.remove(r.id); refreshList(); showIdle() }))
    }

    private fun showAlready(s: Shot, r: Rec) =
        showStatus("warn", "這台已記錄過", hhmm(r.t) + " 已記錄（#" + number(r) + "），沒有重複新增",
            listOf(mine(s), theirs(r)), listOf("不同台，新增" to { record(s) }))

    private fun showFound(s: Shot, r: Rec) =
        showStatus("ok", "已開單", hhmm(r.t) + " 開單 · " + ago(r.t) + "　請對照兩張照片的車牌",
            listOf(mine(s), theirs(r)), emptyList())

    private fun showMissing(s: Shot) =
        showStatus("no", "未開單", "今天沒有這台的記錄", listOf(mine(s)),
            listOf("標記為已開單" to { record(s) }))

    private fun shoot() {
        if (!cvReady || !cameraStarted) return
        if (busy) return
        if (store.date != todayStr()) { store.reset(todayStr()); thumbs.evictAll(); refreshList() }
        busy = true
        shotMode = mode
        shotRecs = store.records.toList()
        btnShoot.isEnabled = false
        btnShoot.text = "比對中…"
        wantShot = true
    }

    private fun shotDone() {
        busy = false
        btnShoot.isEnabled = true
        updateModeUi()
    }

    private fun onShot(s: Shot?, scores: Map<String, Int>, ms: Long) {
        shotDone()
        if (s == null) {
            showStatus("warn", "拍照失敗", "請再拍一次。", emptyList(), emptyList())
            return
        }
        val sorted = scores.values.sortedDescending()
        tvInfo.text = "比對 " + scores.size + " 台 · 最像 " + (sorted.getOrNull(0) ?: 0) +
            " / 次像 " + (sorted.getOrNull(1) ?: 0) + " · " + ms + " 毫秒"
        if (s.feat.n < Decide.MIN_FEATURES) {
            showStatus("warn", "照片看不清楚", "太暗、太模糊或太遠。靠近一點，必要時開補光再拍。", listOf(mine(s)), emptyList())
            return
        }
        // Records may have been removed while the photo was being compared.
        val v = Decide.decide(scores.filterKeys { store.find(it) != null })
        if (shotMode == "mark") {
            when (v) {
                is Decide.Verdict.None -> record(s)
                is Decide.Verdict.Same -> showAlready(s, store.find(v.id)!!)
                is Decide.Verdict.Maybe -> {
                    val r = store.find(v.id)!!
                    showStatus("warn", "可能已記錄過", "和 #" + number(r) + "（" + hhmm(r.t) + "）有點像，對照車牌，是同一台嗎？",
                        listOf(mine(s), theirs(r)),
                        listOf("同一台，不新增" to { showAlready(s, r) }, "不同台，新增" to { record(s) }))
                }
                is Decide.Verdict.Several -> {
                    val recs = v.ids.mapNotNull { store.find(it) }
                    showStatus("warn", "有 " + recs.size + " 台很像", "點選同一台的照片；都不是就新增",
                        listOf(mine(s)) + recs.map { r -> theirs(r) { showAlready(s, r) } },
                        listOf("都不是，新增" to { record(s) }))
                }
            }
        } else {
            when (v) {
                is Decide.Verdict.None -> showMissing(s)
                is Decide.Verdict.Same -> showFound(s, store.find(v.id)!!)
                is Decide.Verdict.Maybe -> {
                    val r = store.find(v.id)!!
                    showStatus("warn", "可能已開單", "和 #" + number(r) + "（" + hhmm(r.t) + "）有點像，對照車牌，是同一台嗎？",
                        listOf(mine(s), theirs(r)),
                        listOf("是，已開單" to { showFound(s, r) }, "不是，未開單" to { showMissing(s) }))
                }
                is Decide.Verdict.Several -> {
                    val recs = v.ids.mapNotNull { store.find(it) }
                    showStatus("warn", "有 " + recs.size + " 台很像", "點選同一台的照片；都不是就是未開單",
                        listOf(mine(s)) + recs.map { r -> theirs(r) { showFound(s, r) } },
                        listOf("都不是，未開單" to { showMissing(s) }))
                }
            }
        }
    }

    // ---------- camera ----------
    private fun startCamera() {
        if (cameraStarted) return
        if (pvW == 0) { pendingCameraStart = true; return }  // wait until the preview has its size
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio43)
                            .setResolutionStrategy(ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                // The view port makes the analysed image cover exactly what the preview shows.
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                previewView.viewPort?.let { group.setViewPort(it) }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                camera = cam
                btnTorch.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                cameraStarted = false
                showStatus("warn", "相機無法啟動", "請關閉其他使用相機的程式後重開。", emptyList(), emptyList())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnTorch.background = rounded(if (torchOn) cWarn else Color.parseColor("#99000000"))
        btnTorch.setTextColor(if (torchOn) Color.BLACK else Color.WHITE)
    }

    /**
     * Frames are dropped until the shutter is pressed. That frame's guide-frame area becomes the photo,
     * which is fingerprinted and compared with every scooter recorded today, all on the worker thread.
     */
    private fun analyze(proxy: ImageProxy) {
        if (!wantShot) {
            proxy.close()
            return
        }
        wantShot = false
        val started = SystemClock.elapsedRealtime()
        val rot = proxy.imageInfo.rotationDegrees
        var crop: Bitmap? = null
        try {
            val src = proxy.toBitmap()
            val r = guideRect(src, proxy, rot)
            crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height(), Matrix().apply { postRotate(rot.toFloat()) }, true)
        } catch (e: Exception) {
            crop = null
        } finally {
            proxy.close()
        }
        var shot: Shot? = null
        val scores = HashMap<String, Int>()
        try {
            if (crop != null) {
                val photo = Matcher.shrink(crop)
                val f = Matcher.extract(photo)
                shot = Shot(photo, f)
                if (f.n >= Decide.MIN_FEATURES) {
                    for (rec in shotRecs) {
                        val rf = store.feat(rec.id) ?: continue
                        scores[rec.id] = Matcher.score(f, rf)
                    }
                }
            }
        } catch (e: Exception) {
            shot = null
        }
        val ms = SystemClock.elapsedRealtime() - started
        runOnUiThread { onShot(shot, scores, ms) }
    }

    /** The guide frame mapped into the (unrotated) camera bitmap. */
    private fun guideRect(src: Bitmap, proxy: ImageProxy, rot: Int): Rect {
        // Visible part of the camera image. toBitmap() may or may not have applied the crop already.
        val vis = if (src.width == proxy.width && src.height == proxy.height) Rect(proxy.cropRect) else Rect(0, 0, src.width, src.height)
        val visW = vis.width().toFloat()
        val visH = vis.height().toFloat()
        val upright = rot == 90 || rot == 270
        val w = if (upright) visH else visW
        val h = if (upright) visW else visH
        val vw = pvW.toFloat()
        val vh = pvH.toFloat()
        val rf = if (vw > 0f && vh > 0f) {
            // Preview fills the view and centre-crops; with a view port the two have the same shape.
            val sc = max(vw / w, vh / h)
            RectF(
                (vw * gLeft - vw / 2f) / sc + w / 2f,
                (vh * gTop - vh / 2f) / sc + h / 2f,
                (vw * (gLeft + gWidth) - vw / 2f) / sc + w / 2f,
                (vh * (gTop + gHeight) - vh / 2f) / sc + h / 2f
            )
        } else RectF(w * 0.1f, h * 0.05f, w * 0.9f, h * 0.95f)
        // Undo the rotation: upright coordinates -> bitmap coordinates.
        val m = Matrix()
        m.postRotate(rot.toFloat())
        val b = RectF(0f, 0f, visW, visH)
        m.mapRect(b)
        m.postTranslate(-b.left, -b.top)
        val inv = Matrix()
        m.invert(inv)
        inv.mapRect(rf)
        val out = Rect(
            (vis.left + rf.left).toInt().coerceIn(0, src.width - 1),
            (vis.top + rf.top).toInt().coerceIn(0, src.height - 1),
            (vis.left + rf.right).toInt().coerceIn(1, src.width),
            (vis.top + rf.bottom).toInt().coerceIn(1, src.height)
        )
        if (out.width() < 50 || out.height() < 50) return Rect(0, 0, src.width, src.height)
        return out
    }

    // ---------- menu actions ----------
    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("清除今天全部 " + store.records.size + " 筆記錄和照片？")
            .setPositiveButton("清除") { _, _ -> store.reset(todayStr()); thumbs.evictAll(); refreshList(); showIdle() }
            .setNegativeButton("取消", null)
            .show()
    }
}
