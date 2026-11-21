package app.phueber.seniorphone;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rows of {@link FavouritesWidget}. The home screen asks for them over a
 * binder; Android runs onDataSetChanged on a binder thread, so reading the
 * address book and the photos there is fine.
 */
public final class FavouritesWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

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

    private static final class Factory implements RemoteViewsFactory {
        private static final int AVATAR_DP = 56;
        private final Context c;
        private volatile List<Row> rows = Collections.emptyList();
        private volatile Palette.Theme theme = Palette.Theme.LIGHT;

        Factory(Context c) {
            this.c = c;
        }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            // Called on behalf of the home screen: without this, the contacts
            // provider would check the home screen's permissions, not ours.
            long token = Binder.clearCallingIdentity();
            try {
                Palette.Theme t = new Store(c).theme();
                List<Row> out = new ArrayList<>();
                if (Contacts.canRead(c)) {
                    ContentResolver cr = c.getContentResolver();
                    int size = PhoneWidgets.avatarPx(c, AVATAR_DP);
                    List<ContactInfo> fav = WidgetModel.favourites(Contacts.list(cr),
                            ContactList.comparator(Collator.getInstance()));
                    for (ContactInfo info : fav) {
                        Bitmap photo = Contacts.photo(cr, info.photoUri, size);
                        out.add(new Row(info.id, ContactsActivity.displayName(c, info),
                                PhoneWidgets.avatar(c, t, info, photo, size)));
                    }
                }
                theme = t;
                rows = out;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override public void onDestroy() { rows = Collections.emptyList(); }

        @Override public int getCount() { return rows.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            List<Row> list = rows;
            if (position < 0 || position >= list.size()) return null;
            Row r = list.get(position);
            Palette.Theme t = theme;
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_favourites_row);
            PhoneWidgets.tint(rv, R.id.row_border, Palette.color(t, Palette.Role.CARD_BORDER));
            PhoneWidgets.tint(rv, R.id.row_fill, Palette.color(t, Palette.Role.CARD));
            rv.setTextColor(R.id.row_name, Palette.color(t, Palette.Role.TEXT));
            rv.setTextViewText(R.id.row_name, r.name);
            rv.setImageViewBitmap(R.id.row_avatar, r.avatar);
            rv.setContentDescription(R.id.row_root, c.getString(R.string.contacts_call_cd, r.name));
            rv.setOnClickFillInIntent(R.id.row_root,
                    new Intent().putExtra(ContactActivity.EXTRA_ID, r.id));
            return rv;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }

        @Override
        public long getItemId(int position) {
            List<Row> list = rows;
            return position >= 0 && position < list.size() ? list.get(position).id : position;
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
