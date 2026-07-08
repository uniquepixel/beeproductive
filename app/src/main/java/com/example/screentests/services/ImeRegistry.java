package com.example.screentests.services;

import android.content.Context;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * AI-added: knows which installed packages are soft keyboards (IMEs).
 *
 * When the soft keyboard opens, Android fires a TYPE_WINDOW_STATE_CHANGED accessibility event
 * carrying the keyboard's package name (e.g. com.samsung.android.honeyboard on Galaxy devices).
 * Treating that as an app switch broke the intervention overlay: the keyboard was classified
 * UNKNOWN, the categorization overlay popped up over the chat and stole focus, so the text box
 * closed itself the moment the user started typing. Emulators typically run with hardware
 * keyboard input enabled, so the soft keyboard never appears there and the bug never reproduced.
 *
 * Packages are resolved via InputMethodManager instead of name heuristics so any keyboard
 * (Samsung, Gboard, SwiftKey, ...) is recognized on any device.
 */
public final class ImeRegistry {

    /** How long a cache miss is trusted before the installed-IME list is re-read. */
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000L;

    // Immutable set swapped atomically; read from the main thread (accessibility events)
    // and the engine's dbExecutor.
    private static volatile Set<String> imePackages = Collections.emptySet();
    private static volatile long lastRefreshMillis = 0L;

    private ImeRegistry() {
    }

    /** True if {@code packageName} belongs to an installed input method (soft keyboard). */
    public static boolean isImePackage(Context context, String packageName) {
        if (imePackages.contains(packageName)) return true;
        // On a miss, re-read the list at most every REFRESH_INTERVAL_MS in case a keyboard
        // was installed since the last refresh.
        if (System.currentTimeMillis() - lastRefreshMillis < REFRESH_INTERVAL_MS) return false;
        return refresh(context).contains(packageName);
    }

    /** Re-reads the installed IME list; cheap enough to call from onServiceConnected. */
    public static Set<String> refresh(Context context) {
        lastRefreshMillis = System.currentTimeMillis();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return imePackages;
        Set<String> fresh = new HashSet<>();
        for (InputMethodInfo info : imm.getInputMethodList()) {
            fresh.add(info.getPackageName());
        }
        imePackages = fresh;
        return fresh;
    }
}
