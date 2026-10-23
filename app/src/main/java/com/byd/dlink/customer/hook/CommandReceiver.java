package com.byd.dlink.customer.hook;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CommandReceiver {

    private static final String ACTION_SPLIT_SCREEN = "com.byd.dlink.customer.SPLIT_SCREEN";
    private static final String ACTION_AUDIO_PROBE = "com.byd.dlink.customer.AUDIO_PROBE";
    private static final String ACTION_VIEW_TREE = "com.byd.dlink.customer.VIEW_TREE";
    private static final String ACTION_COLOURS = "com.byd.dlink.customer.COLOURS";
    private static final String ACTION_PREFS_CHANGED = "com.byd.dlink.customer.PREFS_CHANGED";
    private static boolean sRegistered = false;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        // SystemUI runs in more than one process, and every one of them would register a
        // receiver for the same action -- the command would then be handled by whichever
        // process happened to answer, including one with no windows of its own.
        if (!lpparam.packageName.equals(lpparam.processName)) return;
        hookSystemUIInit(lpparam);
    }

    private static void hookSystemUIInit(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] systemUIClasses = {
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.SystemUIFactory"
        };

        for (String className : systemUIClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("onCreate")
                            || method.getName().equals("start")) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (sRegistered) return;
                                Object thisObj = param.thisObject;
                                Context context = null;
                                if (thisObj instanceof Context) {
                                    context = (Context) thisObj;
                                } else {
                                    for (Method m : thisObj.getClass().getMethods()) {
                                        if (m.getName().equals("getContext")
                                                && m.getParameterCount() == 0) {
                                            Object result = m.invoke(thisObj);
                                            if (result instanceof Context) {
                                                context = (Context) result;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (context != null) {
                                    registerReceiver(context);
                                }
                            }
                        });
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        XposedBridge.log("BYD Dlink Customer: could not find SystemUI init method for command receiver");
    }

    private static synchronized void registerReceiver(Context context) {
        if (sRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_SPLIT_SCREEN);
            filter.addAction(ACTION_AUDIO_PROBE);
            filter.addAction(ACTION_VIEW_TREE);
            filter.addAction(ACTION_COLOURS);
            filter.addAction(ACTION_PREFS_CHANGED);
            context.registerReceiver(new CommandBroadcastReceiver(), filter);
            sRegistered = true;
            XposedBridge.log("BYD Dlink Customer: command receiver registered");
        } catch (Exception e) {
            XposedBridge.log("BYD Dlink Customer: failed to register receiver: " + e.getMessage());
        }
    }

    private static class CommandBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_VIEW_TREE.equals(action)) {
                ViewTreeDump.request();
                return;
            }
            if (ACTION_COLOURS.equals(action)) {
                dumpColours(context);
                return;
            }
            if (ACTION_PREFS_CHANGED.equals(action)) {
                SystemBarBackgroundHook.onPrefsChanged();
                return;
            }
            if (ACTION_AUDIO_PROBE.equals(action)) {
                final Context appContext = context.getApplicationContext();
                final Intent request = new Intent(intent);
                worker().post(new Runnable() {
                    @Override
                    public void run() {
                        NaviVolumeChannel.probe(appContext, request);
                    }
                });
                return;
            }
            if (!ACTION_SPLIT_SCREEN.equals(action)) return;

            String pkg1 = intent.getStringExtra("pkg1");
            String pkg2 = intent.getStringExtra("pkg2");

            if (pkg1 == null || pkg2 == null) {
                XposedBridge.log("BYD Dlink Customer: missing pkg1 or pkg2");
                return;
            }

            XposedBridge.log("BYD Dlink Customer: split screen request: " + pkg1 + " | " + pkg2);
            final Context appContext = context.getApplicationContext();
            worker().post(new Runnable() {
                @Override
                public void run() {
                    launchSplitScreen(appContext, pkg1, pkg2);
                }
            });
        }
    }

    /**
     * Every colour resource this SystemUI ships, by name and value. Which names carry the bar's
     * day/night ink is a question each fork answers differently, and the values in the views say only
     * what is being drawn now, not what the pair is called.
     */
    private static void dumpColours(Context context) {
        try {
            Class<?> colour = Class.forName("com.android.systemui.R$color", false,
                    context.getClassLoader());
            android.content.res.Resources resources = context.getResources();
            java.lang.reflect.Field[] fields = colour.getFields();
            for (int i = 0; i < fields.length; i++) {
                try {
                    XposedBridge.log("BYD Dlink Customer: colour " + fields[i].getName()
                            + " = #" + Integer.toHexString(resources.getColor(fields[i].getInt(null))));
                } catch (Throwable ignored) {
                    // Not a colour a value can be read for; the list simply skips it.
                }
            }
            XposedBridge.log("BYD Dlink Customer: " + fields.length + " colours dumped");
        } catch (Throwable t) {
            XposedBridge.log("BYD Dlink Customer: colour dump failed: " + t);
        }
    }

    // The two split stacks are picked by the launch's windowing mode, and both SystemUI's own
    // wrapper (ActivityOptionsCompat.makeSplitScreenOptions) and the server's
    // ScreenSplitUtils.makeSplitScreenOptions set exactly these: 3 for the half anchored to the
    // leading edge, 4 for the other one.
    private static final int LAUNCH_MODE_PRIMARY_STACK = 3;
    private static final int LAUNCH_MODE_SECONDARY_STACK = 4;

    // Same as the ROM's own launch: NEW_TASK plus the second 0x10000000 bit. setFlags rather
    // than addFlags, because getLaunchIntentForPackage already carries RESET_TASK_IF_NEEDED and
    // the ROM replaces it.
    private static final int LAUNCH_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP;

    // Putting an app into one half of the split is a launch whose options name that half's
    // stack. SystemUI's split-app buttons build those options through their own class; asking
    // the framework directly gives the same result without depending on a class this fork has
    // been known to rename.
    private static void launchIntoSplitHalf(Context context, String pkg, boolean primary) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            XposedBridge.log("BYD Dlink Customer: no launch intent for " + pkg);
            return;
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        XposedHelpers.callMethod(options, "setLaunchWindowingMode",
                primary ? LAUNCH_MODE_PRIMARY_STACK : LAUNCH_MODE_SECONDARY_STACK);
        XposedHelpers.callMethod(options, "setSplitScreenCreateMode", primary ? 0 : 1);
        launch.setFlags(LAUNCH_FLAGS);
        context.startActivity(launch, options.toBundle());
    }

    private static void launchSplitScreen(Context context, String pkg1, String pkg2) {
        try {
            launchIntoSplitHalf(context, pkg1, true);
            launchIntoSplitHalf(context, pkg2, false);
            XposedBridge.log("BYD Dlink Customer: split screen launched with our own"
                    + " split options");
        } catch (Throwable t) {
            XposedBridge.log("BYD Dlink Customer: launchSplitScreen failed: " + t);
        }
    }

    // Starting a task from recents is a synchronous binder call into ActivityTaskManagerService,
    // and that call answers back on SystemUI's main thread -- which is exactly where a broadcast
    // receiver runs. Handled there it wedges both sides until the broadcast times out, and the
    // resulting ANR gets SystemUI killed.
    private static volatile Handler sWorker;

    private static Handler worker() {
        Handler handler = sWorker;
        if (handler != null) return handler;
        synchronized (CommandReceiver.class) {
            if (sWorker == null) {
                HandlerThread thread = new HandlerThread("BYD-Dlink-Command");
                thread.start();
                sWorker = new Handler(thread.getLooper());
            }
            return sWorker;
        }
    }
}
