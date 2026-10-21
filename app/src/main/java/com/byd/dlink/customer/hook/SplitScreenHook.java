package com.byd.dlink.customer.hook;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;

import com.byd.dlink.customer.utils.PrefsManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SplitScreenHook {

    private static final String TAG = "BYD Dlink Customer";

    private static final String DIVIDER_VIEW_CLASS =
            "com.android.systemui.stackdivider.DividerView";
    private static final String DIVIDER_CLASS =
            "com.android.systemui.stackdivider.Divider";
    private static final String DIVIDER_STATE_CLASS =
            "com.android.systemui.stackdivider.DividerState";
    private static final String SNAP_ALGORITHM_CLASS =
            "com.android.internal.policy.DividerSnapAlgorithm";

    // TaskConstants.DockSide
    private static final int DOCK_SIDE_LEFT = 1;
    private static final int DOCK_SIDE_RIGHT = 2;
    private static final int DOCK_SIDE_TOP = 3;

    // Which int names which half belongs to the framework, and guessing it is not harmless:
    // on this build 4 is the *secondary*, so a primary matched against 4 never finds the stack
    // anchored to the leading edge, and the split reads as absent. Resolved from
    // WindowConfiguration instead, which also covers the Q rename of primary to MULTI_WINDOW.
    private static volatile int sPrimaryMode = -1;
    private static volatile int sSecondaryMode = -1;

    // The home and recents stacks carry the secondary windowing mode as well, and the home one
    // starts short of the leading edge, so on mode alone it reads as the other half of the
    // split. Activity type separates them: only a standard stack is an app the user placed.
    private static volatile int sStandardActivityType = -1;

    // DividerView.resizeStack(position, taskPosition, target) has two positions, and they feed
    // different rects. taskPosition == Integer.MAX_VALUE (the ROM's own TASK_POSITION_UNSET,
    // compared against the float-NaN bit pattern) takes the branch that calls
    // resizeDockedStack(dockedRect, null, null, null, null) -- WindowManager then derives both
    // task rects from the stack rect, which is what keeps each half clear of the divider. A
    // real taskPosition instead makes resizeStack build both task rects itself from that value
    // and hand them to WindowManager, so the apps get laid out for a position we never chose.
    private static final int TASK_POSITION_UNSET = Integer.MAX_VALUE;

    // WindowManager reports the docked bounds in the same pixel units we hand to
    // resizeStack, so this only has to cover the ratio rounding.
    private static final int POSITION_SLACK = 8;

    private static volatile boolean sFieldsLogged = false;
    private static volatile boolean sMethodTraceLogged = false;
    private static Class<?> sDividerViewClass = null;
    private static Class<?> sDividerClass = null;
    private static Class<?> sDividerStateClass = null;
    private static volatile Object sDividerState = null;
    private static volatile Object sDividerView = null;
    private static volatile boolean sDividerStateLogged = false;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PrefsManager.isSplitScreenEnabled()) {
            return;
        }

        resolveWindowingModes(lpparam.classLoader);

        try {
            sDividerViewClass = XposedHelpers.findClass(
                    DIVIDER_VIEW_CLASS, lpparam.classLoader);
            hookDividerView(sDividerViewClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DividerView not found: " + t);
        }

        try {
            sDividerClass = XposedHelpers.findClass(
                    DIVIDER_CLASS, lpparam.classLoader);
            hookDivider(sDividerClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Divider controller not found: " + t);
        }

        try {
            sDividerStateClass = XposedHelpers.findClass(
                    DIVIDER_STATE_CLASS, lpparam.classLoader);
            hookDividerState(sDividerStateClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DividerState not found: " + t);
        }

        try {
            Class<?> snapClass = XposedHelpers.findClass(
                    SNAP_ALGORITHM_CLASS, lpparam.classLoader);
            hookSnapAlgorithm(snapClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DividerSnapAlgorithm not found: " + t);
        }
    }

    private static void hookDividerView(Class<?> dividerViewClass) {
        hookInjectDependencies(dividerViewClass);
        hookCalculatePosition(dividerViewClass);
        hookCalculatePositionForInsetBounds(dividerViewClass);
        hookCalculateBoundsForPosition(dividerViewClass);
        hookSecondaryBounds(dividerViewClass);
        hookResizeStack(dividerViewClass);
        hookGetCurrentPosition(dividerViewClass);
        hookDraggingMethods(dividerViewClass);
        hookLifecycle(dividerViewClass);
        hookSettledEvents(dividerViewClass);
        hookMethodTracer(dividerViewClass);
    }

    // Every split task window is derived from the divider position here, so this is
    // where a divider/task disagreement (black bars) originates. Both the divider and
    // the task Rects come out of the same call, which is what keeps them aligned.
    // While the app picker is on screen the split must keep the system default ratio,
    // so the caller's own recents-visibility state gates the rewrite.
    private static void hookCalculateBoundsForPosition(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("calculateBoundsForPosition")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3 || !isIntClass(params[0]) || !isIntClass(params[1])) continue;
            if (!params[2].getName().equals("android.graphics.Rect")) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;
                    if (isDragging(param.thisObject)) return;
                    int position = (int) param.args[0];
                    int dockSide = (int) param.args[1];
                    if (dockSide != DOCK_SIDE_LEFT && dockSide != DOCK_SIDE_RIGHT
                            && dockSide != DOCK_SIDE_TOP) {
                        return;
                    }
                    int configured = getConfiguredPosition(param.thisObject);
                    if (configured <= 0) return;
                    if (isSelectingApp(param.thisObject)) {
                        XposedBridge.log(TAG + ": calculateBoundsForPosition kept pos="
                                + position + " (picker is up)");
                        return;
                    }
                    int wanted = snapPosition(param.thisObject, configured,
                            activeSplitPosition(param.thisObject));
                    if (position == wanted) return;
                    XposedBridge.log(TAG + ": calculateBoundsForPosition pos=" + position
                            + " dockSide=" + dockSide + " out=" + param.args[2]);
                    param.args[0] = wanted;
                    XposedBridge.log(TAG + ":   -> forced pos=" + wanted);
                }
            });
            XposedBridge.log(TAG + ": hooked calculateBoundsForPosition(int,int,Rect)");
            return;
        }
        XposedBridge.log(TAG + ": WARNING: calculateBoundsForPosition(int,int,Rect) NOT found");
    }

    private static int intField(Object obj, String name) {
        try {
            Field field = XposedHelpers.findFieldIfExists(obj.getClass(), name);
            if (field == null) return -999;
            field.setAccessible(true);
            return field.getInt(obj);
        } catch (Throwable t) {
            return -999;
        }
    }

    // Whether the app picker is on screen, i.e. the user has not placed the second app
    // yet. DividerView.mGrowRecents looks like this state but is assigned once in
    // onFinishInflate() from R.bool.recents_grow_in_multiwindow, so on this build it is a
    // constant. DividerUtils.isRecentsActivityVisible() is SystemUI's own live check: it
    // asks WindowManager which stacks exist and whether their top activity is
    // com.android.systemui.recents.RecentsActivity.
    private static volatile Method sPickerVisibleMethod;
    private static volatile boolean sPickerVisibleMethodProbed;

    private static boolean isSelectingApp(Object dividerView) {
        if (isDragging(dividerView)) return false;
        Method method = resolvePickerVisibleMethod(dividerView);
        if (method == null) return false;
        try {
            // null cacheValid: the fork's own no-arg overload passes exactly this.
            return Boolean.TRUE.equals(method.invoke(null, new Object[]{null}));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDragging(Object dividerView) {
        try {
            Field field = XposedHelpers.findFieldIfExists(dividerView.getClass(), "mMoving");
            if (field == null || field.getType() != boolean.class) return false;
            field.setAccessible(true);
            return field.getBoolean(dividerView);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method resolvePickerVisibleMethod(Object dividerView) {
        if (sPickerVisibleMethodProbed) return sPickerVisibleMethod;
        synchronized (SplitScreenHook.class) {
            if (!sPickerVisibleMethodProbed) {
                sPickerVisibleMethodProbed = true;
                try {
                    Class<?> utils = XposedHelpers.findClass(
                            "com.android.systemui.stackdivider.DividerUtils",
                            dividerView.getClass().getClassLoader());
                    for (Method m : utils.getDeclaredMethods()) {
                        if (m.getName().equals("isRecentsActivityVisible")
                                && m.getParameterCount() == 1
                                && isBoolClass(m.getReturnType())) {
                            m.setAccessible(true);
                            sPickerVisibleMethod = m;
                            XposedBridge.log(TAG + ": picker state via DividerUtils."
                                    + m.getName() + "(" + m.getParameterTypes()[0].getSimpleName()
                                    + ")=" + invokePicker(m));
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": WARNING: no picker state source: " + t);
                }
                if (sPickerVisibleMethod == null) {
                    XposedBridge.log(TAG + ": WARNING: isRecentsActivityVisible not found");
                }
            }
        }
        return sPickerVisibleMethod;
    }

    private static boolean invokePicker(Method method) {
        try {
            return Boolean.TRUE.equals(method.invoke(null, new Object[]{null}));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isIntClass(Class<?> c) {
        return c == int.class || "int".equals(c.getName());
    }

    private static boolean isFloatClass(Class<?> c) {
        return c == float.class || "float".equals(c.getName());
    }

    private static boolean isBoolClass(Class<?> c) {
        return c == boolean.class || "boolean".equals(c.getName());
    }

    // ── injectDependencies: capture DividerState reference ──

    private static void hookInjectDependencies(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("injectDependencies")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 2) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sDividerView = param.thisObject;
                    XposedBridge.log(TAG + ": captured DividerView");
                    for (Object arg : param.args) {
                        if (arg == null) continue;
                        String className = arg.getClass().getName();
                        if (className.contains("DividerState")) {
                            sDividerState = arg;
                            XposedBridge.log(TAG + ": captured DividerState: " + className);
                            logDividerStateFields(arg);
                            break;
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked injectDependencies(" + params.length + " params)");
            break;
        }
    }

    // ── DividerState: dump and hook position fields ──

    private static void logDividerStateFields(Object state) {
        if (sDividerStateLogged) return;
        sDividerStateLogged = true;

        XposedBridge.log(TAG + ": === DividerState fields ===");
        Class<?> clazz = state.getClass();
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(state);
                    XposedBridge.log(TAG + ":   " + clazz.getSimpleName() + "."
                            + f.getName() + " (" + f.getType().getSimpleName()
                            + ") = " + val);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ":   " + clazz.getSimpleName() + "."
                            + f.getName() + " error: " + t);
                }
            }
            clazz = clazz.getSuperclass();
        }

        XposedBridge.log(TAG + ": === DividerState methods ===");
        for (Method m : state.getClass().getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("  ").append(m.getReturnType().getSimpleName())
                    .append(" ").append(m.getName()).append("(");
            Class<?>[] ptypes = m.getParameterTypes();
            for (int i = 0; i < ptypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ptypes[i].getSimpleName());
            }
            sb.append(")");
            XposedBridge.log(TAG + ": " + sb.toString());
        }
        XposedBridge.log(TAG + ": === end DividerState ===");
    }

    private static void hookDividerState(Class<?> stateClass) {
        XposedBridge.log(TAG + ": hooking DividerState class");

        for (Method method : stateClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            String name = method.getName();
            Class<?> ret = method.getReturnType();
            Class<?>[] params = method.getParameterTypes();

            String lowerName = name.toLowerCase();

            if (isIntClass(ret) && (lowerName.contains("position") || lowerName.equals("get"))) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        int original = (int) param.getResult();
                        int desired = getDesiredPosition(param.thisObject);
                        if (desired > 0 && original != desired) {
                            XposedBridge.log(TAG + ": DividerState." + name
                                    + "() " + original + " → " + desired);
                            param.setResult(desired);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked DividerState." + name + "()");
            }

            if (lowerName.contains("set") && params.length >= 1 && isIntClass(params[0])) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        int desired = getDesiredPosition(param.thisObject);
                        if (desired > 0) {
                            XposedBridge.log(TAG + ": DividerState." + name
                                    + "(" + param.args[0] + " → " + desired + ")");
                            param.args[0] = desired;
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked DividerState." + name + "(int)");
            }
        }

        for (Field field : stateClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!isIntClass(field.getType())) continue;
            String lowerName = field.getName().toLowerCase();
            if (!lowerName.contains("position") && !lowerName.contains("pos")) continue;

            XposedBridge.log(TAG + ": will override DividerState field: " + field.getName());
        }
    }

    private static void setDividerStatePosition(Object state, int desiredPosition) {
        if (state == null) return;
        Class<?> clazz = state.getClass();
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            for (Field f : clazz.getDeclaredFields()) {
                if (!isIntClass(f.getType())) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;
                String lowerName = f.getName().toLowerCase();
                if (!lowerName.contains("position") && !lowerName.contains("pos")) continue;
                try {
                    f.setAccessible(true);
                    int old = f.getInt(state);
                    if (old != desiredPosition) {
                        f.setInt(state, desiredPosition);
                        XposedBridge.log(TAG + ": DividerState." + f.getName()
                                + " " + old + " → " + desiredPosition);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": setDividerStatePosition error on "
                            + f.getName() + ": " + t);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    // ── getCurrentPosition with caller tracing ──

    private static void hookGetCurrentPosition(Class<?> dividerViewClass) {
        boolean hooked = false;
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("getCurrentPosition")) continue;
            if (method.getParameterCount() != 0) continue;
            if (!isIntClass(method.getReturnType())) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                private int mCallCount = 0;

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;

                    int originalResult = (int) param.getResult();
                    int desiredPosition = getDesiredPosition(param.thisObject);
                    if (desiredPosition <= 0) return;

                    mCallCount++;
                    if (mCallCount <= 5) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("getCurrentPosition caller #").append(mCallCount)
                                .append(" result=").append(originalResult)
                                .append(" → ").append(desiredPosition).append("\n");
                        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                        int limit = Math.min(stack.length, 12);
                        for (int i = 0; i < limit; i++) {
                            String cls = stack[i].getClassName();
                            if (cls.contains("XposedBridge") || cls.contains("MethodAccessor"))
                                continue;
                            sb.append("  at ").append(stack[i]).append("\n");
                        }
                        XposedBridge.log(TAG + ": " + sb.toString());
                    }

                    if (originalResult != desiredPosition) {
                        param.setResult(desiredPosition);
                    }

                    if (sDividerState != null) {
                        setDividerStatePosition(sDividerState, desiredPosition);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked getCurrentPosition() with caller tracing");
            hooked = true;
            break;
        }
        if (!hooked) {
            XposedBridge.log(TAG + ": WARNING: getCurrentPosition() NOT found");
        }
    }

    // ── Dragging / fling / stop methods ──

    private static void hookDraggingMethods(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            String name = method.getName();
            Class<?>[] params = method.getParameterTypes();

            if (name.equals("startDragging") && params.length == 2
                    && isBoolClass(params[0]) && isBoolClass(params[1])) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": startDragging() called, result="
                                + param.getResult());
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        int desired = getDesiredPosition(param.thisObject);
                        if (desired > 0 && sDividerState != null) {
                            setDividerStatePosition(sDividerState, desired);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked startDragging(boolean, boolean)");
            }

            if (name.equals("stopDragging")) {
                boolean hasIntFirst = params.length >= 1 && isIntClass(params[0]);
                boolean hasSnapTarget = false;
                for (Class<?> p : params) {
                    if (p.getName().contains("SnapTarget")) hasSnapTarget = true;
                }

                if (hasIntFirst) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!PrefsManager.isSplitScreenEnabled()) return;
                            int desired = getDesiredPosition(param.thisObject);
                            if (desired > 0) {
                                XposedBridge.log(TAG + ": stopDragging pos="
                                        + param.args[0] + " → " + desired);
                                param.args[0] = desired;
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked stopDragging(" + params.length + " params)");
                }
            }

            if (name.equals("fling") && params.length >= 2
                    && isIntClass(params[0]) && isFloatClass(params[1])) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        int desired = getDesiredPosition(param.thisObject);
                        if (desired > 0) {
                            XposedBridge.log(TAG + ": fling pos=" + param.args[0]
                                    + " → " + desired);
                            param.args[0] = desired;
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked fling(int, float, ...)");
            }

            if (name.equals("flingTo")) {
                boolean hasIntFirst = params.length >= 1 && isIntClass(params[0]);
                boolean hasSnapTarget = false;
                for (Class<?> p : params) {
                    if (p.getName().contains("SnapTarget")) hasSnapTarget = true;
                }
                if (hasIntFirst && hasSnapTarget) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!PrefsManager.isSplitScreenEnabled()) return;
                            int desired = getDesiredPosition(param.thisObject);
                            if (desired > 0) {
                                XposedBridge.log(TAG + ": flingTo pos=" + param.args[0]
                                        + " → " + desired);
                                param.args[0] = desired;
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked flingTo(int, SnapTarget, ...)");
                }
            }

            if (name.equals("updateResizeStack") && params.length == 1
                    && isBoolClass(params[0])) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": updateResizeStack() called");
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        int desired = getDesiredPosition(param.thisObject);
                        if (desired > 0 && sDividerState != null) {
                            setDividerStatePosition(sDividerState, desired);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked updateResizeStack(boolean)");
            }

            if (name.equals("notifyDockSideChanged") && params.length == 1
                    && isIntClass(params[0])) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": notifyDockSideChanged(" + param.args[0] + ")");
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        adjustDividerPosition(param.thisObject);
                    }
                });
                XposedBridge.log(TAG + ": hooked notifyDockSideChanged(int)");
            }
        }
    }

    // ── Method tracer: log every DividerView method call (first time only) ──

    private static void hookMethodTracer(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            String name = method.getName();

            if (name.startsWith("get") || name.startsWith("set")
                    || name.equals("onLayout") || name.equals("onAttachedToWindow")
                    || name.equals("onFinishInflate") || name.equals("onVisibilityChanged")
                    || name.equals("onConfigurationChanged")
                    || name.equals("getCurrentPosition")
                    || name.equals("resizeStack") || name.equals("resizeStackDelayed")
                    || name.equals("calculatePosition")
                    || name.equals("calculateXPosition") || name.equals("calculateYPosition")
                    || name.equals("startDragging") || name.equals("stopDragging")
                    || name.equals("fling") || name.equals("flingTo")
                    || name.equals("updateResizeStack")
                    || name.equals("commitSnapFlags")
                    || name.equals("onDockedFirstAnimationFrame")
                    || name.equals("onDockedTopTask")
                    || name.equals("onRecentsDrawn")
                    || name.equals("injectDependencies")
                    || name.equals("notifyDockSideChanged")) {
                continue;
            }

            final String methodName = name;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                private volatile boolean mLogged = false;

                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!mLogged && !sMethodTraceLogged) {
                        XposedBridge.log(TAG + ": [trace] DividerView." + methodName + "() called");
                        mLogged = true;
                    }
                }
            });
        }
    }

    // ── Lifecycle hooks ──

    private static void hookLifecycle(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("onLayout")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 5) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;
                    XposedBridge.log(TAG + ": onLayout called");
                    adjustDividerPosition(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": hooked DividerView.onLayout");
            break;
        }

        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("onVisibilityChanged")) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int visibility = (int) param.args[0];
                    if (visibility == 0) {
                        XposedBridge.log(TAG + ": onVisibilityChanged VISIBLE");
                        if (!PrefsManager.isSplitScreenEnabled()) return;
                        adjustDividerPosition(param.thisObject);
                    }
                }
            });
            break;
        }

        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("onConfigurationChanged")) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + ": onConfigurationChanged called");
                    if (!PrefsManager.isSplitScreenEnabled()) return;
                    adjustDividerPosition(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": hooked onConfigurationChanged");
            break;
        }
    }

    // ── Field logging ──

    private static void logDividerViewFields(Object dividerView) {
        if (sFieldsLogged) return;
        sFieldsLogged = true;

        XposedBridge.log(TAG + ": === DividerView own int fields ===");
        Class<?> clazz = dividerView.getClass();
        while (clazz != null && !clazz.getName().equals("android.view.View")
                && !clazz.getName().equals("android.view.ViewGroup")) {
            for (Field f : clazz.getDeclaredFields()) {
                if (!isIntClass(f.getType())) continue;
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    int val = f.getInt(dividerView);
                    XposedBridge.log(TAG + ":   " + clazz.getSimpleName() + "."
                            + f.getName() + " = " + val);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ":   " + clazz.getSimpleName() + "."
                            + f.getName() + " error: " + t);
                }
            }
            clazz = clazz.getSuperclass();
        }

        XposedBridge.log(TAG + ": === DividerView all methods ===");
        for (Method m : dividerView.getClass().getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("  ").append(m.getReturnType().getSimpleName())
                    .append(" ").append(m.getName()).append("(");
            Class<?>[] ptypes = m.getParameterTypes();
            for (int i = 0; i < ptypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ptypes[i].getSimpleName());
            }
            sb.append(")");
            XposedBridge.log(TAG + ": " + sb.toString());
        }
        XposedBridge.log(TAG + ": === end ===");
    }

    // ── adjustDividerPosition ──

    // Diagnostics only. The actual task-window bounds are owned by system_server's
    // SplitScreenController, and forcing resizeStack from here writes conflicting info to
    // WindowManager and produces the black bars.
    private static void adjustDividerPosition(Object dividerView) {
        logDividerViewFields(dividerView);

        int desiredPosition = getDesiredPosition(dividerView);
        if (desiredPosition <= 0) return;

        int currentPosition = getActualScreenPosition(dividerView);
        XposedBridge.log(TAG + ": adjustDivider(diag): actual=" + currentPosition
                + " desired=" + desiredPosition);
    }

    // ── Split-completion snap ──

    // Once the picker closes the system runs no further layout pass, so suppressing the
    // rewrite during selection would otherwise leave the split at 50% for good. This
    // replays the fork's own settle path (see DividerView.onDockedTopTask): resize the
    // stack to the configured position, which recomputes the divider and both task rects
    // from one value and therefore cannot desync into black bars.
    private static void snapToConfiguredPosition(Object dividerView, String event) {
        try {
            int configured = getConfiguredPosition(dividerView);
            int[] secondary = new int[1];
            int actual = getSplitPositions(dividerView, secondary);
            int target = configured > 0
                    ? snapPosition(dividerView, configured, actual) : -1;
            boolean settled = near(actual, target)
                    && halvesClearDivider(dividerView, actual, secondary[0]);
            boolean selecting = settled ? false : isSelectingApp(dividerView);
            // Naming the reason, including when there is none, because every reason here is a
            // state the rest of the hook chain cannot see -- a silent bail looks exactly like a
            // snap that ran and did nothing.
            String skip = configured <= 0 ? "no configured position"
                    : actual < 0 ? "split not on screen"
                    : isDragging(dividerView) ? "finger has the divider"
                    : settled ? "already settled"
                    : selecting ? "picker is up" : null;
            XposedBridge.log(TAG + ": snap(" + event + ") target=" + target
                    + " configured=" + configured + " actual=" + actual
                    + " other=" + secondary[0]
                    + " live=" + getLivePosition(dividerView)
                    + (skip == null ? " -> resize" : " skip=" + skip));
            if (skip != null) return;

            Object snapAlgorithm = XposedHelpers.callMethod(dividerView, "getSnapAlgorithm");
            Object middle = XposedHelpers.callMethod(snapAlgorithm, "getMiddleTarget");

            // resizeStack bails out when it thinks nothing changed.
            Field lastResize = XposedHelpers.findFieldIfExists(
                    dividerView.getClass(), "mLastResizeRect");
            if (lastResize != null) {
                lastResize.setAccessible(true);
                Object rect = lastResize.get(dividerView);
                if (rect != null) XposedHelpers.callMethod(rect, "setEmpty");
            }

            XposedBridge.log(TAG + ": snap -> resizeStack(" + target + ")");
            resizeStackMethod().invoke(dividerView, target, TASK_POSITION_UNSET, middle);
            clearResizeDim(dividerView);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": snap failed: " + t);
        }
    }

    // resizeStack also derives a "about to dismiss" shade fraction from the position it was
    // handed, and a position that is not one of the snap targets gives a non-zero fraction,
    // which leaves WindowManager grey-shading the docked stack. The fork never sees this
    // because it only ever settles onto a real target. Clear it with the windowing mode
    // resizeStack just recorded, so the dim is removed from the stack it was put on.
    private static void clearResizeDim(Object dividerView) {
        try {
            Object proxy = XposedHelpers.getObjectField(dividerView, "mWindowManagerProxy");
            if (proxy == null) return;
            int mode = intField(proxy, "mDimLayerTargetWindowingMode");
            XposedHelpers.callMethod(proxy, "setResizeDimLayer", false, mode, 0f);
            XposedBridge.log(TAG + ": resize dim cleared (mode=" + mode + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resize dim clear failed: " + t);
        }
    }

    private static boolean near(int a, int b) {
        return Math.abs(a - b) <= POSITION_SLACK;
    }

    private static void resolveWindowingModes(ClassLoader loader) {
        try {
            Class<?> config = XposedHelpers.findClass(
                    "android.app.WindowConfiguration", loader);
            sPrimaryMode = intConstant(config, "WINDOWING_MODE_SPLIT_SCREEN_PRIMARY",
                    "WINDOWING_MODE_MULTI_WINDOW");
            sSecondaryMode = intConstant(config, "WINDOWING_MODE_SPLIT_SCREEN_SECONDARY",
                    "WINDOWING_MODE_MULTI_WINDOW_SECONDARY");
            sStandardActivityType = intConstant(config, "ACTIVITY_TYPE_STANDARD");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": WARNING: no windowing mode source: " + t);
        }
        XposedBridge.log(TAG + ": windowing modes primary=" + sPrimaryMode
                + " secondary=" + sSecondaryMode
                + " standardActivity=" + sStandardActivityType);
    }

    private static int intConstant(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(null);
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    // Where the divider really is, straight from WindowManager. This is the same stack query
    // SystemUI uses for its picker state, so the SELinux permissions are already there. The
    // docked half is the primary stack anchored to the leading edge; the other half is a
    // secondary stack starting past the divider.
    //
    // Reads the docked half's inner edge, and the other half's leading edge into
    // {@code secondaryOut} when one is given. -1 when a half is not on screen.
    private static int getSplitPositions(Object dividerView, int[] secondaryOut) {
        int primary = -1;
        if (secondaryOut != null) secondaryOut[0] = -1;
        try {
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityTaskManager",
                            dividerView.getClass().getClassLoader()),
                    "getService");
            if (service == null) return -1;
            Object infos = XposedHelpers.callMethod(service, "getAllStackInfos");
            if (!(infos instanceof List)) return -1;
            boolean landscape = isLandscape(dividerView);
            for (Object info : (List<?>) infos) {
                Object config = XposedHelpers.getObjectField(info, "configuration");
                if (config == null) continue;
                Object windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
                if (windowConfig == null) continue;
                Object mode = XposedHelpers.callMethod(windowConfig, "getWindowingMode");
                if (!(mode instanceof Integer)) continue;
                int stackMode = (Integer) mode;
                if (stackMode != sPrimaryMode && stackMode != sSecondaryMode) continue;
                if (!isPlacedAppStack(windowConfig)) continue;
                Rect bounds = stackBounds(info, windowConfig);
                if (bounds == null || bounds.isEmpty()) continue;
                boolean leading = landscape ? bounds.left == 0 : bounds.top == 0;
                if (stackMode == sPrimaryMode && leading) {
                    primary = landscape ? bounds.right : bounds.bottom;
                } else if (stackMode == sSecondaryMode && !leading && secondaryOut != null) {
                    secondaryOut[0] = landscape ? bounds.left : bounds.top;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": split position unavailable: " + t);
        }
        // A half that spans the whole screen is not a half: every decision built on the
        // reading -- including which side the configured ratio belongs to -- would be
        // inverted, so an edge-to-edge stack counts as no reading at all.
        int total = getTotalSize(dividerView);
        if (primary >= total) primary = -1;
        return primary;
    }

    // A stack the user actually placed an app in. Home and recents carry the same secondary
    // windowing mode as the undocked half, and the home one starts short of the leading edge,
    // so without this it is read as that half's inner boundary.
    private static boolean isPlacedAppStack(Object windowConfig) {
        if (sStandardActivityType < 0) return true;
        try {
            Object type = XposedHelpers.callMethod(windowConfig, "getActivityType");
            return type instanceof Integer && sStandardActivityType == (Integer) type;
        } catch (Throwable t) {
            return true;
        }
    }

    // A stack rect is a plain Rect on this framework's StackInfo -- the same field
    // SystemUI's own recents reads for the stack rect. The window configuration's copy is
    // kept as a fallback since it is where a newer framework would put it. Read-only: both
    // can be the live object rather than a copy.
    private static Rect stackBounds(Object stackInfo, Object windowConfig) {
        Rect bounds = rectField(stackInfo, "bounds");
        if (bounds == null || bounds.isEmpty()) bounds = rectField(windowConfig, "mBounds");
        if (bounds == null || bounds.isEmpty()) {
            try {
                Object value = XposedHelpers.callMethod(windowConfig, "getBounds");
                if (value instanceof Rect) bounds = (Rect) value;
            } catch (Throwable ignored) {
            }
        }
        return bounds;
    }

    private static Rect rectField(Object obj, String name) {
        try {
            Field field = XposedHelpers.findFieldIfExists(obj.getClass(), name);
            if (field == null) return null;
            field.setAccessible(true);
            Object value = field.get(obj);
            return value instanceof Rect ? (Rect) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // The two halves never touch: the divider window straddles the gap between them and each
    // half stops short of it by the inset SystemUI pads that window with. A gap of any other
    // size means the last resize wrote the task rects itself instead of letting WindowManager
    // derive them, which is what slides a half under the divider.
    private static boolean halvesClearDivider(Object dividerView, int primaryEnd,
            int secondaryStart) {
        int gutter = dividerGutter(dividerView);
        if (gutter == Integer.MIN_VALUE) return true;
        if (primaryEnd < 0 || secondaryStart < 0) return true;
        return secondaryStart - primaryEnd == gutter;
    }

    // A released divider belongs on whichever of the three resting points it stopped nearest:
    // the configured ratio, the snap algorithm's own middle target, or the mirror of the
    // setting. Mirroring keeps the narrower half on the share the user configured, so a 27%
    // setting lands on 73%. Only live geometry is read, never the stored setting, so the next
    // split entry still starts from 27%.
    //
    // The catch is that a freshly composed split sits on this middle target too (measured: 974,
    // not the 953 an even share of the two halves gives), so position alone cannot tell "the user
    // let go at half" from "this split has not reached the setting yet". The half-way test below
    // is kept for a layout that really does split the screen evenly.
    private static int snapPosition(Object obj, int configured, int actual) {
        int total = getTotalSize(obj);
        Object view = dividerViewController(obj);
        int middle = middleTargetPosition(view);
        if (total <= 0 || actual < 0 || middle < 0) return configured;
        int mirrored = total - configured;
        int half = halfSplitPosition(obj, total);
        if (half >= 0) {
            if (near(actual, half)) return configured;
            if (nearestTo(actual, middle, configured) && nearestTo(actual, middle, mirrored)) {
                return middle;
            }
        }
        return actual > middle ? mirrored : configured;
    }

    // Whether {@code middle} is at least as close to {@code actual} as {@code other}.
    private static boolean nearestTo(int actual, int middle, int other) {
        return Math.abs(actual - middle) <= Math.abs(actual - other);
    }

    // Where an even share of the screen between the two halves falls, less the gutter between
    // them. Measured on this device a composition does not use it -- it lands on the middle
    // target instead -- so this only catches a layout that really does split evenly. A gutter
    // that is missing or comes out of pre-layout field values answers -1, which stands the 50%
    // resting point down rather than let it claim the mid-point and pin every split at half.
    private static int halfSplitPosition(Object obj, int total) {
        int gutter = dividerGutter(obj);
        if (gutter <= 0 || total <= gutter) return -1;
        return (total - gutter) / 2;
    }

    // The gap the divider window keeps between the two halves: its measured width less the
    // insets it is padded with. The window's measured width is the one that counts --
    // mDividerWindowWidth still holds its pre-layout value through the first splits, and a gap
    // computed from it comes out negative. Integer.MIN_VALUE when neither is readable.
    private static int dividerGutter(Object obj) {
        Object view = dividerViewController(obj);
        int windowWidth = view instanceof View ? ((View) view).getWidth() : 0;
        if (windowWidth <= 0) windowWidth = intField(view, "mDividerWindowWidth");
        int insets = intField(view, "mDividerInsets");
        if (windowWidth <= 0 || insets <= 0) return Integer.MIN_VALUE;
        return windowWidth - 2 * insets;
    }

    private static int middleTargetPosition(Object obj) {
        if (obj == null) return -1;
        try {
            Object snapAlgorithm = XposedHelpers.callMethod(obj, "getSnapAlgorithm");
            Object middle = XposedHelpers.callMethod(snapAlgorithm, "getMiddleTarget");
            return (int) XposedHelpers.getObjectField(middle, "position");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static volatile Method sResizeStackMethod;

    private static Method resizeStackMethod() throws NoSuchMethodException {
        Method method = sResizeStackMethod;
        if (method != null) return method;
        for (Method m : sDividerViewClass.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (!m.getName().equals("resizeStack") || params.length != 3
                    || !isIntClass(params[0]) || !isIntClass(params[1])) continue;
            m.setAccessible(true);
            sResizeStackMethod = m;
            return m;
        }
        throw new NoSuchMethodException("resizeStack(int,int,SnapTarget)");
    }

    // Where the strip itself sits. This is getCurrentPosition()'s own body -- the view's
    // location plus the inset the strip straddles -- rather than a call to that method,
    // because it is one of the methods we rewrite, and its rewrite asks for the live
    // position. With no split on screen the two would call each other on SystemUI's main
    // thread until the stack gives out, which freezes everything the thread draws.
    private static int getLivePosition(Object dividerView) {
        if (!(dividerView instanceof View)) return -1;
        try {
            int[] loc = new int[2];
            ((View) dividerView).getLocationOnScreen(loc);
            boolean horizontal = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(dividerView, "isHorizontalDivision"));
            int position = (horizontal ? loc[1] : loc[0]) + intField(dividerView, "mDividerInsets");
            return position > 0 ? position : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // Endpoints of the dock animation. commitSnapFlags runs when a fling settles, the
    // docked-frame callbacks when the newly placed app renders.
    private static void hookSettledEvents(Class<?> dividerViewClass) {
        String[] events = {"commitSnapFlags", "onDockedFirstAnimationFrame",
                "onRecentsDrawn", "onDockedTopTask"};
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            final String name = method.getName();
            boolean isEvent = false;
            for (String event : events) {
                if (name.equals(event)) isEvent = true;
            }
            if (!isEvent) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log(TAG + ": settled event DividerView." + name);
                    snapToConfiguredPosition(param.thisObject, name);
                }
            });
            XposedBridge.log(TAG + ": hooked settled event DividerView." + name);
        }
    }

    // ── Divider hooks ──

    private static void hookDivider(Class<?> dividerClass) {
        XposedBridge.log(TAG + ": === Divider class methods ===");
        int hookedCount = 0;
        for (Method method : dividerClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] params = method.getParameterTypes();

            StringBuilder sb = new StringBuilder();
            sb.append("  ").append(method.getReturnType().getSimpleName())
                    .append(" ").append(method.getName()).append("(");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            sb.append(")");
            XposedBridge.log(TAG + ": " + sb.toString());

            if (method.getName().equals("onAppTransitionFinished") && params.length == 0) {
                final String dividerEvent = method.getName();
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": settled event Divider." + dividerEvent);
                        Object view = XposedHelpers.callMethod(param.thisObject, "getView");
                        if (view != null) snapToConfiguredPosition(view, dividerEvent);
                    }
                });
                XposedBridge.log(TAG + ": hooked settled event Divider.onAppTransitionFinished");
            }

            if (params.length >= 1 && isIntClass(params[0])) {
                String lowerName = method.getName().toLowerCase();
                if (lowerName.contains("position") || lowerName.contains("bounds")
                        || lowerName.contains("resize") || lowerName.contains("set")) {
                    final String methodName = method.getName();
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!PrefsManager.isSplitScreenEnabled()) return;
                            int desired = getDesiredPosition(param.thisObject);
                            if (desired > 0) {
                                XposedBridge.log(TAG + ": Divider." + methodName
                                        + " " + param.args[0] + " → " + desired);
                                param.args[0] = desired;
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked Divider." + method.getName());
                    hookedCount++;
                }
            }
        }
        XposedBridge.log(TAG + ": === Divider: hooked " + hookedCount + " methods ===");
    }

    // ── calculatePosition hooks ──

    private static void hookCalculatePosition(Class<?> dividerViewClass) {
        boolean hookedCalc = false;
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("calculatePosition")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2) continue;
            if (!isIntClass(params[0]) || !isIntClass(params[1])) continue;
            if (!isIntClass(method.getReturnType())) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;

                    int inputSize = (int) param.args[0];
                    if (inputSize <= 0) return;

                    int desiredPosition = getDesiredPosition(param.thisObject);
                    if (desiredPosition <= 0) return;
                    int originalResult = (int) param.getResult();

                    if (originalResult != desiredPosition) {
                        XposedBridge.log(TAG + ": calculatePosition(" + inputSize
                                + ") " + originalResult + " → " + desiredPosition);
                        param.setResult(desiredPosition);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked calculatePosition(int, int)");
            hookedCalc = true;
        }
        if (!hookedCalc) {
            XposedBridge.log(TAG + ": WARNING: calculatePosition(int, int) NOT found");
        }

        for (Method method : dividerViewClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("calculateXPosition") && !name.equals("calculateYPosition")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !isIntClass(params[0])) continue;
            if (!isIntClass(method.getReturnType())) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;
                    int desired = getDesiredPosition(param.thisObject);
                    if (desired > 0) {
                        param.setResult(desired);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + name + "(int)");
        }
    }

    // ── resizeStack hooks ──

    private static void hookCalculatePositionForInsetBounds(Class<?> dividerViewClass) {
        boolean hooked = false;
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("calculatePositionForInsetBounds")) continue;
            if (method.getParameterCount() != 0) continue;
            if (!isIntClass(method.getReturnType())) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;
                    int desired = getDesiredPosition(param.thisObject);
                    if (desired <= 0) return;
                    int original = (int) param.getResult();
                    XposedBridge.log(TAG + ": calculatePositionForInsetBounds() "
                            + original + " → " + desired);
                    param.setResult(desired);
                }
            });
            XposedBridge.log(TAG + ": hooked calculatePositionForInsetBounds()");
            hooked = true;
            break;
        }
        if (!hooked) {
            XposedBridge.log(TAG + ": WARNING: calculatePositionForInsetBounds() NOT found");
        }
    }

    private static void hookSecondaryBounds(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            if (!method.getName().equals("getNonMinimizedSplitScreenSecondaryBounds")) continue;
            if (method.getParameterCount() != 0) continue;
            if (!Rect.class.isAssignableFrom(method.getReturnType())) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;
                    Object result = param.getResult();
                    if (!(result instanceof Rect)) return;
                    Rect rect = (Rect) result;

                    boolean landscape = isLandscape(param.thisObject);
                    int totalSize = getTotalSize(param.thisObject);
                    if (totalSize <= 0) return;
                    int ratio = landscape
                            ? PrefsManager.getLandscapeRatio()
                            : PrefsManager.getPortraitRatio();
                    int desiredPosition = (int) (totalSize * ratio / 100.0f);
                    // A dragged-past-middle split puts the configured share on the other
                    // side, so this rect has to follow the same value the divider does.
                    int active = getDesiredPosition(param.thisObject);
                    if (active > 0) desiredPosition = active;

                    Rect adjusted = new Rect(rect);
                    int dividerThickness = dividerThicknessOf(param.thisObject);
                    if (landscape) {
                        int oldLeft = rect.left;
                        adjusted.left = desiredPosition + dividerThickness;
                        XposedBridge.log(TAG + ": secondaryBounds.left "
                                + oldLeft + " → " + adjusted.left);
                    } else {
                        int oldTop = rect.top;
                        adjusted.top = desiredPosition + dividerThickness;
                        XposedBridge.log(TAG + ": secondaryBounds.top " + oldTop + " → "
                                + adjusted.top);
                    }
                    param.setResult(adjusted);
                }
            });
            XposedBridge.log(TAG + ": hooked getNonMinimizedSplitScreenSecondaryBounds()");
            break;
        }
    }

    private static int dividerThicknessOf(Object dividerView) {
        try {
            Class<?> clazz = dividerView.getClass();
            while (clazz != null && !clazz.getName().equals("android.view.View")) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getName().equals("mDividerWindowWidth") && isIntClass(f.getType())) {
                        f.setAccessible(true);
                        return f.getInt(dividerView);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void hookResizeStack(Class<?> dividerViewClass) {
        for (Method method : dividerViewClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("resizeStack") && !name.equals("resizeStackDelayed")) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1) continue;

            final String methodName = name;
            final int paramCount = params.length;

            boolean hasIntFirst = params.length >= 1 && isIntClass(params[0]);
            boolean hasSnapTargetOnly = params.length == 1
                    && params[0].getName().contains("SnapTarget");

            if (!hasIntFirst && !hasSnapTargetOnly) {
                XposedBridge.log(TAG + ": skipping " + methodName + " (" + paramCount + " params)");
                continue;
            }

            XposedBridge.log(TAG + ": hooking " + methodName + " (" + paramCount
                    + " params, first=" + params[0].getSimpleName() + ")");

            // Which flag bits mean "this target collapses a side" is the framework's business,
            // not ours: an ordinary ratio target carries FLAG_NONE, which is non-zero as well,
            // so "any flag at all" would also silence the drag-to-snap.
            final int collapseMask = params.length >= 3 ? hideStackFlags(params[2]) : 0;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PrefsManager.isSplitScreenEnabled()) return;

                    int desiredPosition = getDesiredPosition(param.thisObject);
                    if (desiredPosition <= 0) return;

                    if (hasIntFirst) {
                        int originalPosition = (int) param.args[0];
                        Object target = paramCount >= 3 && param.args.length >= 3
                                ? param.args[2] : null;
                        int flag = target != null ? intField(target, "flag") : 0;
                        // Forcing a ratio onto a collapsing target would leave one app
                        // half-dismissed, so that gesture is handed back to the system. With
                        // the constants unreadable, keep the old conservative skip.
                        boolean collapses = flag > 0
                                && (collapseMask != 0 ? (flag & collapseMask) != 0 : true);
                        if (collapses) {
                            XposedBridge.log(TAG + ": " + methodName + " pos=" + originalPosition
                                    + " left alone, collapsing target (flag=" + flag + ")");
                            return;
                        }
                        // Only the two position arguments are rewritten. The SnapTarget is the
                        // snap algorithm's own shared object -- its middle target doubles as the
                        // point a drag is measured against -- so editing it would move the goal
                        // post the next gesture is judged by.
                        boolean changed = false;
                        int originalTaskPosition = paramCount >= 3 && isIntClass(params[1])
                                ? (int) param.args[1] : TASK_POSITION_UNSET;
                        if (originalPosition != desiredPosition) {
                            param.args[0] = desiredPosition;
                            changed = true;
                        }
                        // The second int sizes the two *task* rects, and a drag hands it the
                        // system's own target rather than the position: with it set, resizeStack
                        // computes both task rects itself from 974 and hands WindowManager the
                        // 50% layout under a 73% divider. Clearing it takes resizeStack's other
                        // branch, which sends only the docked-stack rect and lets WindowManager
                        // derive the task rects from it -- the branch the settle call already
                        // relies on, and the reason entering a split never showed this.
                        if (paramCount >= 3 && isIntClass(params[1])
                                && originalTaskPosition != TASK_POSITION_UNSET) {
                            param.args[1] = TASK_POSITION_UNSET;
                            changed = true;
                        }
                        if (changed) {
                            XposedBridge.log(TAG + ": " + methodName + "(" + paramCount
                                    + " args) pos=" + originalPosition
                                    + " taskPos=" + originalTaskPosition + " flag=" + flag
                                    + " → " + desiredPosition);
                        }
                    } else if (hasSnapTargetOnly && param.args[0] != null) {
                        // Nothing to force here: this overload takes its position from the
                        // target, which is the system's object to set.
                        XposedBridge.log(TAG + ": " + methodName
                                + "(SnapTarget) left alone");
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + methodName + " (" + paramCount + " params)");
        }
    }

    private static int hideStackFlags(Class<?> snapTargetClass) {
        int mask = 0;
        for (String name : new String[]{"FLAG_HIDE_DOCKED", "FLAG_HIDE_PRIMARY"}) {
            try {
                Field field = XposedHelpers.findFieldIfExists(snapTargetClass, name);
                if (field != null) mask |= field.getInt(null);
            } catch (Throwable ignored) {
            }
        }
        return mask;
    }

    // ── SnapAlgorithm hooks ──

    private static void hookSnapAlgorithm(Class<?> snapClass) {
        XposedBridge.log(TAG + ": hooking DividerSnapAlgorithm");

        for (Method method : snapClass.getDeclaredMethods()) {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();
            String lowerName = name.toLowerCase();

            if (lowerName.contains("position") || lowerName.contains("fraction")) {
                if (returnType == float.class || returnType == double.class) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!PrefsManager.isSplitScreenEnabled()) return;
                            boolean landscape = isLandscape(param.thisObject);
                            int ratio = landscape
                                    ? PrefsManager.getLandscapeRatio()
                                    : PrefsManager.getPortraitRatio();
                            float fVal = ratio / 100.0f;
                            if (returnType == float.class) {
                                param.setResult(fVal);
                            } else {
                                param.setResult((double) fVal);
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked snapAlgorithm." + name + " (fraction)");
                } else if (isIntClass(returnType)) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length >= 1 && isIntClass(params[0])) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!PrefsManager.isSplitScreenEnabled()) return;
                                int totalSize = (int) param.args[0];
                                if (totalSize <= 0) return;
                                int desired = getDesiredPosition(param.thisObject);
                                if (desired > 0) {
                                    param.setResult(desired);
                                }
                            }
                        });
                        XposedBridge.log(TAG + ": hooked snapAlgorithm." + name + " (int)");
                    }
                }
            }
        }
    }

    // ── Helpers ──

    private static int getDesiredPosition(Object obj) {
        // The drag state and the system's own middle snap target only live on the view, so
        // every caller is resolved to it first. Without it the position cannot be judged and
        // imposing one would fight either the finger or a sibling hook.
        Object view = dividerViewController(obj);
        if (view == null) return 0;
        // While the divider is in the user's hand the finger drives it, so nothing may be
        // imposed on the position.
        if (isDragging(view)) return 0;
        // Every position-forcing path funnels through here, so this single check
        // keeps the whole split at the system default while the picker is up.
        int configured = getConfiguredPosition(obj);
        return configured > 0 && !isSelectingApp(view)
                ? snapPosition(obj, configured, activeSplitPosition(view)) : 0;
    }

    // Where the split is right now. WindowManager is authoritative, but a split the framework
    // built without the view has no task bounds to report yet, and that is exactly the moment
    // the callers here need a position -- the divider window's own frame answers it.
    private static int activeSplitPosition(Object dividerView) {
        int actual = getSplitPositions(dividerView, null);
        return actual > 0 ? actual : getLivePosition(dividerView);
    }

    // Divider, DividerState and DividerSnapAlgorithm callbacks belong to the same divider as
    // the view but cannot answer the questions the decision needs, and they must agree with
    // each other -- two hooks forcing different positions is what produces black bars.
    private static Object dividerViewController(Object obj) {
        if (sDividerViewClass != null && sDividerViewClass.isInstance(obj)) return obj;
        return sDividerView;
    }

    // Pure geometry: where the configured ratio puts the divider. Cheap, so callers can
    // use it to decide whether anything would change at all before asking the picker
    // state, which costs a WindowManager stack query.
    private static int getConfiguredPosition(Object obj) {
        boolean landscape = isLandscape(obj);
        int ratio = landscape
                ? PrefsManager.getLandscapeRatio()
                : PrefsManager.getPortraitRatio();
        int totalSize = getTotalSize(obj);
        if (totalSize <= 0) return 0;
        return (int) (totalSize * ratio / 100.0f);
    }

    private static int getActualScreenPosition(Object dividerView) {
        if (!(dividerView instanceof View)) return -1;
        try {
            int[] loc = new int[2];
            ((View) dividerView).getLocationOnScreen(loc);
            boolean landscape = isLandscape(dividerView);
            int pos = landscape ? loc[0] : loc[1];
            if (pos <= 0) return -1;
            return pos;
        } catch (Throwable t) {
            return -1;
        }
    }

    // resizeStack's dockSide must match the actual split direction, otherwise the
    // resized task bounds don't line up with the divider and leave black bars.
    private static int resolveDockSide(Object dividerView) {
        try {
            Class<?> clazz = dividerView.getClass();
            while (clazz != null && !clazz.getName().equals("android.view.View")) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getName().equals("mDockSide") && isIntClass(f.getType())) {
                        f.setAccessible(true);
                        int side = f.getInt(dividerView);
                        if (side >= 0) {
                            XposedBridge.log(TAG + ": dockSide from mDockSide=" + side);
                            return side;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        int side = isLandscape(dividerView) ? 1 : 3;
        XposedBridge.log(TAG + ": dockSide from orientation=" + side);
        return side;
    }

    private static boolean isLandscape(Object obj) {
        try {
            for (Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals("isLandscape") && m.getParameterCount() == 0
                        && isBoolClass(m.getReturnType())) {
                    m.setAccessible(true);
                    return (boolean) m.invoke(obj);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Object config = XposedHelpers.callMethod(obj, "getConfiguration");
            if (config instanceof Configuration) {
                return ((Configuration) config).orientation == Configuration.ORIENTATION_LANDSCAPE;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object context = XposedHelpers.callMethod(obj, "getContext");
            if (context instanceof android.content.Context) {
                return ((android.content.Context) context).getResources().getConfiguration()
                        .orientation == Configuration.ORIENTATION_LANDSCAPE;
            }
        } catch (Throwable ignored) {
        }

        return true;
    }

    private static int getTotalSize(Object obj) {
        try {
            for (Method m : obj.getClass().getDeclaredMethods()) {
                String name = m.getName();
                if ((name.equals("getTotalSize") || name.equals("getDisplaySize")
                        || name.equals("getBoundsSize"))
                        && m.getParameterCount() == 0
                        && isIntClass(m.getReturnType())) {
                    m.setAccessible(true);
                    int result = (int) m.invoke(obj);
                    if (result > 0) return result;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Object context = XposedHelpers.callMethod(obj, "getContext");
            if (context instanceof android.content.Context) {
                android.util.DisplayMetrics dm =
                        ((android.content.Context) context).getResources().getDisplayMetrics();
                boolean landscape = isLandscape(obj);
                int size = landscape ? dm.widthPixels : dm.heightPixels;
                if (size > 0) return size;
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }
}
