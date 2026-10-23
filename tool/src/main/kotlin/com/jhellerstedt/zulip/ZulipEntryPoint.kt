package com.jhellerstedt.zulip

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

const val TOOL_DISPLAY_NAME = "Chat for Zulip (unofficial)"

const val UNOFFICIAL_DISCLAIMER =
    "Unofficial client. Not affiliated with Zulip, Kandra Labs, or The Light Phone."

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
