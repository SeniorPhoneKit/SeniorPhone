package app.phueber.seniorphone;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The favourites widget: the address book's favourites (starred), one big row
 * each with picture and name, in the contact list's order. A tap on a row
 * opens that person's page with "Call …?".
 *
 * Only whole rows: the widget shows as many as fit its height
 * ({@link WidgetModel#fit}) and, when not all do, a last line "+N more – tap
 * to see all" that opens the contact list. A list that scrolls would cut a row
 * in half, and a senior would not know the rest is there. The rows are plain
 * views added into a container (no collection), so the fit is exact on
 * Android 8 to 15. On 12+ the home screen gets one layout per size it may
 * show the widget at; before, the widget is drawn for the smallest height it
 * was told, so nothing is ever cut off.
 *
 * Nothing to show is said in words: "No favourites yet" (tap: the contact
 * list, where the helper can star someone), or "Allow contacts" without the
 * permission (tap: the app, which asks).
 */
public final class FavouritesWidget extends AppWidgetProvider {
    private static final int AVATAR_DP = 56;
    /** RemoteViews accepts at most 16 sized layouts. */
    private static final int MAX_SIZES = 16;

    /** One favourite as drawn: id for the tap, name, round picture. */
    private static final class Row {
        final long id;
        final String name;
        final Bitmap avatar;

        Row(long id, String name, Bitmap avatar) {
            this.id = id;
            this.name = name;
            this.avatar = avatar;
        }
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        updateAsync(ctx, ids);
        PhoneWidgets.watch(ctx);
    }

    /** The home screen changed the widget's size: fit the rows again. */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        updateAsync(ctx, new int[] {id});
    }

    @Override
    public void onDisabled(Context ctx) {
        PhoneWidgets.unwatchIfUnused(ctx);
    }

    private void updateAsync(Context ctx, int[] ids) {
        Context app = ctx.getApplicationContext();
        PendingResult result = goAsync();
        Contacts.BACKGROUND.execute(() -> {
            try {
                update(app, ids);
            } finally {
                result.finish();
            }
        });
    }

    /** Redraws every placed favourites widget. Background thread (reads contacts and photos). */
    static void updateAll(Context c) {
        int[] ids = PhoneWidgets.ids(c, FavouritesWidget.class);
        if (ids.length > 0) update(c, ids);
    }

    /** Background thread. The favourites are read once for all the widgets. */
    private static void update(Context c, int[] ids) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (mgr == null) return;
        Palette.Theme t = new Store(c).theme();
        boolean allowed = Contacts.canRead(c);
        List<Row> rows = allowed ? rows(c, t) : Collections.emptyList();
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, views(c, mgr, id, t, allowed, rows));
            } catch (RuntimeException e) {
                // the widget was removed meanwhile, or the host is gone
            }
        }
    }

    private static List<Row> rows(Context c, Palette.Theme t) {
        ContentResolver cr = c.getContentResolver();
        int size = PhoneWidgets.avatarPx(c, AVATAR_DP);
        List<Row> out = new ArrayList<>();
        List<ContactInfo> fav = WidgetModel.favourites(Contacts.list(cr),
                ContactList.comparator(Collator.getInstance()));
        for (ContactInfo info : fav) {
            Bitmap photo = Contacts.photo(cr, info.photoUri, size);
            out.add(new Row(info.id, ContactsActivity.displayName(c, info),
                    PhoneWidgets.avatar(c, t, info, photo, size)));
        }
        return out;
    }

    /** One layout per size the home screen may use (12+), else one for the smallest height. */
    private static RemoteViews views(Context c, AppWidgetManager mgr, int widgetId,
            Palette.Theme t, boolean allowed, List<Row> rows) {
        Bundle o = mgr.getAppWidgetOptions(widgetId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && allowed && !rows.isEmpty()) {
            List<SizeF> sizes = sizes(o);
            if (!sizes.isEmpty()) {
                Map<SizeF, RemoteViews> bySize = new HashMap<>();
                for (SizeF s : sizes) {
                    if (bySize.size() >= MAX_SIZES) break;
                    bySize.put(s, views(c, widgetId, t, allowed, rows, Math.round(s.getHeight())));
                }
                return new RemoteViews(bySize);
            }
        }
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return views(c, widgetId, t, allowed, rows, h);
    }

    @SuppressWarnings("deprecation") // getParcelableArrayList(String): the typed one is 33+
    private static List<SizeF> sizes(Bundle o) {
        if (o == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Collections.emptyList();
        ArrayList<SizeF> s;
        try {
            s = o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (s == null) return Collections.emptyList();
        List<SizeF> out = new ArrayList<>();
        for (SizeF f : s) {
            if (f != null && f.getWidth() > 0 && f.getHeight() > 0 && !out.contains(f)) out.add(f);
        }
        return out;
    }

    /** The widget at one height (dp; <= 0 unknown). */
    private static RemoteViews views(Context c, int widgetId, Palette.Theme t, boolean allowed,
            List<Row> rows, int heightDp) {
        int border = Palette.color(t, Palette.Role.CARD_BORDER);
        int card = Palette.color(t, Palette.Role.CARD);
        int text = Palette.color(t, Palette.Role.TEXT);
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_favourites);
        // The home screen applies an update onto the views it already has: clear
        // the rows first, or every redraw would add them again.
        rv.removeAllViews(R.id.fav_rows);

        if (!allowed || rows.isEmpty()) {
            PhoneWidgets.tint(rv, R.id.fav_empty_border, border);
            PhoneWidgets.tint(rv, R.id.fav_empty_fill, card);
            rv.setTextColor(R.id.fav_empty_title, text);
            rv.setTextColor(R.id.fav_empty_text, Palette.color(t, Palette.Role.TEXT_MUTED));
            rv.setTextViewText(R.id.fav_empty_title,
                    c.getString(allowed ? R.string.widget_no_favourites : R.string.widget_allow));
            rv.setTextViewText(R.id.fav_empty_text,
                    c.getString(allowed ? R.string.widget_open_contacts : R.string.widget_allow_text));
            rv.setOnClickPendingIntent(R.id.fav_empty, PhoneWidgets.openContacts(c, widgetId));
            rv.setViewVisibility(R.id.fav_rows, View.GONE);
            rv.setViewVisibility(R.id.fav_empty, View.VISIBLE);
            return rv;
        }
        rv.setViewVisibility(R.id.fav_empty, View.GONE);
        rv.setViewVisibility(R.id.fav_rows, View.VISIBLE);

        WidgetModel.Fit fit = WidgetModel.fit(rows.size(), heightDp);
        for (int i = 0; i < fit.rows; i++) {
            Row r = rows.get(i);
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_favourites_row);
            PhoneWidgets.tint(row, R.id.row_border, border);
            PhoneWidgets.tint(row, R.id.row_fill, card);
            row.setTextColor(R.id.row_name, text);
            row.setTextViewText(R.id.row_name, r.name);
            row.setImageViewBitmap(R.id.row_avatar, r.avatar);
            row.setContentDescription(R.id.row_root, c.getString(R.string.contacts_call_cd, r.name));
            row.setOnClickPendingIntent(R.id.row_root, PhoneWidgets.favourite(c, widgetId, r.id));
            rv.addView(R.id.fav_rows, row);
        }
        if (fit.more > 0) {
            String s = c.getResources().getQuantityString(
                    fit.rows > 0 ? R.plurals.widget_more : R.plurals.widget_count, fit.more, fit.more);
            RemoteViews more = new RemoteViews(c.getPackageName(), R.layout.widget_favourites_more);
            PhoneWidgets.tint(more, R.id.more_border, border);
            PhoneWidgets.tint(more, R.id.more_fill, card);
            more.setTextColor(R.id.more_text, text);
            more.setTextViewText(R.id.more_text, s);
            more.setContentDescription(R.id.more_root, s);
            more.setOnClickPendingIntent(R.id.more_root, PhoneWidgets.openContacts(c, widgetId));
            rv.addView(R.id.fav_rows, more);
        }
        return rv;
    }
}
