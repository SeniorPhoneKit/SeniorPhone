package app.phueber.seniorphone;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The helper's settings: lock the contacts, pick the colours, make this the
 * phone app, open the phone's own settings. Each option has one sentence under
 * it that says what it is for (Bleta's pattern). Everything else (ringtone,
 * volume, text size) is already in the phone's settings and is not duplicated.
 */
public final class SettingsActivity extends Activity {
    private static final String STATE_DECLINED = "declined";
    private static final String STATE_RESULT = "backup_result";
    private static final int REQUEST_SAVE = 41;
    private static final int REQUEST_LOAD = 42;

    private Store store;
    private android.app.Dialog dialog;
    /** Filled by showPhoneApp(): its state can change while we are away (Android's own question). */
    private LinearLayout phoneApp;
    /** Android's question came back without "yes": also offer the default-apps page. */
    private boolean declined;
    /** The Backup section; hidden while locked, like the rest of Settings' changes. */
    private LinearLayout backup;
    /** A restore's result, shown once the screen is rebuilt in the restored colours. */
    private String pendingResult;
    private Palette.Theme builtTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        builtTheme = Ui.applyTheme(this);
        store = new Store(this);
        declined = savedInstanceState != null && savedInstanceState.getBoolean(STATE_DECLINED);
        pendingResult = savedInstanceState == null ? null : savedInstanceState.getString(STATE_RESULT);
        LinearLayout content = Ui.screen(this, getString(R.string.settings_title));

        if (LauncherLock.present(this)) {
            // SeniorLauncher is installed: one lock for the whole phone, set on the
            // home screen. A second lock here would only confuse.
            content.addView(heading(getString(R.string.settings_lock)), Ui.fullWidth(this, 8));
            content.addView(explanation(getString(store.lockedByLauncher()
                    ? R.string.settings_lock_with_launcher_on
                    : R.string.settings_lock_with_launcher_off)), Ui.fullWidth(this, 8));
        } else {
            // Lock: asks for a new PIN (twice), like SeniorAlert. Typing a PIN is
            // the guard against locking by accident, so a plain tap opens it.
            Button lock = Ui.button(this, getString(R.string.settings_lock), R.color.green);
            lock.setOnClickListener(v -> {
                dismissDialog();
                dialog = PinDialog.choose(this, pin -> {
                    store.lock(pin);
                    finish(); // back to the contact list, which now shows only the padlock
                });
            });
            content.addView(lock, Ui.fullWidth(this, 8));
            content.addView(explanation(getString(R.string.settings_lock_explain)), Ui.fullWidth(this, 8));
        }

        // Colours: a few fixed, tested themes, no free picker (see Palette).
        // The chosen one is green with a tick; the others are outlined.
        content.addView(heading(getString(R.string.settings_colours)), Ui.fullWidth(this, 36));
        Palette.Theme current = store.theme();
        for (Palette.Theme t : Palette.Theme.values()) {
            String name = getString(themeName(t));
            Button b = t == current
                    ? Ui.button(this, getString(R.string.settings_colour_chosen, name), R.color.green)
                    : Ui.outlined(this, name);
            b.setSelected(t == current);
            b.setOnClickListener(v -> {
                if (t == store.theme()) return;
                store.setTheme(t);
                recreate(); // redraw this screen in the new colours at once
            });
            content.addView(b, Ui.fullWidth(this, 8));
        }
        content.addView(explanation(getString(R.string.settings_colours_explain)), Ui.fullWidth(this, 8));

        // Phone app: the state in words, and the way to change it (see Role).
        content.addView(heading(getString(R.string.settings_phone_app)), Ui.fullWidth(this, 36));
        phoneApp = new LinearLayout(this);
        phoneApp.setOrientation(LinearLayout.VERTICAL);
        content.addView(phoneApp, Ui.fullWidth(this, 0));
        content.addView(explanation(getString(R.string.settings_phone_app_explain)), Ui.fullWidth(this, 8));

        Button phone = Ui.button(this, getString(R.string.settings_phone), R.color.blue);
        phone.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                // No settings screen to open.
            }
        });
        content.addView(phone, Ui.fullWidth(this, 36));
        content.addView(explanation(getString(R.string.settings_phone_explain)), Ui.fullWidth(this, 8));

        // Backup: the settings to a file and back (PhoneSettingsBackup), through
        // the system's file picker. Never while locked.
        backup = new LinearLayout(this);
        backup.setOrientation(LinearLayout.VERTICAL);
        backup.addView(heading(getString(R.string.backup_title)), Ui.fullWidth(this, 0));
        Button save = Ui.button(this, getString(R.string.backup_save), R.color.blue);
        save.setOnClickListener(v -> pick(BackupFiles.saveIntent(
                Backup.fileName(getString(R.string.backup_app_name), BackupFiles.today())), REQUEST_SAVE));
        backup.addView(save, Ui.fullWidth(this, 8));
        Button load = Ui.button(this, getString(R.string.backup_load), R.color.blue);
        load.setOnClickListener(v -> pick(BackupFiles.openIntent(), REQUEST_LOAD));
        backup.addView(load, Ui.fullWidth(this, 8));
        backup.addView(explanation(getString(R.string.backup_explain)), Ui.fullWidth(this, 8));
        content.addView(backup, Ui.fullWidth(this, 36));
    }

    private void pick(Intent intent, int request) {
        if (store.locked()) {
            showBackup();
            return;
        }
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            show(getString(R.string.backup_no_picker));
        }
    }

    private void showBackup() {
        if (backup != null) backup.setVisibility(store.locked() ? View.GONE : View.VISIBLE);
    }

    private void show(CharSequence text) {
        dismissDialog();
        dialog = Ui.message(this, text);
    }

    /** Writes the file off the main thread (contacts are read for it). */
    private void save(Uri uri) {
        Context app = getApplicationContext();
        Contacts.BACKGROUND.execute(() -> {
            boolean ok;
            try {
                BackupFiles.write(app, uri, PhoneSettingsBackup.export(app));
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            boolean saved = ok;
            Contacts.MAIN.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    show(getString(saved ? R.string.backup_saved : R.string.backup_error_save));
                }
            });
        });
    }

    /** Reads the file, asks, then restores; nothing changes before the Replace. */
    private void load(Uri uri) {
        Context app = getApplicationContext();
        Contacts.BACKGROUND.execute(() -> {
            String json;
            String problem = null;
            try {
                json = BackupFiles.read(app, uri);
                Backup.settingsFor(json, app.getPackageName());
            } catch (Backup.Failure f) {
                json = null;
                problem = PhoneSettingsBackup.errorText(app, f.code);
            } catch (Exception e) {
                json = null;
                problem = app.getString(R.string.backup_error_read);
            }
            String file = json, error = problem;
            Contacts.MAIN.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    show(error);
                    return;
                }
                dismissDialog();
                dialog = Ui.confirm(this, getString(R.string.backup_confirm),
                        getString(R.string.backup_replace), R.color.red, () -> restore(file));
            });
        });
    }

    private void restore(String json) {
        if (store.locked()) {
            showBackup();
            return;
        }
        Context app = getApplicationContext();
        Contacts.BACKGROUND.execute(() -> {
            PhoneSettingsBackup.Result r = PhoneSettingsBackup.restore(app, json);
            Contacts.MAIN.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (r.error != null) {
                    show(PhoneSettingsBackup.errorText(this, r.error));
                    return;
                }
                String text = getString(R.string.backup_restored);
                if (!r.skipped.isEmpty()) {
                    text += "\n" + getString(R.string.backup_not_found, String.join(", ", r.skipped));
                }
                if (builtTheme != store.theme()) {
                    // The new colours: rebuild, then tell the helper on the new screen.
                    pendingResult = text;
                    recreate();
                } else {
                    show(text);
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        showBackup();
        if (pendingResult != null) {
            show(pendingResult);
            pendingResult = null;
        }
        showPhoneApp(); // also after coming back from Android's question or the default-apps page
    }

    @Override
    protected void onStop() {
        super.onStop();
        dismissDialog(); // a half-typed PIN does not wait for the next visit
    }

    private void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_DECLINED, declined);
        if (pendingResult != null) out.putString(STATE_RESULT, pendingResult);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE || requestCode == REQUEST_LOAD) {
            Uri uri = data == null ? null : data.getData();
            if (resultCode != RESULT_OK || uri == null) return; // the helper cancelled
            if (store.locked()) { // locked while the picker was open
                showBackup();
                return;
            }
            if (requestCode == REQUEST_SAVE) save(uri);
            else load(uri);
            return;
        }
        if (requestCode != Role.REQUEST_CODE) return;
        // Android 10+ stops showing its question after two "no"s and answers
        // at once; then the default-apps page is the only way left.
        declined = !Role.isDefault(this);
        showPhoneApp();
    }

    private void showPhoneApp() {
        if (phoneApp == null) return;
        phoneApp.removeAllViews();
        TextView state;
        if (Role.isDefault(this)) {
            state = Ui.bold(this, getString(R.string.settings_phone_app_yes), Ui.TEXT_BUTTON);
            state.setTextColor(Ui.color(this, R.color.green));
            phoneApp.addView(state, Ui.fullWidth(this, 8));
            return;
        }
        if (!Role.isAvailable(this)) {
            state = Ui.bold(this, getString(R.string.settings_phone_app_unavailable), Ui.TEXT_BUTTON);
            state.setTextColor(Ui.color(this, R.color.text_muted));
            phoneApp.addView(state, Ui.fullWidth(this, 8));
            return;
        }
        state = Ui.bold(this, getString(R.string.settings_phone_app_no), Ui.TEXT_BUTTON);
        state.setTextColor(Ui.color(this, R.color.red_text));
        phoneApp.addView(state, Ui.fullWidth(this, 8));

        Button make = Ui.button(this, getString(R.string.settings_phone_app_make), R.color.green);
        make.setOnClickListener(v -> {
            if (!Role.request(this)) {
                declined = true; // this phone has no such question: go to the list
                Role.openDefaultAppsSettings(this);
            }
        });
        phoneApp.addView(make, Ui.fullWidth(this, 8));
        phoneApp.addView(Steps.button(this, R.string.steps_role_title, R.array.steps_role,
                R.array.steps_role_notes, new Steps.Action(R.string.settings_phone_app_make, () -> {
                    if (!Role.request(this)) {
                        declined = true;
                        Role.openDefaultAppsSettings(this);
                    }
                }), new Steps.Action(R.string.settings_phone_app_choose,
                        () -> Role.openDefaultAppsSettings(this))), Ui.fullWidth(this, 8));

        if (declined) {
            Button choose = Ui.outlined(this, getString(R.string.settings_phone_app_choose));
            choose.setOnClickListener(v -> Role.openDefaultAppsSettings(this));
            phoneApp.addView(choose, Ui.fullWidth(this, 8));
        }
    }

    private static int themeName(Palette.Theme t) {
        switch (t) {
            case DARK: return R.string.settings_colour_dark;
            case HIGH_CONTRAST: return R.string.settings_colour_high_contrast;
            case COLOURFUL: return R.string.settings_colour_colourful;
            default: return R.string.settings_colour_light;
        }
    }

    private TextView heading(CharSequence s) {
        TextView t = Ui.bold(this, s, Ui.TEXT_BUTTON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) t.setAccessibilityHeading(true);
        return t;
    }

    private TextView explanation(CharSequence s) {
        TextView t = Ui.text(this, s, Ui.TEXT_BODY);
        t.setTextColor(Ui.color(this, R.color.text_muted));
        t.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        return t;
    }
}
