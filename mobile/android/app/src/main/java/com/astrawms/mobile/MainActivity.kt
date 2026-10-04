package com.astrawms.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.astrawms.mobile.ui.AstraNav
import com.astrawms.mobile.ui.AstraTheme
import com.astrawms.mobile.ui.LocalContainer
import androidx.compose.runtime.CompositionLocalProvider

/** The single activity: the RF app's screens are Compose destinations (ui/AstraNav). */
class MainActivity : ComponentActivity() {

    private val container get() = (application as AstraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                AstraTheme {
                    AstraNav()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.readers.start(this)
    }

    override fun onPause() {
        container.readers.stop(this)
        super.onPause()
    }
}
