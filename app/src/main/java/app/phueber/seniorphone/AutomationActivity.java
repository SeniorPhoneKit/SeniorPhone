package app.phueber.seniorphone;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen-opening automation commands (see {@link Automation}): an
 * invisible trampoline that finds the contact off the main thread, opens the
 * right screen and closes. It never places a call: CALL opens the contact page
 * or the number pad with the usual "Call X?" question, so placing the call
 * still takes the senior's (or helper's) tap, exactly like ACTION_DIAL.
 *
 * Without the contacts permission no contact is found: a number then goes to
 * the number pad, a name to the contact list (which asks for the permission).
 */
public final class AutomationActivity extends Activity {
    private static final String TAG = "AutomationActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) { // recreated: the command already ran
            finish();
            return;
        }
        Intent in = getIntent();
        String action = Automation.action(in != null ? in.getAction() : null, Automation.CALL,
                Automation.OPEN_CONTACT, Automation.OPEN_DIALPAD, Automation.OPEN_CONTACTS);
        if (action == null) {
            Log.w(TAG, "unknown action " + (in != null ? in.getAction() : null));
            finish();
            return;
        }
        String number = Automation.parseNumber(in.getStringExtra(Automation.EXTRA_NUMBER));
        String name = Automation.parseName(in.getStringExtra(Automation.EXTRA_NAME));

        if (Automation.OPEN_DIALPAD.equals(action)) {
            open(dialPad(number, false));
        } else if (Automation.OPEN_CONTACTS.equals(action)) {
            open(contactList(name));
        } else if (number == null && name == null) {
            Log.w(TAG, action + " without a number or name");
            finish();
        } else {
            boolean call = Automation.CALL.equals(action);
            ContentResolver cr = getContentResolver();
            boolean canRead = Contacts.canRead(this);
            Contacts.BACKGROUND.execute(() -> {
                long id = !canRead ? -1 : number != null ? byNumber(cr, number) : byName(cr, name);
                Contacts.MAIN.post(() -> open(target(call, id, number, name)));
            });
        }
    }

    /** Where a CALL or OPEN_CONTACT goes, once the contact is known (id) or not (-1). */
    private Intent target(boolean call, long id, String number, String name) {
        if (id >= 0) {
            Intent i = new Intent(this, ContactActivity.class).putExtra(ContactActivity.EXTRA_ID, id);
            if (call) i.putExtra(ContactActivity.EXTRA_ASK_CALL, number != null ? number : "");
            return i;
        }
        // Nobody in the address book: a number on the pad, a name searched in the list.
        if (number != null) return dialPad(number, call);
        return contactList(name);
    }

    private Intent dialPad(String number, boolean ask) {
        // An open number pad gets the new number and question (onNewIntent).
        Intent i = new Intent(this, DialActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (number != null) {
            i.setAction(Intent.ACTION_DIAL).setData(Uri.fromParts("tel", number, null));
            if (ask) i.putExtra(DialActivity.EXTRA_ASK_CALL, true);
        }
        return i;
    }

    private Intent contactList(String search) {
        // A fresh list: nothing left over from an earlier command stays under it.
        Intent i = new Intent(this, ContactsActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (search != null) i.putExtra(ContactsActivity.EXTRA_SEARCH, search);
        return i;
    }

    private void open(Intent i) {
        try {
            if (ContactActivity.class.getName().equals(i.getComponent().getClassName())) {
                // The page over a fresh list (ContactActivity.overList): the page and its
                // question are always the ones asked for, and Back from it goes to the
                // list, never to a page from an earlier command.
                startActivities(ContactActivity.overList(this, i));
            } else {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot open " + i, e);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * The contact with this number, or -1. Android's own matching first; it
     * depends on the phone's country, so "0170 …" may miss a contact saved as
     * "+49 170 …": then the national / country-code comparison (Automation.pickPhone). Background thread.
     */
    private static long byNumber(ContentResolver cr, String number) {
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor c = cr.query(uri, new String[] { ContactsContract.PhoneLookup._ID }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "number lookup", e);
        }
        List<Long> ids = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        String[] cols = { ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER };
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                ids.add(c.getLong(0));
                numbers.add(c.getString(1));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "number scan", e);
        }
        int i = Automation.pickPhone(numbers, number);
        return i < 0 ? -1 : ids.get(i);
    }

    /** The one contact this name means (Automation.pickContact), or -1. Background thread. */
    private static long byName(ContentResolver cr, String name) {
        List<ContactInfo> all = new ArrayList<>(Contacts.list(cr));
        ContactInfo c = Automation.pickContact(all, name);
        return c != null ? c.id : -1;
    }
}
