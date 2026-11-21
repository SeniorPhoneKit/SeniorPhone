package app.phueber.seniorphone;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

/**
 * The favourites widget: the address book's favourites (starred), one big row
 * each with picture and name, in the contact list's order. A tap on a row
 * opens that person's page with "Call …?". The rows come from
 * {@link FavouritesWidgetService}, which reads them only when the home screen
 * asks, so a long list costs nothing up front and works on Android 8 to 15.
 *
 * Nothing to show is said in words: "No favourites yet" (tap: the contact
 * list, where the helper can star someone), or "Allow contacts" without the
 * permission (tap: the app, which asks).
 */
public final class FavouritesWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) update(ctx, mgr, id);
        PhoneWidgets.watch(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        PhoneWidgets.unwatchIfUnused(ctx);
    }

    /** Redraws every placed favourites widget and has the rows read again. Any thread. */
    @SuppressWarnings("deprecation") // notifyAppWidgetViewDataChanged: still the way on 8-11
    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (mgr == null) return;
        int[] ids = PhoneWidgets.ids(c, FavouritesWidget.class);
        if (ids.length == 0) return;
        for (int id : ids) update(c, mgr, id);
        try {
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.fav_list);
        } catch (RuntimeException e) {
            // host gone
        }
    }

    private static void update(Context c, AppWidgetManager mgr, int widgetId) {
        try {
            mgr.updateAppWidget(widgetId, views(c, widgetId));
        } catch (RuntimeException e) {
            // the widget was removed meanwhile, or the host is gone
        }
    }

    @SuppressWarnings("deprecation") // setRemoteAdapter(int, Intent): RemoteCollectionItems needs 31+
    private static RemoteViews views(Context c, int widgetId) {
        Palette.Theme t = new Store(c).theme();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_favourites);
        PhoneWidgets.tint(rv, R.id.fav_empty_border, Palette.color(t, Palette.Role.CARD_BORDER));
        PhoneWidgets.tint(rv, R.id.fav_empty_fill, Palette.color(t, Palette.Role.CARD));
        rv.setTextColor(R.id.fav_empty_title, Palette.color(t, Palette.Role.TEXT));
        rv.setTextColor(R.id.fav_empty_text, Palette.color(t, Palette.Role.TEXT_MUTED));
        rv.setOnClickPendingIntent(R.id.fav_empty, PhoneWidgets.openContacts(c, widgetId));

        if (!Contacts.canRead(c)) {
            rv.setViewVisibility(R.id.fav_list, View.GONE);
            rv.setViewVisibility(R.id.fav_empty, View.VISIBLE);
            rv.setTextViewText(R.id.fav_empty_title, c.getString(R.string.widget_allow));
            rv.setTextViewText(R.id.fav_empty_text, c.getString(R.string.widget_allow_text));
            return rv;
        }
        rv.setTextViewText(R.id.fav_empty_title, c.getString(R.string.widget_no_favourites));
        rv.setTextViewText(R.id.fav_empty_text, c.getString(R.string.widget_open_contacts));
        Intent rows = new Intent(c, FavouritesWidgetService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        // A distinct data URI per widget, or Android hands every widget the same factory.
        rows.setData(Uri.parse(rows.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setViewVisibility(R.id.fav_list, View.VISIBLE);
        rv.setViewVisibility(R.id.fav_empty, View.GONE);
        rv.setRemoteAdapter(R.id.fav_list, rows);
        rv.setEmptyView(R.id.fav_list, R.id.fav_empty);
        rv.setPendingIntentTemplate(R.id.fav_list, PhoneWidgets.callTemplate(c, widgetId));
        return rv;
    }
}
