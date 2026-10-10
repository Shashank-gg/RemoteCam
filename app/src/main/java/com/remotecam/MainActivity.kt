package com.remotecam

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

const val PORT = 8988

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#111111"))
        }
        fun label(t: String, size: Float) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 20, 0, 20)
        }
        fun btn(t: String, c: Class<*>) = Button(this).apply {
            text = t; setOnClickListener { startActivity(Intent(this@MainActivity, c)) }
        }
        col.addView(label("RemoteCam", 30f))
        col.addView(label("Install on both phones. Connect both to the same Wi-Fi (or one phone's hotspot).", 14f))
        col.addView(btn("This phone = CAMERA (the one you pose in front of)", HostActivity::class.java))
        col.addView(btn("This phone = REMOTE (viewfinder + controls)", RemoteActivity::class.java))
        setContentView(col)
    }
}
