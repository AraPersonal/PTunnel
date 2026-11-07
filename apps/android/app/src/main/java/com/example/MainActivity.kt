package com.example

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.VpnScreen
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.ShieldVpnTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val ltrConfig = Configuration(newBase.resources.configuration).apply {
            setLayoutDirection(Locale.US)
        }
        super.attachBaseContext(newBase.createConfigurationContext(ltrConfig))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        enableEdgeToEdge()
        setContent {
            ShieldVpnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepForestBg
                ) {
                    VpnScreen()
                }
            }
        }
    }
}
