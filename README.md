# Mailroom for Android

An email app for Android with a Gmail-style design, for **Gmail** and **Yahoo Mail**
(**Outlook / Hotmail** once Microsoft sign-in is set up, see below).
Built for personal use: no server, no ads, and your password stays encrypted on your phone.

## Install

1. On your Android phone, open the [latest release](https://github.com/Akrishna87/mail-android/releases/latest).
2. Tap **Mailroom.apk** to download it, then open the downloaded file.
3. The first time, Android asks you to allow installs from your browser. Allow it, then tap **Install**.

To update, install the newest release the same way; your account stays signed in.
**☰ → Settings** shows which version you have.

## Signing in

| Provider | How |
|---|---|
| Gmail | Use an **app password**, not your normal password. Turn on 2-Step Verification, then create one at <https://myaccount.google.com/apppasswords>. |
| Yahoo Mail | Use an **app password**. Create one at <https://login.yahoo.com/account/security> → *Generate app password*. |
| Outlook / Hotmail | Not available yet: needs the one-time setup below. |

## Features

**Inbox**
- Gmail's tabs: **Primary, Promotions, Social, Updates, Forums**, sorted exactly as Gmail sorts them, with unread counts (Gmail accounts)
- All folders and labels in the ☰ menu
- Sender avatars, a preview line, unread mail highlighted, and a star you can tap
- Swipe to archive (or delete outside the inbox), with **Undo**
- Pull down to refresh

**Selecting several emails**: long-press an email (or tap its avatar), then Archive, Delete, Mark read/unread, Snooze, or under ⋮ Select all, Star, Move to and Label as.

**Reading**
- Opens instantly: the newest 50 emails are downloaded ahead and kept on the phone
- Wide emails are narrowed to the screen at normal text size; embedded images show
- Attachments open in other apps
- Archive, Delete, Mark unread, Snooze, Move to and Label as; Reply, Reply all and Forward

**Writing**: To, Cc, Bcc, attachments (up to 25 MB in total).

**Search** like Gmail: recent searches, sender suggestions, filter chips (Unread, Starred, Has attachment, dates).
On Gmail, every Gmail search works (`from:`, `subject:`, `has:attachment`, `is:unread`, `older_than:1y`, `OR`, ...) across all mail.
On Yahoo the common ones work within the current folder.

**Snooze**: later today, tomorrow, this weekend or next week. Snoozed mail waits in a
*Snoozed by Mailroom* label and comes back to the inbox unread, with a notification. (Gmail does not let other
apps use its own snooze.) The phone brings it back, so if it is off, the mail returns once it is online again.

**New mail** (☰ → Settings → Check for new mail): **instantly (push)**, every 15, 30 or 60 minutes, or only when
the app is open. Background checks also download new mail, so it is there when you open the app. One notification
per email (Primary only, like Gmail); tapping it opens that email. For reliable checks, tap **Allow background use**
in Settings.

**Themes** (☰ → Settings → Theme): Ocean, Forest, Sunset, Lavender, Rose, your wallpaper's colours, or Classic
Gmail white; each with a dark mode.

## One-time Microsoft sign-in setup

Microsoft requires every mail app to be registered. This is free.

1. Go to <https://entra.microsoft.com> and sign in → **App registrations** → **New registration**.
   (If it asks for a directory first, Microsoft needs a free Azure account.)
2. Name: `Mailroom`. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**.
3. Redirect URI: platform **Public client/native (mobile & desktop)**, value `com.akrishna87.mail://oauth2redirect`.
4. Click **Register** and copy the **Application (client) ID**.
5. Put it in `gradle.properties` as `msClientId=<your id>` and push. The next build has Microsoft sign-in enabled.

## Development

Every push to `main`:

1. runs the unit tests (`./gradlew testDebugUnitTest`): snooze times, search filters, theme contrast;
2. runs the UI tests on an Android emulator (`./gradlew connectedDebugAndroidTest`): selecting emails, menus, dialogs;
3. builds a signed APK and publishes it as a release.

To build locally you need Android Studio (or the Android SDK) and JDK 17:

```bash
./gradlew assembleRelease
```

Release builds are signed with a key stored in the repository secrets `SIGNING_KEYSTORE_BASE64`
(the keystore file, base64-encoded) and `SIGNING_PASSWORD`. Releases are only published when these are set,
so every published APK installs as an update over the previous one.

## How it works

Kotlin and Jetpack Compose (Material 3). JavaMail talks IMAP/SMTP directly from the phone, keeping one connection
and the current folder open; Gmail tabs and search use Gmail's IMAP search extension (`X-GM-RAW`). Push uses IMAP
IDLE in a foreground service; periodic checks and snooze returns use WorkManager. Mail is cached on the phone, and
the password is kept in EncryptedSharedPreferences. AppAuth handles Microsoft sign-in.
