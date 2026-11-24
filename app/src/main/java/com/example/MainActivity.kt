package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scheduler.AgentForegroundService
import com.example.ui.MainViewModel
import com.example.ui.PersonalAiApp

class MainActivity : ComponentActivity() {
    private var openLiveAgentTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(AgentForegroundService.EXTRA_OPEN_AGENT_LIVE, false) == true) {
            openLiveAgentTrigger++
        }
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            PersonalAiApp(
                viewModel = mainViewModel,
                openLiveAgentSignal = openLiveAgentTrigger
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AgentForegroundService.EXTRA_OPEN_AGENT_LIVE, false)) {
            openLiveAgentTrigger++
        }
    }
}
