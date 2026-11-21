package app.phueber.seniorphone;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import java.util.Locale;

/**
 * Whether SeniorNav's "Back & Home" bar is switched on. Its big Back and Home
 * sit at the bottom of every screen, so our own pinned Back button would only
 * repeat them: it is hidden while the bar is on and comes back when the bar is
 * turned off.
 *
 * SeniorNav publishes whether its bar is visible right now (also paused by
 * automation, e.g. for a full-screen app) through a read-only provider, one
 * row with the column "visible", and notifies changes; while the view is on
 * screen we follow it live. An older SeniorNav without the provider: whether
 * its service is switched on, checked whenever the screen gets focus.
 */
final class NavBar {
    static final String PACKAGE = "app.phueber.seniornav";
    static final Uri STATE = Uri.parse("content://app.phueber.seniornav.bar/state");

    private NavBar() {}

    /** Pure: whether the enabled-services setting lists SeniorNav's bar service. */
    static boolean listed(String enabledServices) {
        if (enabledServices == null) return false;
        for (String entry : enabledServices.split(":")) {
            if (entry.trim().toLowerCase(Locale.ROOT).startsWith("app.phueber.seniornav/")) return true;
        }
        return false;
    }

    static boolean on(Context c) {
        Boolean visible = visible(c);
        if (visible != null) return visible;
        try {
            return listed(Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        } catch (RuntimeException e) {
            return false; // unreadable: keep our own Back
        }
    }

    /**
     * The bar's own answer: visible or not; null when SeniorNav (the real one,
     * by package) has no such provider or gives no answer.
     */
    private static Boolean visible(Context c) {
        try {
            ProviderInfo p = c.getPackageManager().resolveContentProvider(STATE.getAuthority(), 0);
            if (p == null || !PACKAGE.equals(p.packageName)) return null;
            try (Cursor cur = c.getContentResolver().query(STATE, new String[] { "visible" }, null, null, null)) {
                if (cur == null || !cur.moveToFirst()) return null;
                int i = cur.getColumnIndex("visible");
                return i < 0 || cur.isNull(i) ? null : cur.getInt(i) != 0;
            }
        } catch (RuntimeException e) {
            return null; // SeniorNav dying or being updated: fall back
        }
    }

    /**
     * Hides {@code back} while the bar is on: re-checked each time the window
     * gets focus, and on every change SeniorNav reports while the view is
     * attached.
     */
    static void hideWhileOn(View back) {
        Runnable update = () -> back.setVisibility(on(back.getContext()) ? View.GONE : View.VISIBLE);
        update.run();
        back.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
            if (focused) update.run();
        });
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                update.run();
            }
        };
        back.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                try {
                    v.getContext().getContentResolver().registerContentObserver(STATE, false, observer);
                } catch (RuntimeException ignored) {
                    // no SeniorNav provider: the focus check above remains
                }
                update.run();
            }

            @Override public void onViewDetachedFromWindow(View v) {
                v.getContext().getContentResolver().unregisterContentObserver(observer);
            }
        });
    }
}
