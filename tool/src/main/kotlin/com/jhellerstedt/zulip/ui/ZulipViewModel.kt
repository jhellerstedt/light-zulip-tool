package com.jhellerstedt.zulip.ui

import com.jhellerstedt.zulip.store.GlobalStore
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen

abstract class ZulipViewModel<T>(
    private val pauseEventSync: () -> Unit = {},
    private val resumeEventSync: () -> Unit = {},
) : LightViewModel<T>() {
    constructor(globalStore: GlobalStore) : this(
        pauseEventSync = globalStore::pauseEventSync,
        resumeEventSync = globalStore::resumeEventSync,
    )

    override fun onAppPause() {
        pauseEventSync()
    }

    override fun onScreenShow(screen: SimpleLightScreen<T>) {
        resumeEventSync()
    }
}
