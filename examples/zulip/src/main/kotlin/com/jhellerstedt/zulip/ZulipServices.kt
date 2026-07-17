package com.jhellerstedt.zulip

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.SealedLightContext
import com.jhellerstedt.zulip.data.AccountRepository
import com.jhellerstedt.zulip.data.MIGRATION_1_2
import com.jhellerstedt.zulip.data.ZulipCacheRepository
import com.jhellerstedt.zulip.data.ZulipDatabase
import com.jhellerstedt.zulip.store.GlobalStore

object ZulipServices {
    @Volatile
    private var globalStore: GlobalStore? = null

    @Volatile
    private var accountRepository: AccountRepository? = null

    @Volatile
    private var cacheRepository: ZulipCacheRepository? = null

    @Volatile
    private var database: ZulipDatabase? = null

    private fun database(context: SealedLightContext): ZulipDatabase {
        return database ?: synchronized(this) {
            database ?: context.buildDatabase(
                ZulipDatabase::class.java,
                AccountRepository.DATABASE_NAME,
                MIGRATION_1_2,
            ).also { database = it }
        }
    }

    fun accountRepository(context: SealedLightContext): AccountRepository {
        return accountRepository ?: synchronized(this) {
            accountRepository ?: AccountRepository.getInstance(
                databaseProvider = { database(context) },
                dataStore = context.dataStore,
            ).also { accountRepository = it }
        }
    }

    fun cacheRepository(context: SealedLightContext): ZulipCacheRepository {
        return cacheRepository ?: synchronized(this) {
            cacheRepository ?: ZulipCacheRepository(database(context).cacheDao()).also { cacheRepository = it }
        }
    }

    fun globalStore(context: SealedLightContext): GlobalStore {
        return globalStore ?: synchronized(this) {
            globalStore ?: GlobalStore(
                accountRepository = accountRepository(context),
                cacheRepository = cacheRepository(context),
            ).also { globalStore = it }
        }
    }
}
