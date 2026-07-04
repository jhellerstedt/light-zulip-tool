package com.thelightphone.zulip

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.zulip.data.AccountRepository
import com.thelightphone.zulip.data.ZulipDatabase
import com.thelightphone.zulip.store.GlobalStore

object ZulipServices {
  @Volatile
    private var globalStore: GlobalStore? = null

    @Volatile
    private var accountRepository: AccountRepository? = null

    fun accountRepository(context: SealedLightContext): AccountRepository {
        return accountRepository ?: synchronized(this) {
            accountRepository ?: AccountRepository.getInstance(
                databaseProvider = {
                    context.buildDatabase(ZulipDatabase::class.java, AccountRepository.DATABASE_NAME)
                },
                dataStore = context.dataStore,
            ).also { accountRepository = it }
        }
    }

    fun globalStore(context: SealedLightContext): GlobalStore {
        return globalStore ?: synchronized(this) {
            globalStore ?: GlobalStore(accountRepository(context)).also { globalStore = it }
        }
    }
}
