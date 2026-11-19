# Media Hush

An Xposed (Vector / LSPosed) module that stops media notifications, and the media player card in
quick settings, from showing up. Use it per app (it was written for Brave) or for every app at once.

## Why

Android deliberately lets media notifications ignore notification blocks. `NotificationManagerService`
treats a notification as a media notification when it uses `Notification.MediaStyle` *and* carries a
`MediaSession` token in `android.mediaSession`. Such a notification is posted even when the app or its
channel is blocked, and SystemUI builds its media player card from that token. As a result, for Brave
and other Chromium browsers (and any other player):

- turning off the app's notifications, or its media channel, does nothing;
- the Android 13+ notification permission does not apply, because media sessions are exempt;
- "Pin media player", "Show media on lock screen" and similar toggles only cover paused/resumed
  players, not one that is currently playing;
- `settings put global qs_media_controls 0` is ignored on Android 12+;
- the "Google Cast" setting people suggest controls the Cast output switcher. That is a different thing,
  and it doesn't exist on microG ROMs.

## Modes

The mode is set by the module's **scope** in the Vector/LSPosed manager. There is no other config.

| Scope | Mode | Effect | Needs |
|---|---|---|---|
| One or more apps | App mode | The app's media notification becomes an ordinary notification, so its notification settings work again | That app's media channel (or all its notifications) turned off |
| System Framework | Global mode | Every media notification from every app is dropped before it is posted | A reboot after changing the scope |

Both can be active at once, but global mode already covers every app.

In both modes the apps' `MediaSession`s are untouched, so headset and Bluetooth play/pause buttons keep
working. Foreground playback services keep running without their notification. Non-media
notifications are never touched.

### App mode

Inside each scoped app, the module hooks `NotificationManager.notify` / `notifyAsUser` /
`notifyAsPackage` and `Service.startForeground` (Chromium posts its media notification through a
foreground service). It removes `android.mediaSession` and, if it is `MediaStyle` /
`DecoratedMediaCustomViewStyle`, `android.template`. The notification is then treated as an
ordinary one. Your app/channel setting applies to it, and SystemUI has no token to build a player card
from.

If the channel stays enabled, you get a plain "playing media" notification instead of the player.

### Global mode

Inside `system_server`, the module hooks `NotificationManagerService.enqueueNotificationInternal`, where
every posted notification ends up, including foreground-service notifications. It returns early for
any media notification, or any notification app mode already stripped (marked with
`fyi.fyrnir.mediahush.stripped`). The notification is never posted and no player card is made. Because
nothing is ever posted, nothing needs blocking in settings.

## Requirements

- Root with Zygisk: Magisk (Zygisk enabled), or KernelSU plus a Zygisk implementation
- An Xposed framework that loads legacy (API 82) modules: [Vector](https://github.com/JingMatrix/Vector)
  (the maintained LSPosed successor) or LSPosed
- Android 10+ (`minSdk 29`)

### Tested on

| Device | ROM | Root / framework | Tested |
|---|---|---|---|
| OnePlus 8T (kebab) | LineageOS for microG 24.0 (Android 17) | Magisk v31.1, Vector v2.2 | App mode and global mode with Brave 1.99.57 |

Reports from other setups are welcome. Please open an issue.

## Install

1. Install the APK from [Releases](../../releases), or build it yourself (see below).
2. Enable the module in the manager and set its scope:
   - **App mode:** the apps to hush. The suggested default is Brave (stable, beta, nightly). Then turn
     off each app's media notification channel (for Brave: Settings → Apps → Brave → Notifications →
     *Media playback*) and force stop the app.
   - **Global mode:** *System Framework*, then reboot.

Headless, with Vector's CLI as root (`adb root` or `su`). In the CLI the framework scope is called
`system`:

```sh
adb install app-release.apk
CLI=/data/adb/modules/zygisk_vector/cli
adb shell "$CLI modules enable fyi.fyrnir.mediahush"

# app mode
adb shell "$CLI scope set fyi.fyrnir.mediahush com.brave.browser/0"
adb shell "$CLI scope add fyi.fyrnir.mediahush <another.package>/0"
adb shell am force-stop com.brave.browser

# global mode
adb shell "$CLI scope set fyi.fyrnir.mediahush system/0" && adb reboot
```

## Verifying

The module logs to the framework log (Vector: `cli log cat`; LSPosed: the manager's Logs screen):

```
MediaHush: app mode: hooked com.brave.browser (com.brave.browser, 6 methods)
MediaHush: stripped media notification (token=true, template=android.app.Notification$MediaStyle, channel=media)
MediaHush: global mode: hooked com.android.server.notification.NotificationManagerService (3 methods)
MediaHush: global mode: dropped media notification from com.brave.browser (channel=media)
```

The "stripped" and "dropped" lines are logged once per process, the first time it happens.
Some other checks:

```sh
adb shell dumpsys notification --noredact | grep pkg=com.brave.browser   # nothing while playing
adb shell dumpsys media_session | grep com.brave.browser                  # session still active
```

## Limitations

- A ROM could build a player card straight from an active `MediaSession`, without any notification.
  AOSP up to Android 17 does not do this; if a ROM does, a SystemUI hook would be needed.
- Global mode depends on `NotificationManagerService.enqueueNotificationInternal` existing. That has
  been the case for many Android releases, but the log line tells you if the hook failed.
- Only the legacy Xposed API 82 is used. Libxposed API 100+ features such as hot reload are not.

## Building

Requirements: JDK 17 and an Android SDK with platform 35 (`local.properties`: `sdk.dir=...`).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # unsigned unless a keystore is configured
```

Release signing is optional. Set `MH_KEYSTORE_PROPERTIES` (an environment variable or Gradle property)
to the path of a properties file containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

The Xposed API (`de.robv.android.xposed:api:82`) isn't on Maven Central, so `xposed-stubs/` contains
minimal compile-only stand-ins for the few classes used. They are `compileOnly` and never packaged;
the framework provides the real classes at runtime.

## License

[MIT](LICENSE)
