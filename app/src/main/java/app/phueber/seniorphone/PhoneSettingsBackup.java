package app.phueber.seniorphone;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saving and restoring SeniorPhone's settings (PhoneBackup), the one place
 * both the Settings screen and BackupProvider (SeniorLauncher's "all Senior
 * apps") go through, so both behave the same. Does contact I/O: never on the
 * main thread.
 */
final class PhoneSettingsBackup {
    /** Restore refused: favourites are in the file, but the app may not read or write contacts. */
    static final String ERROR_NO_CONTACTS = "no_contacts";

    private PhoneSettingsBackup() {}

    /** The outcome of a restore. */
    static final class Result {
        /** Null when restored, else a Backup.ERROR_* code or ERROR_NO_CONTACTS. */
        final String error;
        /** Restored, but not found on this phone: favourites' names. */
        final List<String> skipped;

        Result(String error, List<String> skipped) {
            this.error = error;
            this.skipped = skipped;
        }
    }

    /**
     * This app's one-entry file. Without the contacts permission the file has
     * no favourites at all (not an empty list), so restoring it leaves them alone.
     */
    static String export(Context c) {
        Store store = new Store(c);
        List<PhoneBackup.Favourite> favourites = null;
        if (Contacts.canRead(c)) {
            favourites = new ArrayList<>();
            Map<Long, List<String>> numbers = numbers(c.getContentResolver());
            List<ContactInfo> starred = new ArrayList<>();
            for (ContactInfo i : Contacts.list(c.getContentResolver())) if (i.starred) starred.add(i);
            ContactList.sort(starred, java.text.Collator.getInstance());
            for (ContactInfo i : starred) {
                List<String> own = numbers.get(i.id);
                favourites.add(new PhoneBackup.Favourite(i.name, own == null ? new ArrayList<>() : own));
            }
        }
        PhoneBackup.Settings s = new PhoneBackup.Settings(store.theme(), favourites);
        return Backup.writeOne(BackupFiles.now(), c.getPackageName(), BackupFiles.appVersion(c),
                PhoneBackup.toMap(s));
    }

    /**
     * Replaces the theme and the favourites with the file's. All or nothing:
     * the file is read completely before anything changes, and refused while
     * locked or, when it has favourites, without the contacts permission.
     */
    static Result restore(Context c, String json) {
        PhoneBackup.Settings s;
        try {
            s = PhoneBackup.fromMap(Backup.settingsFor(json, c.getPackageName()));
        } catch (Backup.Failure f) {
            return new Result(f.code, null);
        }
        Store store = new Store(c);
        if (store.locked()) return new Result(Backup.ERROR_LOCKED, null);
        List<String> skipped = new ArrayList<>();
        if (s.favourites != null) {
            if (!Contacts.canRead(c) || !Contacts.canWrite(c)) return new Result(ERROR_NO_CONTACTS, null);
            ContentResolver cr = c.getContentResolver();
            Map<Long, List<String>> numbers = numbers(cr);
            List<PhoneBackup.Candidate> candidates = new ArrayList<>();
            Set<Long> starredNow = new HashSet<>();
            for (ContactInfo i : Contacts.list(cr)) {
                candidates.add(new PhoneBackup.Candidate(i.id, i.name, numbers.get(i.id)));
                if (i.starred) starredNow.add(i.id);
            }
            PhoneBackup.Match m = PhoneBackup.match(s.favourites, candidates, Contacts.home(c));
            Set<Long> wanted = new HashSet<>(m.ids);
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            for (long id : starredNow) if (!wanted.contains(id)) ops.add(star(id, false));
            for (long id : wanted) if (!starredNow.contains(id)) ops.add(star(id, true));
            if (!ops.isEmpty()) {
                try {
                    cr.applyBatch(ContactsContract.AUTHORITY, ops);
                } catch (Exception e) {
                    return new Result(ERROR_NO_CONTACTS, null);
                }
            }
            skipped.addAll(m.unmatched);
            PhoneWidgets.changed(c);
        }
        if (s.theme != store.theme()) store.setTheme(s.theme);
        return new Result(null, skipped);
    }

    private static ContentProviderOperation star(long id, boolean on) {
        return ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id))
                .withValue(ContactsContract.Contacts.STARRED, on ? 1 : 0)
                .build();
    }

    /** Every contact's numbers, each written once, by contact id. */
    private static Map<Long, List<String>> numbers(ContentResolver cr) {
        Map<Long, List<String>> out = new HashMap<>();
        Map<Long, Set<String>> seen = new HashMap<>();
        try (Cursor c = cr.query(Phone.CONTENT_URI, new String[] {Phone.CONTACT_ID, Phone.NUMBER},
                null, null, Phone.IS_SUPER_PRIMARY + " DESC, " + Phone.IS_PRIMARY + " DESC, " + Phone._ID)) {
            while (c != null && c.moveToNext()) {
                String number = c.getString(1);
                String key = ContactForm.numberKey(number);
                if (key.isEmpty()) continue;
                long id = c.getLong(0);
                Set<String> s = seen.computeIfAbsent(id, k -> new HashSet<>());
                if (!s.add(key)) continue;
                out.computeIfAbsent(id, k -> new ArrayList<>()).add(number.trim());
            }
        } catch (RuntimeException ignored) {
            // permission revoked meanwhile: no numbers, so matches by name only
        }
        return out;
    }

    /** The helper's sentence for a refused restore. */
    static String errorText(Context c, String code) {
        if (Backup.ERROR_NEWER.equals(code)) return c.getString(R.string.backup_error_newer);
        if (Backup.ERROR_NO_ENTRY.equals(code)) {
            return c.getString(R.string.backup_error_no_entry, c.getString(R.string.backup_app_name));
        }
        if (Backup.ERROR_LOCKED.equals(code)) return c.getString(R.string.backup_error_locked);
        if (ERROR_NO_CONTACTS.equals(code)) return c.getString(R.string.backup_error_no_contacts);
        return c.getString(R.string.backup_error_malformed);
    }
}
