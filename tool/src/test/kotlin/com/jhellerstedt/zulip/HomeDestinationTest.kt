package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.HomeDestination
import com.jhellerstedt.zulip.ui.homeDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeDestinationTest {
    @Test
    fun oneSavedAccountOpensTheMenu() {
        assertEquals(HomeDestination.Menu, homeDestination(1))
    }

    @Test
    fun zeroSavedAccountsOpensTheRealmList() {
        assertEquals(HomeDestination.RealmList, homeDestination(0))
    }

    @Test
    fun twoSavedAccountsOpenTheRealmList() {
        assertEquals(HomeDestination.RealmList, homeDestination(2))
    }
}
