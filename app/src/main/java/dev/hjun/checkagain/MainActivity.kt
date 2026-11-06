package dev.hjun.checkagain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "두고 내리지 않기"
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            // 개발자용 도구는 제목을 길게 눌러야 열린다.
            setOnLongClickListener {
                startActivity(Intent(this@MainActivity, ProbeActivity::class.java))
                true
            }
        }
        status = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("시작") { start(false) }, weighted())
            addView(button("종료") { stop() }, weighted())
            addView(button("목록 비우고 시작") { start(true) }, weighted())
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
        }
        scroll = ScrollView(this).apply { addView(logView) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(title)
            addView(status)
            addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        ensurePermissions()
    }

    override fun onStart() {
        super.onStart()
        if (logView.text.isEmpty()) AutoService.replay().takeLast(80).forEach { appendUi(it) }
        AutoService.sink = ::appendUi
        refreshStatus()
    }

    override fun onStop() {
        AutoService.sink = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (audioGranted() && !AutoService.running) start(false)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun audioGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // 마이크와 알림 권한을 함께 요청한다. 둘 중 하나만 없어도 요청한다.
    private fun ensurePermissions() {
        val missing = mutableListOf<String>()
        if (!audioGranted()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) {
            if (!AutoService.running) start(false)
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    private fun start(reset: Boolean) {
        if (!audioGranted()) {
            ensurePermissions()
            return
        }
        AutoService.start(applicationContext, reset)
        refreshStatus()
    }

    private fun stop() {
        AutoService.stop(applicationContext)
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (AutoService.running) "감시 중" else "중지됨"
        status.postDelayed({ status.text = if (AutoService.running) "감시 중" else "중지됨" }, 700)
    }

    private fun appendUi(message: String) {
        runOnUiThread {
            logView.append(message + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
