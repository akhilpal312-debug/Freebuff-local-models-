package dev.universalai.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.universalai.agent.ui.navigation.AppNavHost
import dev.universalai.agent.ui.theme.UniversalAgentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as UniversalAgentApp).container
        setContent {
            UniversalAgentTheme {
                AppNavHost(container)
            }
        }
    }
}
