package com.jhellerstedt.zulip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jhellerstedt.zulip.store.GlobalStore
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomeDestination {
    Menu,
    RealmList,
}

fun homeDestination(accountCount: Int): HomeDestination =
    if (accountCount == 1) HomeDestination.Menu else HomeDestination.RealmList

private object HomeReturn {
    var toMenu: Boolean = false
}

/**
 * The portal builds this tool against the published SDK, which only offers [SimpleLightScreen.navigateTo]
 * and [SimpleLightScreen.goBack]. One saved realm walks back to the menu already on the stack.
 * Any other count opens the realm list on top, without restoring the last realm.
 */
fun <T> SimpleLightScreen<T>.openHome(destination: HomeDestination) {
    when (destination) {
        HomeDestination.Menu -> {
            HomeReturn.toMenu = true
            goBack()
        }
        HomeDestination.RealmList -> {
            HomeReturn.toMenu = false
            navigateTo(screenFactory = { ChooseAccountScreen(it, false) })
        }
    }
}

@Composable
fun <T> SimpleLightScreen<T>.ReturnHomeOnShow() {
    LaunchedEffect(Unit) {
        if (HomeReturn.toMenu) goBack()
    }
}

@Composable
fun ClearHomeReturn() {
    LaunchedEffect(Unit) {
        HomeReturn.toMenu = false
    }
}

@Composable
fun <T> SimpleLightScreen<T>.homeBarButton(globalStore: GlobalStore): LightBarButton.Text {
    val scope = rememberCoroutineScope()
    var inFlight by remember { mutableStateOf(false) }
    return LightBarButton.Text(
        text = "HOME",
        onClick = {
            if (!inFlight) {
                inFlight = true
                scope.launch {
                    var navigated = false
                    try {
                        val destination = try {
                            val count = withContext(Dispatchers.IO) { globalStore.listAccounts().size }
                            homeDestination(count)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            HomeDestination.RealmList
                        }
                        if (!isActive) return@launch
                        withContext(Dispatchers.Main) {
                            navigated = true
                            openHome(destination)
                        }
                    } finally {
                        if (!navigated) inFlight = false
                    }
                }
            }
        },
    )
}
