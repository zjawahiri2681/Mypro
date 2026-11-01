package com.mypro.iptvbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.player.PlayerActivity
import com.mypro.iptvbox.ui.HomeScreen
import com.mypro.iptvbox.ui.IptvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingLink = mutableStateOf<WatchLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvTheme { HomeScreen(pendingLink = pendingLink.value, onLinkConsumed = { pendingLink.value = null }) }
        }
        val fromLauncher = intent?.action == Intent.ACTION_MAIN
        handleIntent(intent)
        if (savedInstanceState == null && fromLauncher && IptvApp.settings.current.autoPlayLast) {
            lifecycleScope.launch {
                val id = IptvApp.settings.current.lastChannelId
                var tries = 0
                while (IptvApp.repo.content.value == null && tries++ < 25) delay(200)
                IptvApp.repo.channelById(id)?.let { PlayerActivity.playChannel(this@MainActivity, it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == WatchLink.SCHEME) {
            WatchLink.parse(data.toString())?.let { pendingLink.value = it }
        }
    }
}
