package com.mypro.minutes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mypro.minutes.ui.AppViewModel
import com.mypro.minutes.ui.ExportScreen
import com.mypro.minutes.ui.HomeScreen
import com.mypro.minutes.ui.LiveScreen
import com.mypro.minutes.ui.MinutesTheme
import com.mypro.minutes.ui.Screen
import com.mypro.minutes.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MinutesTheme {
                BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
                when (vm.screen) {
                    Screen.HOME -> HomeScreen(vm)
                    Screen.SETUP -> SetupScreen(vm)
                    Screen.LIVE -> LiveScreen(vm)
                    Screen.EXPORT -> ExportScreen(vm)
                }
            }
        }
    }
}
