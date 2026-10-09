package com.m9r1u5.panicguard

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(PanicGuardPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
