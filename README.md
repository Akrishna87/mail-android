# Mailroom for Android

A simple email app for Android that works with **Gmail**, **Yahoo Mail** and **Outlook / Hotmail**.
Built for personal use: no server, no ads, and your passwords stay encrypted on your phone.

## Install

1. On your Android phone, open the [latest release](https://github.com/Akrishna87/mail-android/releases/latest).
2. Tap **Mailroom.apk** to download it, then open the downloaded file.
3. The first time, Android asks you to allow installs from your browser. Allow it, then tap **Install**.

To update, install the newest release the same way. Your account stays signed in.

## Signing in

| Provider | How |
|---|---|
| Gmail | Use an **app password**, not your normal password. Turn on 2-Step Verification, then create one at <https://myaccount.google.com/apppasswords>. |
| Yahoo Mail | Use an **app password**. Create one at <https://login.yahoo.com/account/security> → *Generate app password*. |
| Outlook / Hotmail | Tap **Sign in with Microsoft** and sign in on Microsoft's page. Needs the one-time setup below. |

## Version 1 features

- One Gmail, Yahoo or Outlook account
- Inbox and all other folders, with unread counts
- Read plain and formatted (HTML) email; links open in your browser, scripts are blocked
- Write, reply, reply all and forward
- Delete (moves to Trash)
- Notification for new mail, checked about every 15 minutes

## One-time Microsoft sign-in setup

Microsoft requires every mail app to be registered. This is free.

1. Go to <https://entra.microsoft.com> and sign in → **App registrations** → **New registration**.
2. Name: `Mailroom`. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**.
3. Redirect URI: platform **Public client/native (mobile & desktop)**, value `com.akrishna87.mail://oauth2redirect`.
4. Click **Register** and copy the **Application (client) ID**.
5. Put it in `gradle.properties` as `msClientId=<your id>` and push. The next build has Microsoft sign-in enabled.

## Building

GitHub Actions builds a signed APK on every push to `main` and publishes it as a release.
To build locally you need Android Studio (or the Android SDK) and JDK 17:

```bash
./gradlew assembleRelease
```

Release builds are signed with a key stored in the repository secrets `SIGNING_KEYSTORE_BASE64`
(the keystore file, base64-encoded) and `SIGNING_PASSWORD`. Releases are only published when these are set,
so every published APK installs as an update over the previous one.

## Tech

Kotlin, Jetpack Compose (Material 3), JavaMail for IMAP/SMTP, AppAuth for Microsoft OAuth,
WorkManager for new-mail checks, and EncryptedSharedPreferences for credentials.
