package com.jhellerstedt.zulip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jhellerstedt.zulip.store.GlobalStore
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

@Composable
fun homeBarButton(
    globalStore: GlobalStore,
    openMenu: () -> Unit,
    openRealmList: () -> Unit,
): LightBarButton.Text {
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
                            when (destination) {
                                HomeDestination.Menu -> openMenu()
                                HomeDestination.RealmList -> openRealmList()
                            }
                        }
                    } finally {
                        if (!navigated) inFlight = false
                    }
                }
            }
        },
    )
}
