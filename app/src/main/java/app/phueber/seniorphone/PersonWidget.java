package app.phueber.seniorphone;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

/**
 * The one-person widget: one launcher cell with the person's round picture and
 * first name, big. A tap opens their page with "Call Maria?" (never a silent
 * call). The helper picks the person when adding it (WidgetPickActivity, also
 * to change it later).
 *
 * The person is remembered by contact id and lookup key, so it survives the
 * address book merging or re-numbering contacts. Deleted: a "?" that opens the
 * contact list. Without the contacts permission: "Allow contacts", opening
 * the app, which asks.
 */
public final class PersonWidget extends AppWidgetProvider {
    private static final int AVATAR_DP = 96;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        Context app = ctx.getApplicationContext();
        PendingResult result = goAsync();
        Contacts.BACKGROUND.execute(() -> {
            try {
                for (int id : ids) update(app, mgr, id);
            } finally {
                result.finish();
            }
        });
        PhoneWidgets.watch(ctx);
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        for (int id : ids) PhoneWidgets.forget(ctx, id);
    }

    @Override
    public void onDisabled(Context ctx) {
        PhoneWidgets.unwatchIfUnused(ctx);
    }

    /** Redraws every placed one-person widget. Background thread. */
    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (mgr == null) return;
        for (int id : PhoneWidgets.ids(c, PersonWidget.class)) update(c, mgr, id);
    }

    /** Background thread. */
    static void update(Context c, AppWidgetManager mgr, int widgetId) {
        try {
            mgr.updateAppWidget(widgetId, views(c, widgetId));
        } catch (RuntimeException e) {
            // the widget was removed meanwhile, or the host is gone
        }
    }

    /** The widget as it should look now. Background thread: reads the contact and its photo. */
    static RemoteViews views(Context c, int widgetId) {
        Palette.Theme t = new Store(c).theme();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_person);
        PhoneWidgets.tint(rv, R.id.person_pill_bg, Palette.color(t, Palette.Role.CARD));
        rv.setTextColor(R.id.person_name, Palette.color(t, Palette.Role.TEXT));
        rv.setViewVisibility(R.id.person_pill, View.VISIBLE);
        int size = PhoneWidgets.avatarPx(c, AVATAR_DP);

        if (!Contacts.canRead(c)) {
            rv.setImageViewBitmap(R.id.person_avatar, PhoneWidgets.mark(c, null,
                    Palette.color(t, Palette.Role.BLUE), Palette.ON_COLOR, size));
            rv.setTextViewText(R.id.person_name, c.getString(R.string.widget_allow));
            rv.setContentDescription(R.id.person_root, c.getString(R.string.widget_allow_text));
            rv.setOnClickPendingIntent(R.id.person_root, PhoneWidgets.openContacts(c, widgetId));
            return rv;
        }

        ContentResolver cr = c.getContentResolver();
        WidgetModel.Pick pick = PhoneWidgets.pick(c, widgetId);
        ContactInfo info = pick == null ? null : Contacts.find(cr, pick.id, pick.lookupKey);
        if (info == null) {
            // Deleted (or never chosen): a question mark that leads to the list.
            rv.setImageViewBitmap(R.id.person_avatar, PhoneWidgets.mark(c, "?",
                    Palette.color(t, Palette.Role.DISABLED_BG), Palette.color(t, Palette.Role.TEXT), size));
            rv.setViewVisibility(R.id.person_pill, View.GONE);
            rv.setContentDescription(R.id.person_root, c.getString(R.string.widget_gone_cd));
            rv.setOnClickPendingIntent(R.id.person_root, PhoneWidgets.openContacts(c, widgetId));
            return rv;
        }
        if (info.id != pick.id || !pick.lookupKey.equals(info.lookupKey)) {
            // Merged or re-numbered: remember where the person is now.
            PhoneWidgets.setPick(c, widgetId, new WidgetModel.Pick(info.id, info.lookupKey));
        }
        Bitmap photo = Contacts.photo(cr, info.photoUri, size);
        rv.setImageViewBitmap(R.id.person_avatar, PhoneWidgets.avatar(c, t, info, photo, size));
        String full = ContactsActivity.displayName(c, info);
        rv.setTextViewText(R.id.person_name,
                info.name.isEmpty() ? full : WidgetModel.firstName(info.name));
        rv.setContentDescription(R.id.person_root, c.getString(R.string.contacts_call_cd, full));
        rv.setOnClickPendingIntent(R.id.person_root, PhoneWidgets.call(c, widgetId, info.id));
        return rv;
    }
}
