package com.example.utt_trafficjams

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.utt_trafficjams.ui.UTTTrafficApp
import com.example.utt_trafficjams.ui.theme.UTTTrafficTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UTTTrafficTheme {
                UTTTrafficApp()
            }
        }
    }
}
