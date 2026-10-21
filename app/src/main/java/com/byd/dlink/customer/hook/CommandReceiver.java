package com.byd.dlink.customer.hook;

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

    private static void launchSplitScreen(Context context, String pkg1, String pkg2) {
        try {
            // A split is only a split when the launch carries split windowing options;
            // LAUNCH_ADJACENT alone leaves both tasks fullscreen. SystemUI's own picker entry
            // point builds those options, and this receiver runs inside SystemUI, so its
            // classloader is the one that already has the class.
            Class<?> dividerUtils = XposedHelpers.findClass(
                    "com.android.systemui.stackdivider.DividerUtils", context.getClassLoader());
            XposedHelpers.callStaticMethod(dividerUtils, "startSplitAppActivity",
                    context, pkg1, true);
            XposedHelpers.callStaticMethod(dividerUtils, "startSplitAppActivity",
                    context, pkg2, false);
            XposedBridge.log("BYD Dlink Customer: split screen launched via DividerUtils");
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
