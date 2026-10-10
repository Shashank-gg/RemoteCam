package com.remotecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** The CAMERA phone: runs the camera, streams a 30fps preview, obeys commands, saves photos/videos. */
class HostActivity : ComponentActivity() {
    private lateinit var pv: PreviewView
    private lateinit var status: TextView
    private lateinit var qrBox: LinearLayout
    private lateinit var qrImg: ImageView
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var videoCap: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var mode = "PHOTO"
    private var audioOk = false
    private var lens = CameraSelector.LENS_FACING_BACK
    @Volatile private var out: DataOutputStream? = null
    private var server: ServerSocket? = null
    private var lastSent = 0L
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val netExec = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pv = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); setPadding(24, 24, 24, 24)
            text = "Starting…"
        }
        val root = FrameLayout(this)
        root.addView(pv)
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        qrImg = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(24, 24, 24, 24) }
        qrBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xCC000000.toInt()); setPadding(32, 32, 32, 32)
            addView(TextView(this@HostActivity).apply { text = "Scan this QR with the REMOTE phone"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 24) })
            addView(qrImg, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.8f).toInt(), (resources.displayMetrics.widthPixels * 0.8f).toInt()))
        }
        root.addView(qrBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 29) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val req = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.CAMERA] == true) start()
            else { Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show(); finish() }
        }
        val need = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) start() else req.launch(need.toTypedArray())
    }

    private fun start() {
        audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind(true); startServer() }, ContextCompat.getMainExecutor(this))
    }

    private fun sel169(max: Size?): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            if (max != null) ResolutionStrategy(max, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            else ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
        ).build()

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bind(useFps: Boolean) {
        val p = provider ?: return
        p.unbindAll()
        val fps = Range(30, 30)

        val pb = Preview.Builder().setResolutionSelector(sel169(Size(1920, 1080)))
        val ab = ImageAnalysis.Builder().setResolutionSelector(sel169(Size(1280, 720)))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (useFps) {
            Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            Camera2Interop.Extender(ab).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        }
        val preview = pb.build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        val analysis = ab.build().also { it.setAnalyzer(analysisExec) { img -> stream(img) } }

        val cases = mutableListOf<UseCase>(preview, analysis)
        if (mode == "PHOTO") {
            capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(sel169(null)).build()
            videoCap = null
            cases.add(capture!!)
        } else {
            val rec = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
            videoCap = VideoCapture.withOutput(rec)
            capture = null
            cases.add(videoCap!!)
        }
        try {
            camera = p.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lens).build(), *cases.toTypedArray())
        } catch (e: Exception) {
            if (useFps) { bind(false); return }
            sendText("MSG Camera error: ${e.message}")
            return
        }
        info()
    }

    private fun info() {
        val c = camera ?: return
        val r = c.cameraInfo.exposureState.exposureCompensationRange
        val z = c.cameraInfo.zoomState.value
        sendText("INFO ${r.lower} ${r.upper} ${z?.minZoomRatio ?: 1f} ${z?.maxZoomRatio ?: 1f}")
        sendText("STATE $mode ${if (recording != null) 1 else 0}")
    }

    private fun stream(img: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (out == null || now - lastSent < 30) { img.close(); return }
        lastSent = now
        try {
            val bmp = img.toBitmap()
            val m = Matrix().apply { postRotate(img.imageInfo.rotationDegrees.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val bos = ByteArrayOutputStream()
            r.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            send(0, bos.toByteArray())
        } catch (_: Exception) {
        } finally { img.close() }
    }

    @Synchronized private fun send(type: Int, d: ByteArray) {
        try { out?.run { writeByte(type); writeInt(d.size); write(d); flush() } } catch (_: IOException) { out = null }
    }
    private fun sendText(s: String) = netExec.execute { send(1, s.toByteArray()) }

    private fun startServer() {
        showQr()
        thread {
            try {
                server = ServerSocket(PORT)
                while (true) {
                    val s = server!!.accept()
                    s.tcpNoDelay = true
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))
                    runOnUiThread { status.text = "Remote connected ✔"; qrBox.visibility = View.GONE; info() }
                    try {
                        BufferedReader(InputStreamReader(s.getInputStream())).forEachLine { l -> runOnUiThread { handle(l) } }
                    } catch (_: Exception) {}
                    out = null
                    runOnUiThread { showQr() }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showQr() {
        val ip = localIp()
        status.text = "Waiting for remote…  ($ip:$PORT)"
        try { qrImg.setImageBitmap(BarcodeEncoder().encodeBitmap("remotecam:$ip:$PORT", BarcodeFormat.QR_CODE, 700, 700)) } catch (_: Exception) {}
        qrBox.visibility = View.VISIBLE
    }

    private fun handle(line: String) {
        val p = line.trim().split(" ")
        val c = camera ?: return
        try {
            when (p[0]) {
                "ZOOM" -> c.cameraControl.setLinearZoom(p[1].toFloat())
                "ZR" -> c.cameraControl.setZoomRatio(p[1].toFloat())
                "EXP" -> c.cameraControl.setExposureCompensationIndex(p[1].toInt())
                "TORCH" -> c.cameraControl.enableTorch(p[1] == "1")
                "SWITCH" -> if (recording == null) {
                    lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    bind(true)
                }
                "MODE" -> if (recording == null && p[1] != mode) { mode = p[1]; bind(true) }
                "FOCUS" -> {
                    val pt = pv.meteringPointFactory.createPoint(p[1].toFloat() * pv.width, p[2].toFloat() * pv.height)
                    c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                }
                "SHOT" -> shoot()
                "REC" -> toggleRec()
            }
        } catch (_: Exception) {}
    }

    private fun shoot() {
        val cap = capture ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "RemoteCam_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RemoteCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { sendText("MSG Photo saved on camera phone") }
            override fun onError(e: ImageCaptureException) { sendText("MSG Error: ${e.message}") }
        })
    }

    @SuppressLint("MissingPermission")
    private fun toggleRec() {
        val r = recording
        if (r != null) { r.stop(); return }
        val vc = videoCap ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "RemoteCam_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RemoteCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pr = vc.output.prepareRecording(this, opts)
        if (audioOk) pr = pr.withAudioEnabled()
        recording = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> sendText("STATE VIDEO 1")
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    sendText("STATE VIDEO 0")
                    sendText(if (ev.hasError()) "MSG Video error ${ev.error}" else "MSG Video saved on camera phone")
                }
                else -> {}
            }
        }
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "unknown"
    } catch (_: Exception) { "unknown" }

    override fun onDestroy() {
        super.onDestroy()
        try { recording?.stop() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        analysisExec.shutdown(); netExec.shutdown()
    }
}
