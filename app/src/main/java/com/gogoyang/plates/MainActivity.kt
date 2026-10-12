package com.gogoyang.plates

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    // Guide frame as a share of the preview: wide, tall enough for two-line plates, sitting in the upper part.
    // The area read by the recognizer is computed from this same frame, so they always match.
    private val gLeft = 0.04f
    private val gWidth = 0.92f
    private val gTop = 0.06f
    private val gHeight = 0.54f
    @Volatile private var pvW = 0
    @Volatile private var pvH = 0

    private lateinit var store: Store
    private lateinit var learn: Learn
    private lateinit var previewView: PreviewView
    private lateinit var guide: View
    private lateinit var statusBox: LinearLayout
    private lateinit var tvLabel: TextView
    private lateinit var tvPlate: TextView
    private lateinit var tvDetail: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var etPlate: EditText
    private lateinit var tvHint: TextView
    private lateinit var tvCount: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var btnMark: Button
    private lateinit var btnCheck: Button
    private lateinit var btnScan: Button

    private var mode = "mark"
    private var scanOn = true
    private var awaiting = false
    private var lastRaw = ""
    private val reads = ArrayList<Pair<Long, String>>()
    private var lastAccepted = ""
    private var lastAcceptedTime = 0L
    private var lastAnalyze = 0L
    private var cameraStarted = false

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private var tone: ToneGenerator? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            showStatus("warn", "沒有相機權限", "", "請到手機設定允許相機，或直接輸入車牌。", emptyList(), false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        store.load(todayStr())
        learn = Learn(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        refreshList()
        updateModeUi()
        showIdle()
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
            refreshList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        executor.shutdown()
        tone?.release()
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

    // ---------- UI ----------
    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "車牌開單查詢"; textSize = 18f; setTextColor(cInk); typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvCount = TextView(this).apply { textSize = 14f; setTextColor(cMuted) }
        header.addView(tvCount)
        val btnExport = button("匯出", Color.WHITE, cInk, 14f).apply { minHeight = dp(40); minimumHeight = dp(40) }
        btnExport.setOnClickListener { exportList() }
        val btnClear = button("清除", Color.WHITE, cNo, 14f).apply { minHeight = dp(40); minimumHeight = dp(40) }
        btnClear.setOnClickListener { confirmClear() }
        val hp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) }
        header.addView(btnExport, hp)
        header.addView(btnClear, LinearLayout.LayoutParams(hp))
        root.addView(header)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMark = button("第一圈・記錄", Color.WHITE, cMuted, 15f)
        btnCheck = button("第二圈・巡查", Color.WHITE, cMuted, 15f)
        btnScan = button("鏡頭：開", Color.WHITE, cMuted, 15f)
        btnMark.setOnClickListener { setMode("mark") }
        btnCheck.setOnClickListener { setMode("check") }
        btnScan.setOnClickListener {
            scanOn = !scanOn
            updateModeUi()
        }
        val mp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } }
        modeRow.addView(btnMark, mp())
        modeRow.addView(btnCheck, mp())
        modeRow.addView(btnScan, mp())
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val previewFrame = FrameLayout(this).apply { background = rounded(Color.BLACK, 12) }
        previewView = PreviewView(this)
        previewFrame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            pvW = r - l
            pvH = b - t
            val lp = FrameLayout.LayoutParams((pvW * gWidth).toInt(), (pvH * gHeight).toInt())
            lp.leftMargin = (pvW * gLeft).toInt()
            lp.topMargin = (pvH * gTop).toInt()
            guide.post { guide.layoutParams = lp }
        }
        guide = View(this).apply { background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(3), Color.WHITE)
        } }
        previewFrame.addView(guide, FrameLayout.LayoutParams(dp(260), dp(120)))
        val guideText = TextView(this).apply {
            text = "車牌放進框內，離近一點"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        previewFrame.addView(guideText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        root.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.2f).apply { topMargin = dp(8) })

        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        tvLabel = TextView(this).apply { textSize = 26f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        tvPlate = TextView(this).apply {
            textSize = 40f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.BLACK); gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 8, Color.BLACK, 3)
            setPadding(dp(16), dp(2), dp(16), dp(2))
            letterSpacing = 0.05f
        }
        tvDetail = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        statusBox.addView(tvLabel)
        statusBox.addView(tvPlate, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        statusBox.addView(tvDetail)
        statusBox.addView(actionsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(statusBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val entry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etPlate = EditText(this).apply {
            hint = "輸入車牌"
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(cInk)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(Color.WHITE, 12, Color.parseColor("#C6CFD8"), 2)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        etPlate.setOnEditorActionListener { _, _, _ -> submit(etPlate.text.toString()); true }
        etPlate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateHint() }
        })
        val btnGo = button("送出", cAccent, Color.WHITE)
        btnGo.setOnClickListener { submit(etPlate.text.toString()) }
        entry.addView(etPlate, LinearLayout.LayoutParams(0, dp(56), 1f))
        entry.addView(btnGo, LinearLayout.LayoutParams(dp(84), dp(56)).apply { leftMargin = dp(8) })
        root.addView(entry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        tvHint = TextView(this).apply { textSize = 14f; setTextColor(cMuted); setPadding(dp(4), dp(4), dp(4), 0) }
        root.addView(tvHint)

        val listView = ListView(this).apply {
            background = rounded(Color.WHITE, 12)
            divider = null
        }
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 22f
                v.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                v.setTextColor(cInk)
                return v
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val rec = store.records.getOrNull(pos) ?: return@setOnItemClickListener
            AlertDialog.Builder(this)
                .setTitle(PlateLogic.format(rec.plate))
                .setItems(arrayOf("修改車牌", "刪除", "取消")) { _, which ->
                    when (which) {
                        0 -> askCorrect(rec.plate, rec.raw) { truth -> store.replace(rec, truth); refreshList() }
                        1 -> { store.remove(rec); refreshList() }
                    }
                }
                .show()
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.6f).apply { topMargin = dp(6) })
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
        btnScan.text = if (scanOn) "鏡頭：開" else "鏡頭：關"
        style(btnScan, scanOn)
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(store.records.map { PlateLogic.format(it.plate) + "      " + hhmm(it.t) })
        tvCount.text = store.date + " · 已開單 " + store.records.size + " 台  "
    }

    private fun updateHint() {
        val q = PlateLogic.normalize(etPlate.text.toString())
        val hits = PlateLogic.search(q, store.records)
        tvHint.text = if (hits.isEmpty()) "" else "已開單中符合：" + hits.take(6).joinToString("、") { PlateLogic.format(it.plate) }
    }

    private fun idleText(): String =
        if (mode == "mark") "開完一台，對準車牌或輸入車牌，就記錄為已開單。"
        else "對準車牌或輸入車牌，馬上顯示有沒有開過單。"

    private fun showIdle() {
        lastRaw = ""
        showStatus("idle", "準備就緒", "", idleText(), emptyList(), false)
    }

    private fun showStatus(kind: String, label: String, plate: String, detail: String, actions: List<Pair<String, () -> Unit>>, hold: Boolean) {
        awaiting = hold
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
        tvPlate.visibility = if (plate.isEmpty()) View.GONE else View.VISIBLE
        tvPlate.text = PlateLogic.format(plate)

        // Plates read by the camera can be corrected; the correction is remembered.
        val shown = actions.toMutableList()
        if (plate.isNotEmpty() && lastRaw.isNotEmpty() && !hold) {
            val fix: Pair<String, () -> Unit> = "更正車牌" to { correctDisplayed(plate) }
            shown.add(fix)
        }
        actionsRow.removeAllViews()
        shown.forEachIndexed { i, a ->
            val b = button(a.first, if (i == 0) Color.WHITE else Color.TRANSPARENT, if (i == 0) Color.BLACK else fg, 15f)
            if (i != 0) b.background = rounded(Color.TRANSPARENT, 12, fg, 2)
            b.setOnClickListener { a.second() }
            actionsRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        if (kind != "idle") feedback(kind)
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

    // ---------- corrections ----------
    private fun askCorrect(current: String, raw: String, onDone: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("更正車牌")
            .setMessage("改成實車上的車牌。這次更正會被記住，下次同樣讀錯會自動改正。")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val truth = PlateLogic.normalize(et.text.toString())
                if (truth.length < 3) {
                    Toast.makeText(this, "請輸入至少 3 碼車牌", Toast.LENGTH_SHORT).show()
                } else {
                    if (raw.isNotEmpty() && raw != truth) learn.teach(raw, truth)
                    onDone(truth)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun correctDisplayed(shown: String) {
        val raw = lastRaw
        askCorrect(shown, raw) { truth ->
            if (mode == "mark") {
                val rec = store.records.firstOrNull { it.plate == shown && it.raw == raw }
                if (rec != null) {
                    store.replace(rec, truth)
                    refreshList()
                    lastRaw = ""
                    showStatus("ok", "已更正", truth, "已改成這個車牌，下次同樣讀錯會自動改正", emptyList(), false)
                } else {
                    lastRaw = ""
                    submit(truth)
                }
            } else {
                lastRaw = ""
                submit(truth)
            }
        }
    }

    // ---------- core flow ----------
    private fun showFound(r: Rec) =
        showStatus("ok", "已開單", r.plate, hhmm(r.t) + " 開單 · " + ago(r.t), emptyList(), false)

    private fun showRecorded(r: Rec) =
        showStatus("ok", "已記錄", r.plate, "今天第 " + store.records.size + " 台",
            listOf("復原" to { store.remove(r); refreshList(); showIdle() }), false)

    private fun showMissing(n: String) {
        val rr = lastRaw
        showStatus("no", "未開單", n, "今天沒有這台的記錄",
            listOf("標記為已開單" to { val r = store.add(n, rr); refreshList(); showRecorded(r) }), false)
    }

    /** [readRaw] is what the camera read; empty for typed entries. */
    private fun submit(raw: String, readRaw: String = "") {
        if (store.date != todayStr()) { store.reset(todayStr()); refreshList() }
        val n = PlateLogic.normalize(raw)
        if (n.length < 3) {
            Toast.makeText(this, "請輸入至少 3 碼車牌", Toast.LENGTH_SHORT).show()
            return
        }
        lastRaw = readRaw
        etPlate.setText("")
        val exact = store.find(n)
        val near = if (exact == null) PlateLogic.findNear(n, store.records) else emptyList()
        if (mode == "mark") {
            when {
                exact != null -> showStatus("warn", "這台已記錄過", n, hhmm(exact.t) + " 已記錄，沒有重複新增", emptyList(), false)
                near.isNotEmpty() -> showStatus("warn", "有相似車牌", n,
                    "已記錄的 " + PlateLogic.format(near[0].plate) + "（" + hhmm(near[0].t) + "）很像，是同一台嗎？",
                    listOf("不同台，新增" to { val r = store.add(n, readRaw); refreshList(); showRecorded(r) },
                        "同一台，不新增" to { showFound(near[0]) }), true)
                else -> { val r = store.add(n, readRaw); refreshList(); showRecorded(r) }
            }
        } else {
            when {
                exact != null -> showFound(exact)
                near.isNotEmpty() -> showStatus("warn", "有相似車牌", n,
                    "已開單的 " + PlateLogic.format(near[0].plate) + "（" + hhmm(near[0].t) + "）很像，是這台嗎？",
                    listOf("是這台，已開單" to { showFound(near[0]) },
                        "不是，未開單" to { showMissing(n) }), true)
                else -> showMissing(n)
            }
        }
    }

    // ---------- camera ----------
    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1920, 1080))
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                cameraStarted = false
                showStatus("warn", "相機無法啟動", "", "請直接輸入車牌。", emptyList(), false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Read only the strip inside the guide frame, at the camera's full resolution. */
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!scanOn || now - lastAnalyze < 450) {
            proxy.close()
            return
        }
        lastAnalyze = now
        var crop: Bitmap? = null
        try {
            val src = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val imgW = rotated.width
            val imgH = rotated.height
            var left = imgW / 10
            var top = imgH * 3 / 10
            var right = imgW * 9 / 10
            var bottom = imgH * 6 / 10
            val vw = pvW
            val vh = pvH
            if (vw > 0 && vh > 0) {
                // Map the guide frame (preview pixels) back onto the camera image (the preview fills and centre-crops).
                val scale = max(vw.toFloat() / imgW, vh.toFloat() / imgH)
                left = (((vw * gLeft) - vw / 2f) / scale + imgW / 2f).toInt()
                right = (((vw * (gLeft + gWidth)) - vw / 2f) / scale + imgW / 2f).toInt()
                top = (((vh * gTop) - vh / 2f) / scale + imgH / 2f).toInt()
                bottom = (((vh * (gTop + gHeight)) - vh / 2f) / scale + imgH / 2f).toInt()
            }
            left = max(0, left)
            top = max(0, top)
            right = minOf(imgW, right)
            bottom = minOf(imgH, bottom)
            crop = if (right - left < 50 || bottom - top < 50) rotated else Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            crop = null
        } finally {
            proxy.close()
        }
        val bmp = crop ?: return
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val p = PlateLogic.extract(r.text)
                if (p != null) runOnUiThread { onSeen(p) }
            }
    }

    /** Take a plate only when it clearly wins the vote among the last few seconds of reads. */
    private fun onSeen(p: String) {
        if (awaiting) return
        val now = SystemClock.elapsedRealtime()
        reads.add(now to p)
        reads.removeAll { now - it.first > 3000 }
        val counts = reads.groupingBy { it.second }.eachCount()
        val top = counts.maxByOrNull { it.value } ?: return
        val second = counts.filter { it.key != top.key }.values.maxOrNull() ?: 0
        if (top.value < 2 || (top.value < 3 && second > 0)) return
        val plate = top.key
        reads.clear()
        if (plate == lastAccepted && now - lastAcceptedTime < 5000) {
            lastAcceptedTime = now
            return
        }
        lastAccepted = plate
        lastAcceptedTime = now
        submit(learn.fix(plate), plate)
    }

    // ---------- menu actions ----------
    private fun exportList() {
        if (store.records.isEmpty()) { Toast.makeText(this, "今天還沒有記錄", Toast.LENGTH_SHORT).show(); return }
        val sb = StringBuilder("車牌,記錄時間\n")
        store.records.reversed().forEach { sb.append(PlateLogic.format(it.plate)).append(',').append(store.date).append(' ').append(hhmm(it.t)).append('\n') }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(i, "匯出今日清單"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("清除今天全部 " + store.records.size + " 筆記錄？")
            .setPositiveButton("清除") { _, _ -> store.reset(todayStr()); refreshList(); showIdle() }
            .setNegativeButton("取消", null)
            .show()
    }
}
