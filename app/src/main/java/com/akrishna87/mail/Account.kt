package com.akrishna87.mail

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Gmail's servers; the app only works with Gmail. */
object Gmail {
    const val IMAP_HOST = "imap.gmail.com"
    const val SMTP_HOST = "smtp.gmail.com"
    const val SMTP_PORT = 465
}

/** A Gmail account signed in with an app password. */
data class Account(
    val email: String,
    val name: String,
    val password: String,
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
        // Older versions also offered Yahoo and Outlook; those sign-ins no longer work, so they show sign-in again.
        if (prefs.getString("provider", "GMAIL") != "GMAIL") return null
        val password = prefs.getString("password", null)?.takeIf { it.isNotEmpty() } ?: return null
        return Account(
            email = prefs.getString("email", "").orEmpty(),
            name = prefs.getString("name", "").orEmpty(),
            password = password,
        )
    }

    fun save(account: Account) {
        prefs.edit()
            .remove("provider")
            .remove("authState")
            .putString("email", account.email)
            .putString("name", account.name)
            .putString("password", account.password)
            .apply()
    }

    /** INBOX UIDNEXT seen by the last new-mail check; 0 means "not checked yet". */
    var lastUidNext: Long
        get() = prefs.getLong("lastUidNext", 0)
        set(value) = prefs.edit().putLong("lastUidNext", value).apply()

    /** When the background new-mail check last ran, and its error if it failed. */
    var lastCheckTime: Long
        get() = prefs.getLong("lastCheckTime", 0)
        set(value) = prefs.edit().putLong("lastCheckTime", value).apply()
    var lastCheckError: String
        get() = prefs.getString("lastCheckError", "").orEmpty()
        set(value) = prefs.edit().putString("lastCheckError", value).apply()

    /** How often to fetch new mail in the background. */
    var syncMode: SyncMode
        get() = runCatching { SyncMode.valueOf(prefs.getString("syncMode", null) ?: "") }.getOrDefault(SyncMode.EVERY_15)
        set(value) = prefs.edit().putString("syncMode", value.name).apply()

    /** Recent searches, newest first. */
    var recentSearches: List<String>
        get() = prefs.getString("recentSearches", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("recentSearches", value.joinToString("\n")).apply()

    /** Messages snoozed by this app, waiting to return to the inbox. */
    var snoozes: List<Snooze>
        get() = Snooze.listFromJson(prefs.getString("snoozes", "[]").orEmpty())
        set(value) = prefs.edit().putString("snoozes", Snooze.listToJson(value)).apply()

    /** Colour theme; Night unless chosen otherwise. */
    var theme: AppTheme
        get() = runCatching { AppTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(AppTheme.NIGHT)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    /** The last emails opened and how long each took (Settings → Recent opens). */
    var openLog: List<OpenRecord>
        get() = OpenRecord.listFromJson(prefs.getString("openLog", "[]").orEmpty())
        set(value) = prefs.edit().putString("openLog", OpenRecord.listToJson(value)).apply()

    fun clear() = prefs.edit().clear().apply()
}
