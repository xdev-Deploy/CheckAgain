package dev.hjun.checkagain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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
            NotificationChannel(CHANNEL_NOTICE, "등록과 하차 알림", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setWanted(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            // 프로세스가 죽었다가 시스템이 되살린 경우, 백그라운드에서는 마이크 타입 포그라운드 서비스를 시작할 수 없다.
            // 조용히 멈추지 않도록 알림을 남기고, 사용자가 누르면 앱이 열리면서 다시 시작한다.
            push("포그라운드 서비스 시작 실패, 마이크 권한을 확인하세요")
            postNotice(Kind.WARN, "작동 중단", "챙겼니가 멈췄어요. 눌러서 앱을 열면 다시 시작해요.")
            stopSelf()
            return START_NOT_STICKY
        }
        setWanted(true)
        val reset = intent?.getBooleanExtra(EXTRA_RESET, false) == true
        if (reset) {
            flow?.stop()
            flow = null
        }
        if (flow == null) {
            flow = AutoFlow(applicationContext, { m -> push(m) }, { kind, title, text -> postNotice(kind, title, text) }).also { it.start(reset) }
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

    // 사용자가 끄지 않았는데 서비스가 사라졌는지 알기 위한 표시
    private fun setWanted(value: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WANTED, value).apply()
    }

    private fun openApp(requestCode: Int, center: Boolean = false): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CENTER, center),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun enterForeground(): Boolean {
        val notification = Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("챙겼니")
            .setContentText("내릴 때 물건을 알려드릴게요")
            .setContentIntent(openApp(0))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "끄기",
                    PendingIntent.getService(
                        this,
                        2,
                        Intent(this, AutoService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )
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

    // 알림 센터에 기록을 남기고 시스템 알림도 띄운다.
    // 알림 ID를 돌려 써서 연속 이벤트 때 앞 알림이 덮어씌워지지 않게 한다.
    private fun postNotice(kind: String, title: String, text: String) {
        runCatching { EventStore(this).add(kind, title, text) }
        val notification = Notification.Builder(this, CHANNEL_NOTICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(1, true))
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
        private const val PREFS = "service"
        private const val KEY_WANTED = "wanted"

        fun wanted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

        // 처음 설치한 뒤에는 켜 두고, 사용자가 끈 뒤에는 다시 켤 때까지 켜지 않는다.
        fun shouldRun(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return !prefs.contains(KEY_WANTED) || prefs.getBoolean(KEY_WANTED, false)
        }

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
