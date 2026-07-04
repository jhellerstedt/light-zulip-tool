package com.thelightphone.zulip

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ZulipEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        // GlobalStore is initialized lazily from screens via ZulipServices.
        // Observe serverData here when UnifiedPush support is added.
        serverData.collect { }
    }

    override val enablePushNotifications: Boolean
        get() = false
}
