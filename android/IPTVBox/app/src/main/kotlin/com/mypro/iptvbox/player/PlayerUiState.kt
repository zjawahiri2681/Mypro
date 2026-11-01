package com.mypro.iptvbox.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.ui.AspectRatioFrameLayout
import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.Programme
import com.mypro.iptvbox.core.sync.WatchLink

/** Observable state for the player screen. Mutated only on the main thread. */
class PlayerUiState {
    var title by mutableStateOf("")
    var groupName by mutableStateOf("")
    var channel by mutableStateOf<Channel?>(null)
    var isLive by mutableStateOf(true)
    var logo by mutableStateOf("")
    var number by mutableIntStateOf(0)

    var overlay by mutableStateOf(true)
    var channelList by mutableStateOf(false)
    var epgPanel by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var watchDialog by mutableStateOf<WatchLink?>(null)
    var hostPicker by mutableStateOf(false)
    var numberInput by mutableStateOf("")

    var buffering by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var bufferedPosition by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    var toast by mutableStateOf("")
    var videoInfo by mutableStateOf("")
    var nowProgramme by mutableStateOf<Programme?>(null)
    var nextProgramme by mutableStateOf<Programme?>(null)
    var isFavorite by mutableStateOf(false)
    var quality by mutableStateOf("Auto")
    /** Submenu to open when the options panel appears (e.g. from the yellow button). */
    var menuInitialSub by mutableStateOf<String?>(null)

    val anyPanel: Boolean get() = channelList || epgPanel || menu || watchDialog != null || hostPicker || error != null
}
