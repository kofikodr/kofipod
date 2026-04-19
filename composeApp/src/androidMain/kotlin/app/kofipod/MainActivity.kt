// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.kofipod.auth.AuthBridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthBridge.attach(this)
        setContent { App() }
    }

    override fun onDestroy() {
        AuthBridge.detach(this)
        super.onDestroy()
    }
}
