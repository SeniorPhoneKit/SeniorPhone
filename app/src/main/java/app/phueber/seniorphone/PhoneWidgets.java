package app.phueber.seniorphone;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.ContactsContract;
import android.widget.RemoteViews;

/**
 * What both home-screen widgets share: keeping them up to date, the person a
 * one-person widget calls, the round pictures, the taps.
 *
 * Up to date without polling: while the app's process runs, a ContentObserver
 * on the address book; when it does not, a JobScheduler job that Android
 * starts on a change of the address book (a content trigger, not a timer).
 * The app's own star, edit and delete also ask for a refresh directly. All of
 * these are coalesced into one redraw ({@link #changed}).
 *
 * Every tap that leads to a call opens the contact page with the usual
 * "Call Maria Schmidt?" question (ContactActivity.EXTRA_ASK_CALL), exactly
 * like the automation CALL: a widget never places a call by itself.
 */
final class PhoneWidgets {
    private static final String PREFS = "widgets";
    private static final String K_PICK = "person_";
    /** Several changes in a row (a sync, a star plus its observer) become one redraw. */
    private static final long REFRESH_DELAY_MS = 800;
    /** Big enough for a sharp picture on any phone, small enough for a RemoteViews parcel. */
    private static final int MAX_AVATAR_PX = 256;

    /** Main thread only. */
    private static ContentObserver observer;
    @SuppressLint("StaticFieldLeak") // the application context, which lives as long as the process
    private static Context app;
    private static final Runnable REFRESH = () -> {
        Context c = app;
        if (c != null) Contacts.BACKGROUND.execute(() -> refreshNow(c));
    };

    private PhoneWidgets() {}

    static int[] ids(Context c, Class<?> provider) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (mgr == null) return new int[0];
        try {
            return mgr.getAppWidgetIds(new ComponentName(c, provider));
        } catch (RuntimeException e) {
            return new int[0];
        }
    }

    /** Whether any of our widgets is on a home screen. */
    static boolean any(Context c) {
        return ids(c, PersonWidget.class).length > 0 || ids(c, FavouritesWidget.class).length > 0;
    }

    // ---------------------------------------------------------------- refresh

    /** Something about the contacts, the permission or the theme changed: redraw soon. Any thread. */
    static void changed(Context c) {
        Context a = c.getApplicationContext();
        Contacts.MAIN.post(() -> {
            app = a;
            Contacts.MAIN.removeCallbacks(REFRESH);
            Contacts.MAIN.postDelayed(REFRESH, REFRESH_DELAY_MS);
        });
    }

    /** Redraws every placed widget. Background thread (it reads contacts and photos). */
    static void refreshNow(Context c) {
        FavouritesWidget.updateAll(c);
        PersonWidget.updateAll(c);
    }

    /**
     * Follows the address book while the process lives, and lets Android wake
     * us for changes when it does not; only while a widget is placed. Any thread.
     */
    static void watch(Context c) {
        Context a = c.getApplicationContext();
        Contacts.MAIN.post(() -> {
            if (!any(a)) return;
            ContactsChangedJob.schedule(a);
            if (observer != null || !Contacts.canRead(a)) return;
            ContentObserver o = new ContentObserver(Contacts.MAIN) {
                @Override public void onChange(boolean selfChange) {
                    changed(a);
                }
            };
            try {
                a.getContentResolver().registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI, true, o);
                observer = o;
            } catch (RuntimeException e) {
                // permission revoked meanwhile: the job and the app's own refreshes remain
            }
        });
    }

    /** The last widget was removed: stop following the address book. */
    static void unwatchIfUnused(Context c) {
        Context a = c.getApplicationContext();
        Contacts.MAIN.post(() -> {
            if (any(a)) return;
            ContactsChangedJob.cancel(a);
            if (observer != null) {
                a.getContentResolver().unregisterContentObserver(observer);
                observer = null;
            }
        });
    }

    // ---------------------------------------------------------------- the chosen person

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static WidgetModel.Pick pick(Context c, int widgetId) {
        return WidgetModel.decode(prefs(c).getString(K_PICK + widgetId, null));
    }

    static void setPick(Context c, int widgetId, WidgetModel.Pick p) {
        prefs(c).edit().putString(K_PICK + widgetId, WidgetModel.encode(p)).apply();
    }

    static void forget(Context c, int widgetId) {
        prefs(c).edit().remove(K_PICK + widgetId).apply();
    }

    // ---------------------------------------------------------------- taps

    private static int immutable() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    /**
     * The contact page with "Call …?" (with several numbers, the page itself
     * is the question: one Call per number). CLEAR_TOP: an open contact page
     * is replaced, so the question is always about this person.
     */
    private static Intent callIntent(Context c) {
        return new Intent(c, ContactActivity.class)
                .putExtra(ContactActivity.EXTRA_ASK_CALL, "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static PendingIntent call(Context c, int widgetId, long contactId) {
        return PendingIntent.getActivity(c, widgetId,
                callIntent(c).putExtra(ContactActivity.EXTRA_ID, contactId), immutable());
    }

    /**
     * The favourites list's template: each row fills in its contact id. Must be
     * mutable for the fill-in; it is explicit (our own activity), so nothing
     * else can be put in its place.
     */
    static PendingIntent callTemplate(Context c, int widgetId) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getActivity(c, widgetId,
                callIntent(c).setAction("app.phueber.seniorphone.widget.FAVOURITE"), flags);
    }

    /** The contact list; it also asks for the contacts permission when it is missing. */
    static PendingIntent openContacts(Context c, int widgetId) {
        return PendingIntent.getActivity(c, widgetId,
                new Intent(c, ContactsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                immutable());
    }

    /** Colours one of the white widget_round shapes. */
    static void tint(RemoteViews rv, int viewId, int color) {
        rv.setInt(viewId, "setColorFilter", color);
    }

    // ---------------------------------------------------------------- pictures

    static int avatarPx(Context c, int dp) {
        return Math.min(MAX_AVATAR_PX, Ui.dp(c, dp));
    }

    /**
     * A contact's round picture, as the app draws it: the photo when there is
     * one, else the first letter in white on blue (in Colourful, the contact's
     * own tile colour), or a person symbol for a name without a letter.
     */
    static Bitmap avatar(Context c, Palette.Theme t, ContactInfo info, Bitmap photo, int size) {
        if (photo != null) return roundPhoto(photo, size);
        int fill = Palette.colouredTiles(t)
                ? Palette.tileColor(ContactList.colorIndex(info.id, Palette.tileColorCount()))
                : Palette.color(t, Palette.Role.BLUE);
        String initial = ContactList.initial(info.name);
        return mark(c, initial.isEmpty() ? null : initial, fill, Palette.ON_COLOR, size);
    }

    /** A round mark: text (or, for null, the person symbol) in fg on a fill circle. */
    static Bitmap mark(Context c, String text, int fill, int fg, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(fill);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p);
        if (text == null) {
            Drawable d = c.getDrawable(R.drawable.ic_contacts_person);
            if (d != null) {
                d = d.mutate();
                d.setTint(fg);
                int pad = Math.round(size * 0.18f);
                d.setBounds(pad, pad, size - pad, size - pad);
                d.draw(canvas);
            }
            return b;
        }
        p.setColor(fg);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(size * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        Rect r = new Rect();
        p.getTextBounds(text, 0, text.length(), r);
        canvas.drawText(text, size / 2f, size / 2f + r.height() / 2f - r.bottom, p);
        return b;
    }

    private static Bitmap roundPhoto(Bitmap photo, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        BitmapShader shader = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Centre crop: the short side fills the circle.
        float scale = (float) size / Math.min(photo.getWidth(), photo.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((size - photo.getWidth() * scale) / 2f, (size - photo.getHeight() * scale) / 2f);
        shader.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(shader);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p);
        return b;
    }
}
