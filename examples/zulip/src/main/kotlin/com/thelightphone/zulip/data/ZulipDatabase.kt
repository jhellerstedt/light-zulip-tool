package com.thelightphone.zulip.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["realm_url", "email"], unique = true)],
)
data class ZulipAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "realm_url") val realmUrl: String,
    @ColumnInfo(name = "realm_name") val realmName: String,
    val email: String,
    @ColumnInfo(name = "encrypted_api_key") val encryptedApiKey: ByteArray,
    @ColumnInfo(name = "user_id") val userId: Int,
    @ColumnInfo(name = "zulip_feature_level") val zulipFeatureLevel: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
interface ZulipAccountDao {
    @Query("SELECT * FROM accounts ORDER BY added_at ASC")
    fun getAllAccounts(): List<ZulipAccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    fun getAccount(id: Long): ZulipAccountEntity?

    @Query("SELECT * FROM accounts WHERE realm_url = :realmUrl AND email = :email LIMIT 1")
    fun findAccount(realmUrl: String, email: String): ZulipAccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(account: ZulipAccountEntity): Long

    @Query("DELETE FROM accounts WHERE id = :id")
    fun deleteAccount(id: Long): Int

    @Query(
        """
        UPDATE accounts
        SET realm_name = :realmName,
            zulip_feature_level = :zulipFeatureLevel
        WHERE id = :id
        """,
    )
    fun updateAccountMetadata(id: Long, realmName: String, zulipFeatureLevel: Int): Int
}

@Database(entities = [ZulipAccountEntity::class], version = 1, exportSchema = false)
abstract class ZulipDatabase : RoomDatabase() {
    abstract fun accountDao(): ZulipAccountDao
}
