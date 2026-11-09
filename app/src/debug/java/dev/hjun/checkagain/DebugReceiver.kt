package dev.hjun.checkagain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 디버그 빌드 전용. 화면 없이 adb 명령으로 좌석, 문, 주행을 흉내 낸다.
 *
 * adb shell am broadcast -n dev.hjun.checkagain/.DebugReceiver --es cmd seat --es name ROW_2_RIGHT --ez value true
 * cmd: seat(name, value), door(name, value), rolling(value), clear, reset, notice(name: register, alight, info, warn)
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val value = intent.getBooleanExtra("value", false)
        val cmd = intent.getStringExtra("cmd")
        // 화면 토스트와 알림 센터를 확인하는 용도라 자동 모드가 꺼져 있어도 동작한다.
        if (cmd == "notice") {
            val kind = when (name) {
                "alight" -> Kind.ALIGHT
                "info" -> Kind.INFO
                "warn" -> Kind.WARN
                else -> Kind.REGISTER
            }
            EventStore(context).add(kind, "테스트 알림", "토스트와 알림 센터 확인용 메시지예요.")
            return
        }
        val flow = AutoService.current
        if (flow == null) {
            AutoService.push("자동 모드가 실행 중이 아님")
            return
        }
        when (cmd) {
            "seat" -> flow.injectSeat(name, value)
            "door" -> flow.injectDoor(name, value)
            "rolling" -> flow.injectRolling(value)
            "clear" -> flow.clearInjected()
            "reset" -> flow.clearRegistrations()
            else -> AutoService.push("알 수 없는 명령: $cmd")
        }
    }
}
