# Mailroom for Android

A **Gmail** app for Android with a Gmail-style design.
Built for personal use: no server, no ads, and your password stays encrypted on your phone.

## Install

1. On your Android phone, open the [latest release](https://github.com/Akrishna87/mail-android/releases/latest).
2. Tap **Mailroom.apk** to download it, then open the downloaded file.
3. The first time, Android asks you to allow installs from your browser. Allow it, then tap **Install**.

To update, install the newest release the same way; your account stays signed in.
**☰ → Settings** shows which version you have.

## Signing in

Mailroom signs in with a Gmail **app password**, not your normal password.
The Gmail app can't make one, but your phone's browser can:

1. Make sure 2-Step Verification is on for your Google account (<https://myaccount.google.com/security>).
2. Open <https://myaccount.google.com/apppasswords> in the browser (the sign-in screen has a button for it) and sign in.
3. Type a name such as `Mailroom`, tap **Create**, and copy the 16-letter password.
4. In Mailroom, enter your name, Gmail address and that password, and tap **Sign in**.

The password stays encrypted on your phone. To revoke it, delete it on the same page.

## Features

**Inbox**
- Gmail's tabs: **Primary, Promotions, Social, Updates, Forums**, sorted exactly as Gmail sorts them, with unread counts
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
Every Gmail search works (`from:`, `subject:`, `has:attachment`, `is:unread`, `older_than:1y`, `OR`, ...) across all mail.

**Snooze**: later today, tomorrow, this weekend or next week. Snoozed mail waits in a
*Snoozed by Mailroom* label and comes back to the inbox unread, with a notification. (Gmail does not let other
apps use its own snooze.) The phone brings it back, so if it is off, the mail returns once it is online again.

**New mail** (☰ → Settings → Check for new mail): **instantly (push)**, every 15, 30 or 60 minutes, or only when
the app is open. Background checks also download new mail, so it is there when you open the app. One notification
per email (Primary only, like Gmail); tapping it opens that email. For reliable checks, tap **Allow background use**
in Settings.

**Themes** (☰ → Settings → Theme): Night (the default), Ocean, Forest, Sunset, Lavender, Rose, your wallpaper's colours, or Classic
Gmail white; each with a dark mode.

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
the app password is kept in EncryptedSharedPreferences.
