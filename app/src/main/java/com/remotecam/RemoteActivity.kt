package com.remotecam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The REMOTE phone: live view of the camera phone + controls. */
class RemoteActivity : ComponentActivity() {
    private lateinit var iv: ImageView
    private lateinit var connectPanel: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var countdown: TextView
    private lateinit var recTime: TextView
    private lateinit var expBar: SeekBar
    private lateinit var shutter: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnVideo: Button
    private var expMin = 0
    private var sock: Socket? = null
    private var w: PrintWriter? = null
    private var bmpW = 1
    private var bmpH = 1
    private var torch = false
    private lateinit var fpsView: TextView
    private val frameRef = AtomicReference<ByteArray?>()   // newest undecoded frame (older ones are dropped)
    private val frameSignal = Semaphore(0)
    private val pending = AtomicReference<Bitmap?>()
    private val frames = AtomicInteger()
    private var curMode = "PHOTO"
    private var recording = false
    private var recStart = 0L
    private val netExec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            recTime.text = "● %02d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun cmd(s: String) = netExec.execute { try { w?.println(s) } catch (_: Exception) {} }
    private fun primary() = if (curMode == "PHOTO") cmd("SHOT") else cmd("REC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        iv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP && controls.visibility == View.VISIBLE) {
                val inv = Matrix(); iv.imageMatrix.invert(inv)
                val pt = floatArrayOf(e.x, e.y); inv.mapPoints(pt)
                val nx = pt[0] / bmpW; val ny = pt[1] / bmpH
                if (nx in 0f..1f && ny in 0f..1f) cmd("FOCUS $nx $ny")
            }
            true
        }
        root.addView(iv)

        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val scan = registerForActivityResult(ScanContract()) { r ->
            val c = r.contents
            if (c != null) {
                val p = c.split(":")
                if (p.size == 3 && p[0] == "remotecam") saveAndConnect(p[1], p[2].toIntOrNull() ?: PORT)
                else Toast.makeText(this, "Not a RemoteCam QR code", Toast.LENGTH_LONG).show()
            }
        }
        val scanBtn = Button(this).apply {
            text = "📷  Scan QR to connect"
            setOnClickListener {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR shown on the camera phone").setBeepEnabled(false).setOrientationLocked(false))
            }
        }
        val last = prefs.getString("ip", "") ?: ""
        val lastBtn = Button(this).apply {
            text = "Reconnect to $last"
            visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { connect(last, PORT) }
        }
        val ip = EditText(this).apply {
            hint = "or type IP manually"
            setText(last); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val go = Button(this).apply { text = "Connect manually" }
        go.setOnClickListener { saveAndConnect(ip.text.toString().trim(), PORT) }
        connectPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(this@RemoteActivity).apply { text = "On the other phone open RemoteCam → CAMERA, then scan its QR code."; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(16)) })
            addView(scanBtn); addView(lastBtn); addView(ip); addView(go)
        }
        root.addView(connectPanel, FrameLayout.LayoutParams(-1, -1))

        countdown = TextView(this).apply { textSize = 90f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(countdown, FrameLayout.LayoutParams(-1, -1))
        recTime = TextView(this).apply {
            textSize = 20f; setTextColor(Color.RED); setBackgroundColor(0x88000000.toInt()); setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
        }
        root.addView(recTime, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) })
        fpsView = TextView(this).apply { textSize = 12f; setTextColor(Color.GREEN); setBackgroundColor(0x88000000.toInt()); setPadding(dp(8), dp(2), dp(8), dp(2)) }
        root.addView(fpsView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(40); leftMargin = dp(8) })
        handler.post(object : Runnable {
            override fun run() { val n = frames.getAndSet(0); fpsView.text = if (n > 0) "$n fps" else ""; handler.postDelayed(this, 1000) }
        })
        // decoder thread: always decodes only the NEWEST frame, so the picture never lags behind
        thread(isDaemon = true) {
            while (!isDestroyed) {
                if (!frameSignal.tryAcquire(500, TimeUnit.MILLISECONDS)) continue
                val b = frameRef.getAndSet(null) ?: continue
                try {
                    var bmp = BitmapFactory.decodeByteArray(b, 1, b.size - 1) ?: continue
                    val rot = (b[0].toInt() and 3) * 90
                    if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    bmpW = bmp.width; bmpH = bmp.height
                    frames.incrementAndGet()
                    if (pending.getAndSet(bmp) == null) ui { pending.getAndSet(null)?.let { iv.setImageBitmap(it) } }
                } catch (_: Exception) {}
            }
        }

        val zoom = SeekBar(this).apply { max = 1000 }
        zoom.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) cmd("ZOOM ${p / 1000f}") }
        })
        expBar = SeekBar(this).apply { max = 0 }
        expBar.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) cmd("EXP ${p + expMin}") }
        })
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnPhoto = btn("PHOTO") { cmd("MODE PHOTO") }
        btnVideo = btn("VIDEO") { cmd("MODE VIDEO") }
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(btnPhoto); addView(btnVideo) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Torch") { torch = !torch; cmd("TORCH ${if (torch) 1 else 0}") })
            addView(btn("Flip") { cmd("SWITCH") })
            addView(btn("3s") { timer(3) })
            addView(btn("10s") { timer(10) })
        }
        shutter = Button(this).apply { text = "📷  CAPTURE"; setOnClickListener { primary() } }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setBackgroundColor(0xAA000000.toInt()); setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(zoomRow)
            addView(label("Exposure (brightness)")); addView(expBar)
            addView(label("Fine zoom")); addView(zoom)
            addView(modeRow); addView(row); addView(shutter)
        }
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        refreshMode()
    }

    private fun refreshMode() {
        btnPhoto.alpha = if (curMode == "PHOTO") 1f else 0.45f
        btnVideo.alpha = if (curMode == "VIDEO") 1f else 0.45f
        shutter.text = when {
            curMode == "PHOTO" -> "📷  CAPTURE"
            recording -> "■  STOP"
            else -> "●  RECORD"
        }
        handler.removeCallbacks(tick)
        if (recording) { recStart = SystemClock.elapsedRealtime(); recTime.visibility = View.VISIBLE; handler.post(tick) }
        else recTime.visibility = View.GONE
    }

    private fun timer(n: Int) {
        countdown.visibility = View.VISIBLE
        for (i in 0..n) handler.postDelayed({
            if (i < n) countdown.text = "${n - i}" else { countdown.visibility = View.GONE; primary() }
        }, i * 1000L)
    }

    private fun buildPresets(zmin: Float, zmax: Float) {
        zoomRow.removeAllViews()
        for (z in listOf(0.5f, 1f, 2f, 3f, 5f, 10f)) if (z >= zmin - 0.01f && z <= zmax + 0.01f) {
            zoomRow.addView(Button(this).apply {
                text = if (z < 1f) ".5" else "${z.toInt()}×"
                setOnClickListener { cmd("ZR $z") }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun saveAndConnect(host: String, port: Int) {
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("ip", host).apply()
        connect(host, port)
    }

    private fun connect(host: String, port: Int = PORT) {
        Toast.makeText(this, "Connecting…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val s = Socket(); s.receiveBufferSize = 128 * 1024; s.connect(InetSocketAddress(host, port), 4000); s.tcpNoDelay = true
                sock = s; w = PrintWriter(s.getOutputStream(), true)
                ui { connectPanel.visibility = View.GONE; controls.visibility = View.VISIBLE }
                val d = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 17))
                while (true) {
                    val t = d.readUnsignedByte(); val n = d.readInt()
                    val buf = ByteArray(n); d.readFully(buf)
                    if (t == 2) { if (frameRef.getAndSet(buf) == null) frameSignal.release() }
                    else onText(String(buf))
                }
            } catch (e: Exception) {
                ui {
                    Toast.makeText(this, "Disconnected: ${e.message}", Toast.LENGTH_LONG).show()
                    connectPanel.visibility = View.VISIBLE; controls.visibility = View.GONE
                }
            }
        }
    }

    private fun onText(s: String) = ui {
        val p = s.split(" ")
        when {
            p[0] == "INFO" -> {
                expMin = p[1].toInt(); expBar.max = p[2].toInt() - expMin; expBar.progress = -expMin
                buildPresets(p[3].toFloat(), p[4].toFloat())
            }
            p[0] == "STATE" -> { curMode = p[1]; recording = p[2] == "1"; refreshMode() }
            s.startsWith("MSG ") -> Toast.makeText(this, s.substring(4), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy(); handler.removeCallbacksAndMessages(null)
        try { sock?.close() } catch (_: Exception) {}; netExec.shutdown()
    }
}

open class SimpleSeek : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {}
    override fun onStartTrackingTouch(s: SeekBar) {}
    override fun onStopTrackingTouch(s: SeekBar) {}
}
