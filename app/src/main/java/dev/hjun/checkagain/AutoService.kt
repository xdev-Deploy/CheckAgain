package dev.hjun.checkagain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class AutoService : Service() {

    private var flow: AutoFlow? = null
    private var noticeSeq = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "자동 모드", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTICE, "물건 확인", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            push("포그라운드 서비스 시작 실패, 마이크 권한을 확인하세요")
            stopSelf()
            return START_NOT_STICKY
        }
        val reset = intent?.getBooleanExtra(EXTRA_RESET, false) == true
        if (reset) {
            flow?.stop()
            flow = null
        }
        if (flow == null) {
            flow = AutoFlow(applicationContext, { m -> push(m) }, { t -> postNotice(t) }).also { it.start(reset) }
            current = flow
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        flow?.stop()
        flow = null
        current = null
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun openApp(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun enterForeground(): Boolean {
        val notification = Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("두고 내리지 않기")
            .setContentText("문과 좌석을 감시하는 중")
            .setContentIntent(openApp(0))
            .setOngoing(true)
            .build()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ONGOING_ID, notification)
            }
        }.onFailure { Log.e(TAG, "startForeground 실패", it) }.isSuccess
    }

    // 알림 ID를 돌려 써서 연속 하차 때 앞 알림이 덮어씌워지지 않게 한다.
    private fun postNotice(text: String) {
        val notification = Notification.Builder(this, CHANNEL_NOTICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("물건 확인")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(1))
            .setAutoCancel(true)
            .build()
        val id = NOTICE_ID + (noticeSeq++ % NOTICE_SLOTS)
        runCatching { getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    companion object {
        private const val TAG = "CheckAgain"
        private const val CHANNEL_ONGOING = "auto_ongoing"
        private const val CHANNEL_NOTICE = "auto_notice"
        private const val ONGOING_ID = 1
        private const val NOTICE_ID = 100
        private const val NOTICE_SLOTS = 10
        private const val ACTION_STOP = "dev.hjun.checkagain.STOP"
        private const val EXTRA_RESET = "reset"
        private const val HISTORY_MAX = 300

        @Volatile
        var running = false
            private set

        @Volatile
        var current: AutoFlow? = null
            private set

        @Volatile
        var sink: ((String) -> Unit)? = null

        private val history = ArrayDeque<String>()
        private val ui = Handler(Looper.getMainLooper())

        fun push(message: String) {
            Log.d(TAG, message)
            synchronized(history) {
                history.addLast(message)
                while (history.size > HISTORY_MAX) history.removeFirst()
            }
            ui.post { sink?.invoke(message) }
        }

        fun replay(): List<String> = synchronized(history) { history.toList() }

        fun start(context: Context, reset: Boolean) {
            val intent = Intent(context, AutoService::class.java).putExtra(EXTRA_RESET, reset)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AutoService::class.java).setAction(ACTION_STOP))
        }
    }
}
