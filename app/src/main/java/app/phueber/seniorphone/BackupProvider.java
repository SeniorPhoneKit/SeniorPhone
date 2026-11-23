package app.phueber.seniorphone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * SeniorLauncher's door to this app's settings backup, so the helper can save
 * and restore every Senior app at once there.
 *
 * content://app.phueber.seniorphone.backup
 * call("export", null, null) -> Bundle with string "json": this app's one-entry file.
 * call("import", json, null) -> Bundle with boolean "ok"; when restored, string[]
 * "skipped" (favourites not found on this phone, ready to show); when refused,
 * string "error" (a Backup.ERROR_* code, or "no_contacts") and string
 * "message" (the sentence for the helper, in this app's language).
 *
 * Only SeniorLauncher and this app itself are answered (Backup.callerAllowed):
 * a sanity check so no other app reads the favourites, not security.
 */
public final class BackupProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        if (c == null) throw new IllegalStateException("not attached");
        Bundle out = new Bundle();
        if (!Backup.callerAllowed(getCallingPackage(), c.getPackageName())) {
            out.putString(Backup.EXTRA_ERROR, Backup.ERROR_NOT_ALLOWED);
            return out;
        }
        if (Backup.METHOD_EXPORT.equals(method)) {
            out.putString(Backup.EXTRA_JSON, PhoneSettingsBackup.export(c));
            return out;
        }
        if (!Backup.METHOD_IMPORT.equals(method)) throw new UnsupportedOperationException(method);
        PhoneSettingsBackup.Result r = PhoneSettingsBackup.restore(c, arg);
        out.putBoolean(Backup.EXTRA_OK, r.error == null);
        if (r.error == null) {
            out.putStringArray(Backup.EXTRA_SKIPPED, r.skipped.toArray(new String[0]));
        } else {
            out.putString(Backup.EXTRA_ERROR, r.error);
            out.putString("message", PhoneSettingsBackup.errorText(c, r.error));
        }
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException("call only");
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("call only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("call only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("call only");
    }
}
