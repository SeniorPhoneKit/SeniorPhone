package app.phueber.seniorphone;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.text.format.DateUtils;
import android.util.Log;

/**
 * Missed calls. As the phone app, Telecom leaves the missed-call notification
 * to us (ACTION_SHOW_MISSED_CALLS_NOTIFICATION) instead of posting its own.
 * One big notification: the caller's name (or number) as the title, so it
 * reads well on a home screen's notification card, and "Missed call · 14:05"
 * below. A tap opens the person's page (or the number pad with the number);
 * Call back asks "Call Maria Schmidt?" first, like every call from the app.
 *
 * Opening it, Call back, or swiping it away tells Telecom the calls were
 * seen (cancelMissedCallsNotification), which also marks them read in the
 * call log. Count 0 from Telecom removes ours.
 */
public final class MissedCalls extends BroadcastReceiver {
    private static final String TAG = "MissedCalls";
    private static final String CHANNEL = "missed_calls";
    private static final int ID = 3; // CallNotifier uses 1 and 2
    /** Our own: the notification was swiped away. */
    static final String ACTION_SEEN = "app.phueber.seniorphone.MISSED_SEEN";
    /** On the screens a missed-call tap opens: the calls have been seen (boolean). */
    static final String EXTRA_SEEN = "missed_seen";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Context app = context.getApplicationContext();
        if (ACTION_SEEN.equals(action)) {
            clearInTelecom(app);
            return;
        }
        if (!TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION.equals(action)) return;
        int count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0);
        if (count <= 0) {
            cancel(app);
            return;
        }
        String extraNumber = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER);
        PendingResult result = goAsync();
        Contacts.BACKGROUND.execute(() -> {
            try {
                post(app, count, extraNumber);
            } catch (Throwable t) {
                Log.e(TAG, "missed call notification", t);
            } finally {
                result.finish();
            }
        });
    }

    /** Background thread: who called last and when, then the notification. */
    private static void post(Context c, int count, String extraNumber) {
        String number = extraNumber;
        long when = System.currentTimeMillis();
        ContentResolver cr = c.getContentResolver();
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            String[] cols = { CallLog.Calls.NUMBER, CallLog.Calls.DATE };
            try (Cursor cur = cr.query(CallLog.Calls.CONTENT_URI, cols,
                    CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1",
                    null, CallLog.Calls.DATE + " DESC")) {
                if (cur != null && cur.moveToFirst()) {
                    String n = cur.getString(0);
                    if (n != null && !n.trim().isEmpty()) number = n;
                    when = cur.getLong(1);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "call log", e);
            }
        }
        if (number != null && number.trim().isEmpty()) number = null;

        String name = null;
        long contactId = -1;
        if (number != null && Contacts.canRead(c)) {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            String[] cols = { ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup._ID };
            try (Cursor cur = cr.query(uri, cols, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    name = cur.getString(0);
                    contactId = cur.getLong(1);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "contact lookup", e);
            }
            if (name == null) { // PhoneLookup depends on the phone's country; try our own rule
                long id = Contacts.idByNumber(c, number);
                ContactInfo info = id >= 0 ? Contacts.load(cr, id) : null;
                if (info != null) {
                    name = info.name;
                    contactId = id;
                }
            }
        }

        String who = name != null ? name
                : number != null ? CallService.format(number) : c.getString(R.string.call_unknown_number);
        String time = DateUtils.isToday(when)
                ? DateUtils.formatDateTime(c, when, DateUtils.FORMAT_SHOW_TIME)
                : DateUtils.formatDateTime(c, when, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_ABBREV_MONTH);
        String text = count == 1 ? c.getString(R.string.missed_one, time)
                : c.getString(R.string.missed_many, count, time);

        ensureChannel(c);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_contacts_call)
                .setContentTitle(who)
                .setContentText(text)
                .setWhen(when)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setNumber(count)
                .setContentIntent(open(c, contactId, number, false, 1))
                .setDeleteIntent(PendingIntent.getBroadcast(c, 2,
                        new Intent(c, MissedCalls.class).setAction(ACTION_SEEN), immutable()));
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setCategory(Notification.CATEGORY_MISSED_CALL);
        if (number != null) {
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_contacts_call),
                    c.getString(R.string.missed_call_back), open(c, contactId, number, true, 3)).build());
        }
        c.getSystemService(NotificationManager.class).notify(ID, b.build());
    }

    /**
     * The person's page over the contact list, or the number pad with the
     * number; ask: with the "Call …?" question. Unknown caller: the list.
     */
    private static PendingIntent open(Context c, long contactId, String number, boolean ask, int code) {
        if (contactId >= 0) {
            Intent page = new Intent(c, ContactActivity.class)
                    .putExtra(ContactActivity.EXTRA_ID, contactId)
                    .putExtra(EXTRA_SEEN, true);
            if (ask) page.putExtra(ContactActivity.EXTRA_ASK_CALL, number);
            return PendingIntent.getActivities(c, code, ContactActivity.overList(c, page), immutable());
        }
        Intent i;
        if (number != null) {
            i = new Intent(c, DialActivity.class).setAction(Intent.ACTION_DIAL)
                    .setData(Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (ask) i.putExtra(DialActivity.EXTRA_ASK_CALL, true);
        } else {
            i = new Intent(c, ContactsActivity.class);
        }
        i.putExtra(EXTRA_SEEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, i, immutable());
    }

    /** A screen opened from the notification: the missed calls have been seen. */
    static void seenFrom(Activity a, Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SEEN, false)) return;
        intent.removeExtra(EXTRA_SEEN);
        cancel(a);
        clearInTelecom(a.getApplicationContext());
    }

    /**
     * Telecom forgets the count and marks the calls read; it then sends count 0.
     * Allowed for the default phone app without MODIFY_PHONE_STATE.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private static void clearInTelecom(Context c) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            if (tm != null) tm.cancelMissedCallsNotification();
        } catch (RuntimeException e) {
            Log.w(TAG, "cancel in Telecom", e); // not the phone app any more
        }
    }

    private static void cancel(Context c) {
        try {
            c.getSystemService(NotificationManager.class).cancel(ID);
        } catch (RuntimeException e) {
            Log.w(TAG, "cancel", e);
        }
    }

    private static void ensureChannel(Context c) {
        try {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    c.getString(R.string.missed_channel), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(c.getString(R.string.missed_channel_description));
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        } catch (RuntimeException e) {
            Log.e(TAG, "channel", e);
        }
    }

    private static int immutable() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
