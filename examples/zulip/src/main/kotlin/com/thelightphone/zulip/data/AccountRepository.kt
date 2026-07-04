package com.thelightphone.zulip.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class StoredZulipAccount(
    val id: Long,
    val realmUrl: String,
    val realmName: String,
    val email: String,
    val userId: Int,
    val zulipFeatureLevel: Int,
)

class AccountRepository private constructor(
    private val database: ZulipDatabase,
    private val cipher: CredentialCipher,
    private val dataStore: DataStore<Preferences>,
) {
    private val dao = database.accountDao()

    fun getAllAccounts(): List<StoredZulipAccount> =
        dao.getAllAccounts().map { it.toStored() }

    fun getAccount(id: Long): StoredZulipAccount? =
        dao.getAccount(id)?.toStored()

    fun findAccount(realmUrl: String, email: String): StoredZulipAccount? =
        dao.findAccount(realmUrl, email)?.toStored()

    fun getApiKey(id: Long): String? {
        val entity = dao.getAccount(id) ?: return null
        return cipher.decrypt(entity.encryptedApiKey)
    }

    fun insertAccount(
        realmUrl: String,
        realmName: String,
        email: String,
        apiKey: String,
        userId: Int,
        zulipFeatureLevel: Int,
    ): StoredZulipAccount {
        val existing = dao.findAccount(realmUrl, email)
        if (existing != null) {
            return existing.toStored()
        }
        val id = dao.insert(
            ZulipAccountEntity(
                realmUrl = realmUrl,
                realmName = realmName,
                email = email,
                encryptedApiKey = cipher.encrypt(apiKey),
                userId = userId,
                zulipFeatureLevel = zulipFeatureLevel,
                addedAt = System.currentTimeMillis(),
            ),
        )
        if (id == -1L) error("Failed to insert Zulip account")
        return requireNotNull(getAccount(id))
    }

    fun deleteAccount(id: Long): Boolean = dao.deleteAccount(id) > 0

    fun updateAccountMetadata(
        id: Long,
        realmName: String,
        zulipFeatureLevel: Int,
    ): StoredZulipAccount {
        val updated = dao.updateAccountMetadata(id, realmName, zulipFeatureLevel)
        if (updated <= 0) error("Failed to update Zulip account metadata")
        return requireNotNull(getAccount(id))
    }

    val activeAccountId: Flow<Long?> =
        dataStore.data.map { prefs -> prefs[ACTIVE_ACCOUNT_ID] }

    suspend fun getActiveAccountId(): Long? =
        dataStore.data.first()[ACTIVE_ACCOUNT_ID]

    suspend fun setActiveAccountId(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(ACTIVE_ACCOUNT_ID)
            } else {
                prefs[ACTIVE_ACCOUNT_ID] = id
            }
        }
    }

    private fun ZulipAccountEntity.toStored() = StoredZulipAccount(
        id = id,
        realmUrl = realmUrl,
        realmName = realmName,
        email = email,
        userId = userId,
        zulipFeatureLevel = zulipFeatureLevel,
    )

    companion object {
        const val DATABASE_NAME = "zulip_accounts.db"
        private val ACTIVE_ACCOUNT_ID = longPreferencesKey("active_account_id")

        @Volatile
        private var instance: AccountRepository? = null

        fun getInstance(
            databaseProvider: () -> ZulipDatabase,
            dataStore: DataStore<Preferences>,
        ): AccountRepository {
            return instance ?: synchronized(this) {
                instance ?: AccountRepository(
                    database = databaseProvider(),
                    cipher = CredentialCipher(),
                    dataStore = dataStore,
                ).also { instance = it }
            }
        }
    }
}
