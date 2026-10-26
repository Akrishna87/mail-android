package com.akrishna87.mail

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider(
    val label: String,
    val imapHost: String,
    val smtpHost: String,
    val smtpPort: Int,
    val usesOAuth: Boolean,
) {
    GMAIL("Gmail", "imap.gmail.com", "smtp.gmail.com", 465, false),
    YAHOO("Yahoo Mail", "imap.mail.yahoo.com", "smtp.mail.yahoo.com", 465, false),
    MICROSOFT("Outlook / Hotmail", "outlook.office365.com", "smtp-mail.outlook.com", 587, true),
}

/** App password for Gmail/Yahoo; empty for Microsoft, whose tokens live in [AccountStore.authState]. */
data class Account(
    val provider: Provider,
    val email: String,
    val name: String,
    val password: String = "",
)

/** Stores the signed-in account in encrypted preferences on the device. */
class AccountStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "account",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): Account? {
        val provider = prefs.getString("provider", null) ?: return null
        return Account(
            provider = Provider.valueOf(provider),
            email = prefs.getString("email", "").orEmpty(),
            name = prefs.getString("name", "").orEmpty(),
            password = prefs.getString("password", "").orEmpty(),
        )
    }

    fun save(account: Account) {
        prefs.edit()
            .putString("provider", account.provider.name)
            .putString("email", account.email)
            .putString("name", account.name)
            .putString("password", account.password)
            .apply()
    }

    /** Serialized AppAuth state (Microsoft refresh/access tokens). */
    var authState: String
        get() = prefs.getString("authState", "").orEmpty()
        set(value) = prefs.edit().putString("authState", value).apply()

    /** INBOX UIDNEXT seen by the last new-mail check; 0 means "not checked yet". */
    var lastUidNext: Long
        get() = prefs.getLong("lastUidNext", 0)
        set(value) = prefs.edit().putLong("lastUidNext", value).apply()

    fun clear() = prefs.edit().clear().apply()
}
