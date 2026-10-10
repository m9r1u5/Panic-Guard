package com.m9r1u5.panicguard

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    private val volTimes = ArrayDeque<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(PanicGuardPlugin::class.java)
        super.onCreate(savedInstanceState)
    }

    // Triple press of a volume key (only while the app is on screen).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            (event?.repeatCount ?: 0) == 0) {
            val now = SystemClock.uptimeMillis()
            volTimes.addLast(now)
            while (volTimes.isNotEmpty() && now - volTimes.first() > 1500) volTimes.removeFirst()
            if (volTimes.size >= 3) {
                volTimes.clear()
                bridge?.triggerWindowJSEvent("volumeTriple")
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
