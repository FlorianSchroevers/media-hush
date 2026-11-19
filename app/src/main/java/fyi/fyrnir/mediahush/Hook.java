package fyi.fyrnir.mediahush;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Bundle;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps media notifications (and the media player card SystemUI builds from them) from showing up.
 *
 * <p>Android treats a notification as a media notification when it is {@code MediaStyle} and carries
 * a MediaSession token in {@code android.mediaSession}. NotificationManagerService posts those even
 * when the app or channel is blocked, and SystemUI builds its player card from the token.
 *
 * <p>What happens depends on where the module is loaded, which is set by its scope in the
 * LSPosed/Vector manager:
 *
 * <ul>
 *   <li><b>App mode</b> (an app in scope): inside that app, the token and MediaStyle template are
 *       stripped before the notification leaves the app. It becomes ordinary, so the app's own
 *       notification settings (e.g. its media channel turned off) hide it again.</li>
 *   <li><b>Global mode</b> ("System Framework" / {@code android} in scope): inside system_server,
 *       every media notification from every app is dropped before it is posted, along with any
 *       notification app mode already stripped. No per-app settings needed.</li>
 * </ul>
 *
 * In both modes the apps' MediaSessions are untouched, so headset/Bluetooth media buttons keep
 * working.
 */
public final class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "MediaHush: ";

    /** Marker that app mode leaves on notifications it stripped, so global mode can still drop them. */
    static final String EXTRA_STRIPPED = "fyi.fyrnir.mediahush.stripped";

    private static final String SYSTEM = "android";
    private static final String NMS = "com.android.server.notification.NotificationManagerService";

    private static final Set<String> MEDIA_TEMPLATES = Set.of(
            "android.app.Notification$MediaStyle",
            "android.app.Notification$DecoratedMediaCustomViewStyle");

    /** True in system_server once global mode is set up there. */
    private static volatile boolean globalModeHere;

    private static volatile boolean loggedFirstStrip;
    private static volatile boolean loggedFirstDrop;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SYSTEM.equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        } else if (!globalModeHere) {
            // System packages that share system_server (Settings provider, Telecom, ...) also show
            // up here; global mode already covers them, so don't add per-app hooks on top.
            hookApp(lpparam);
        }
    }

    // ---------------------------------------------------------------- app mode

    private static void hookApp(XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook stripArgs = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (Object arg : param.args) {
                    if (arg instanceof Notification) strip((Notification) arg);
                }
            }
        };

        // notify(id, n) and notify(tag, id, n) both funnel into notifyAsUser; notifyAsPackage is separate.
        int hooks = 0;
        for (String m : new String[] {"notify", "notifyAsUser", "notifyAsPackage"}) {
            hooks += hookAll(NotificationManager.class, m, stripArgs);
        }
        // Chromium (and many players) show the media notification through a foreground service,
        // which goes through ActivityManager rather than NotificationManager.
        hooks += hookAll(Service.class, "startForeground", stripArgs);

        XposedBridge.log(TAG + "app mode: hooked " + lpparam.packageName + " ("
                + lpparam.processName + ", " + hooks + " methods)");
    }

    static void strip(Notification n) {
        Bundle extras = n.extras;
        if (extras == null || !isMedia(extras)) return;
        boolean hadToken = extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        String template = extras.getString(Notification.EXTRA_TEMPLATE);

        extras.remove(Notification.EXTRA_MEDIA_SESSION);
        if (MEDIA_TEMPLATES.contains(template)) extras.remove(Notification.EXTRA_TEMPLATE);
        extras.putBoolean(EXTRA_STRIPPED, true);

        if (!loggedFirstStrip) {
            loggedFirstStrip = true;
            XposedBridge.log(TAG + "stripped media notification (token=" + hadToken
                    + ", template=" + template + ", channel=" + n.getChannelId() + ")");
        }
    }

    // ---------------------------------------------------------------- global mode

    private static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> nms;
        try {
            nms = Class.forName(NMS, false, lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "global mode: " + NMS + " not found: " + t);
            return;
        }

        // Every path that posts a notification (app binder calls, foreground services via
        // NotificationManagerInternal) ends in enqueueNotificationInternal. Returning early drops
        // the notification; the foreground service itself keeps running.
        int hooks = hookAll(nms, "enqueueNotificationInternal", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Notification n = null;
                String pkg = null;
                for (Object arg : param.args) {
                    if (arg instanceof Notification) n = (Notification) arg;
                    else if (pkg == null && arg instanceof String) pkg = (String) arg;
                }
                if (n == null || n.extras == null) return;
                if (!isMedia(n.extras) && !n.extras.getBoolean(EXTRA_STRIPPED, false)) return;

                param.setResult(null);
                if (!loggedFirstDrop) {
                    loggedFirstDrop = true;
                    XposedBridge.log(TAG + "global mode: dropped media notification from " + pkg
                            + " (channel=" + n.getChannelId() + ")");
                }
            }
        });
        globalModeHere = hooks > 0;
        XposedBridge.log(TAG + "global mode: hooked " + NMS + " (" + hooks + " methods)");
    }

    // ---------------------------------------------------------------- shared

    private static boolean isMedia(Bundle extras) {
        return extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                || MEDIA_TEMPLATES.contains(extras.getString(Notification.EXTRA_TEMPLATE));
    }

    private static int hookAll(Class<?> c, String name, XC_MethodHook cb) {
        try {
            return XposedBridge.hookAllMethods(c, name, cb).size();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "failed to hook " + c.getName() + "." + name + ": " + t);
            return 0;
        }
    }
}
