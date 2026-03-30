package com.example.utt_trafficjams

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.utt_trafficjams.ui.UTTTrafficApp
import com.example.utt_trafficjams.ui.theme.UTTTrafficTheme

class MainActivity : ComponentActivity() {

    private var openChatToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        enableEdgeToEdge()
        setContent {
            UTTTrafficTheme {
                UTTTrafficApp(openChatToken = openChatToken)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) {
            openChatToken += 1
            intent.removeExtra(EXTRA_OPEN_CHAT)
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "extra_open_chat"
    }
}
