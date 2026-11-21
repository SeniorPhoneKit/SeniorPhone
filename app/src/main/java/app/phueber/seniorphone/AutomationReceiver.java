package app.phueber.seniorphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The helper's settings by automation (see {@link Automation}): LOCK, UNLOCK,
 * SET_THEME. The lock is a guard rail against mistakes, not security, so these
 * work while locked, like the helper would. Store does the work, so the open
 * screens follow (they listen to Store) as if Settings had done it.
 *
 * LOCK is ignored when SeniorLauncher is installed (one lock for the whole
 * phone, set on the home screen, as Settings explains) and while already
 * locked (it would silently replace the PIN the helper knows). UNLOCK lifts
 * the launcher's lock too, through its PIN, like the padlock here.
 */
public final class AutomationReceiver extends BroadcastReceiver {
    private static final String TAG = "AutomationReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = Automation.action(intent != null ? intent.getAction() : null,
                Automation.LOCK, Automation.UNLOCK, Automation.SET_THEME);
        if (action == null) {
            Log.w(TAG, "unknown action");
            return;
        }
        String pin = intent.getStringExtra(Automation.EXTRA_PIN);
        String theme = intent.getStringExtra(Automation.EXTRA_THEME);
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        // The launcher's lock is a content provider call and Store commits to disk.
        new Thread(() -> {
            try {
                run(app, action, pin == null ? null : pin.trim(), theme);
            } catch (Throwable t) {
                Log.e(TAG, action, t);
            } finally {
                result.finish();
            }
        }, "automation").start();
    }

    private static void run(Context c, String action, String pin, String theme) {
        Store store = new Store(c);
        if (Automation.LOCK.equals(action)) {
            if (!Pin.isValid(pin)) {
                Log.w(TAG, "LOCK needs a PIN of 4 to 8 digits");
            } else if (LauncherLock.present(c)) {
                Log.w(TAG, "LOCK ignored: SeniorLauncher's lock applies; lock the home screen");
            } else if (store.ownLock()) {
                Log.w(TAG, "LOCK ignored: already locked");
            } else {
                store.lock(pin);
            }
        } else if (Automation.UNLOCK.equals(action)) {
            if (pin == null || pin.isEmpty() || !store.unlock(pin)) Log.w(TAG, "UNLOCK: wrong PIN");
        } else if (Automation.SET_THEME.equals(action)) {
            Palette.Theme t = Automation.parseTheme(theme);
            if (t == null) Log.w(TAG, "SET_THEME: unknown theme");
            else store.setTheme(t);
        }
    }
}
