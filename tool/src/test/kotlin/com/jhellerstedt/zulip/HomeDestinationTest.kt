package com.jhellerstedt.zulip

import com.jhellerstedt.zulip.ui.AccountSwitchLanding
import com.jhellerstedt.zulip.ui.HomeDestination
import com.jhellerstedt.zulip.ui.accountSwitchLanding
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

    @Test
    fun coldStartPushesANewMenu() {
        assertEquals(AccountSwitchLanding.NewMenu, accountSwitchLanding(autoRestoreActiveAccount = true))
    }

    @Test
    fun choosingARealmWalksBackToTheExistingMenu() {
        assertEquals(
            AccountSwitchLanding.ExistingMenu,
            accountSwitchLanding(autoRestoreActiveAccount = false),
        )
    }
}
