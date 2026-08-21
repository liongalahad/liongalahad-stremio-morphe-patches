package com.stremio.morphe;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

public final class MorpheNavBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean accountsFocused;
    private static WeakReference<Object> menuFocusState = new WeakReference<Object>(null);
    private static WeakReference<MorpheAccountsNavView> accountsView =
            new WeakReference<MorpheAccountsNavView>(null);

    private MorpheNavBridge() {}

    public static void onMenuFocusChanged(final Object state, boolean hasFocus) {
        menuFocusState = new WeakReference<Object>(state);
        setState(state, hasFocus);
        MorpheAccountsNavView view = accountsView.get();
        if (view != null) view.setNativeMenuExpanded(hasFocus);
        if (!hasFocus) {
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    if (accountsFocused) setState(state, true);
                }
            }, 80L);
        }
    }

    public static void setAccountsFocused(boolean focused) {
        accountsFocused = focused;
        Object state = menuFocusState.get();
        if (state != null) setState(state, focused);
    }

    public static void registerAccountsView(MorpheAccountsNavView view) {
        accountsView = new WeakReference<MorpheAccountsNavView>(view);
    }

    private static void setState(Object state, boolean value) {
        if (state == null) return;
        try {
            for (Method method : state.getClass().getMethods()) {
                if ("setValue".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    method.invoke(state, Boolean.valueOf(value));
                    return;
                }
            }
        } catch (Exception ignored) {
            // The explicit backdrop still keeps the rail visually complete if reflection is unavailable.
        }
    }
}
