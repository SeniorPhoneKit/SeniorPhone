package app.phueber.seniorphone;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.text.Collator;
import java.util.Collections;
import java.util.List;

/**
 * Choosing the person of a one-person widget, when the helper adds it and
 * when they change it later (reconfigure): the app's contacts, favourites
 * first, with search, as big rows. Cancel (or Back) adds nothing: the result
 * stays RESULT_CANCELED, so the home screen drops the widget again.
 */
public final class WidgetPickActivity extends Activity {
    private static final int REQUEST_CONTACTS = 1;
    private static final int AVATAR_DP = 56;

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private EditText search;
    private ListView listView;
    private TextView status;
    private final Adapter adapter = new Adapter();
    private List<ContactInfo> sorted = Collections.emptyList();
    private boolean loaded;
    private boolean picking;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (!isOurs(widgetId)) {
            finish();
            return;
        }
        Ui.applyTheme(this);
        setContentView(build());
        if (Contacts.canRead(this)) {
            load();
        } else {
            requestPermissions(new String[] { Manifest.permission.READ_CONTACTS }, REQUEST_CONTACTS);
        }
    }

    /** Only our own one-person widgets: another app cannot point this at someone else's widget. */
    private boolean isOurs(int id) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        AppWidgetProviderInfo info = mgr != null ? mgr.getAppWidgetInfo(id) : null;
        return info != null && new ComponentName(this, PersonWidget.class).equals(info.provider);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CONTACTS) return;
        if (Contacts.canRead(this)) {
            load();
            PhoneWidgets.changed(this); // the other widgets can show people now too
        } else {
            status.setText(R.string.widget_pick_permission);
        }
    }

    private View build() {
        int p = Ui.dp(this, 16);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(p, 0, p, Ui.dp(this, 12));

        TextView title = Ui.bold(this, getString(R.string.widget_pick_title), Ui.TEXT_TITLE);
        title.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) title.setAccessibilityHeading(true);
        page.addView(title);

        search = searchField();
        page.addView(search, Ui.fullWidth(this, 4));

        FrameLayout middle = new FrameLayout(this);
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setClipToPadding(false);
        listView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        middle.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = Ui.text(this, getString(R.string.contacts_loading), 24);
        status.setTextColor(Ui.color(this, R.color.text_muted));
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 32);
        middle.addView(status, slp);
        page.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button cancel = Ui.outlined(this, getString(R.string.cancel));
        cancel.setOnClickListener(v -> finish());
        page.addView(cancel, Ui.fullWidth(this, 8));

        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        frame.addView(page);
        return frame;
    }

    private EditText searchField() {
        EditText s = new EditText(this);
        s.setHint(R.string.contacts_search_hint);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.TEXT_BUTTON);
        s.setTextColor(Ui.color(this, R.color.text_black));
        s.setHintTextColor(Ui.color(this, R.color.text_muted));
        s.setSingleLine(true);
        s.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        s.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        s.setMinHeight(Ui.dp(this, Ui.BUTTON_HEIGHT));
        int p = Ui.dp(this, 18);
        s.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.color.card));
        bg.setStroke(Ui.dp(this, 2), Ui.color(this, R.color.text_black));
        bg.setCornerRadius(Ui.dp(this, 32));
        s.setBackground(bg);
        s.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable t) { refilter(); }
        });
        s.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        return s;
    }

    private void load() {
        int gen = ++generation;
        ContentResolver cr = getContentResolver();
        Contacts.BACKGROUND.execute(() -> {
            List<ContactInfo> list = Contacts.list(cr);
            ContactList.sort(list, Collator.getInstance());
            Contacts.MAIN.post(() -> {
                if (gen != generation || isDestroyed()) return;
                sorted = list;
                loaded = true;
                refilter();
            });
        });
    }

    private void refilter() {
        if (!loaded) return;
        String q = search.getText().toString();
        List<ContactList.Item> items = ContactList.items(sorted, q);
        adapter.set(items);
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (sorted.isEmpty()) status.setText(R.string.widget_pick_none);
        else status.setText(getString(R.string.contacts_no_match, q.trim()));
    }

    /** Remembers the person, draws the widget, and only then reports success. */
    private void pick(ContactInfo c) {
        if (picking) return;
        picking = true;
        Context app = getApplicationContext();
        int id = widgetId;
        PhoneWidgets.setPick(app, id, new WidgetModel.Pick(c.id, c.lookupKey));
        Contacts.BACKGROUND.execute(() -> {
            RemoteViews rv = PersonWidget.views(app, id);
            AppWidgetManager mgr = AppWidgetManager.getInstance(app);
            if (mgr != null) {
                try {
                    mgr.updateAppWidget(id, rv);
                } catch (RuntimeException ignored) {
                    // the home screen draws it on its next update
                }
            }
            Contacts.MAIN.post(() -> {
                setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
                finish();
            });
        });
        PhoneWidgets.watch(app);
    }

    // ---------------------------------------------------------------- rows

    private final class Adapter extends BaseAdapter {
        private List<ContactList.Item> items = Collections.emptyList();

        void set(List<ContactList.Item> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return items.get(position).isHeader() ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; } // rows are their own buttons

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            ContactList.Item item = items.get(position);
            if (item.isHeader()) {
                TextView h = convert instanceof TextView ? (TextView) convert : header();
                h.setText(ContactList.FAVOURITES.equals(item.header)
                        ? getString(R.string.contacts_favourites) : item.header);
                return h;
            }
            Row r = convert != null && convert.getTag() instanceof Row ? (Row) convert.getTag() : new Row();
            r.bind(item.contact);
            return r.root;
        }
    }

    private TextView header() {
        TextView h = Ui.bold(this, "", 24);
        h.setPadding(Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 6));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) h.setAccessibilityHeading(true);
        return h;
    }

    /** One person: picture and name as one wide button, like the contact list. */
    private final class Row {
        final LinearLayout root;
        final LinearLayout card;
        final ContactsActivity.Avatar avatar;
        final TextView name;
        ContactInfo contact;

        Row() {
            Context c = WidgetPickActivity.this;
            root = new LinearLayout(c);
            root.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            root.setTag(this);
            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int p = Ui.dp(c, 12);
            card.setPadding(p, p, p, p);
            card.setMinimumHeight(Ui.dp(c, 80));
            card.setBackground(Ui.ripple(c, R.color.press_dark, Ui.color(c, R.color.card),
                    Ui.color(c, R.color.card_border), 20));
            card.setClickable(true);
            card.setFocusable(true);
            avatar = new ContactsActivity.Avatar(c, AVATAR_DP);
            int size = Ui.dp(c, AVATAR_DP);
            card.addView(avatar.view, new LinearLayout.LayoutParams(size, size));
            name = Ui.bold(c, "", Ui.TEXT_BUTTON);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nlp.setMarginStart(Ui.dp(c, 14));
            card.addView(name, nlp);
            root.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setOnClickListener(v -> { if (contact != null) pick(contact); });
        }

        void bind(ContactInfo c) {
            contact = c;
            String n = ContactsActivity.displayName(WidgetPickActivity.this, c);
            name.setText(n);
            card.setContentDescription(n);
            avatar.bind(c, false);
        }
    }
}
