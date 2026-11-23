package app.phueber.seniorphone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The call screen: incoming (Answer / Decline), calling and talking (Hang up,
 * Speaker, Louder, Microphone, Keypad), on hold (Continue / Hang up), SIM
 * choice, and a short "Call ended". The keypad (hotline menus, PINs) replaces
 * the talking screen's middle with a big 0-9 * # pad; Hang up stays big in
 * the same place at the bottom. Works over the lock screen and turns the screen on.
 *
 * It holds no call state of its own: every render reads CallService, so it
 * can be destroyed and recreated at any time while the call goes on, and it
 * can never show a call that has ended. It listens from onCreate to onDestroy,
 * so a call that ends while the screen is in the background closes it too.
 *
 * Layout for seniors, incoming: Answer is a huge green button in the middle,
 * Decline a smaller red one at the bottom, far apart. Stacked, not side by
 * side: a sideways slip of the thumb cannot hit the other button, and the
 * order is the app's rule everywhere (positive first, like Ui.confirm).
 * Answer is the bigger target because declining by mistake (missing the
 * family's call) is the worse error. Every new screen ignores taps for a
 * moment, so a tap meant for the app underneath, or a double tap on Answer,
 * cannot land on what appears in its place (Speaker, Hang up).
 */
public final class CallActivity extends Activity implements CallService.Listener {
    private static final String TAG = "CallActivity";
    static final String EXTRA_KEY = "call_key";
    static final String ACTION_ANSWER = "app.phueber.seniorphone.CALL_ANSWER";
    /** Open with the keypad showing (Telecom's onBringToForeground(showDialpad)). */
    static final String EXTRA_SHOW_KEYPAD = "show_keypad";
    /** A keypad tap sends its tone this long. */
    private static final long DTMF_MS = 200;
    /** After Answer/Decline, both are ignored this long unless the call changes. */
    private static final long CHOICE_LOCK_MS = 3000;

    /** Between onStart and onStop. */
    private static boolean started;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private TextView status;

    private boolean resumed;
    /** The call on screen (0 = none yet) and its screen, to notice changes. */
    private int shownKey;
    private CallState.Screen shownScreen;
    private String shownSig;
    /** The name shown for that call, for "Call ended" if Telecom forgot it. */
    private String shownTitle;
    private long guardUntil;
    private long choiceLockedUntil;
    private boolean endedShowing;
    /** The keypad is showing instead of the talking screen's middle. */
    private boolean keypadOpen;
    /** What was typed on the keypad during this call, shown above it. */
    private String typed = "";
    private TextView typedView;
    private Object backCallback;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable close = this::close;

    // ---- Lifecycle --------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation") // the window flags, for API 26 only
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.applyTheme(this);
        // The manifest sets showWhenLocked/turnScreenOn too; repeat them in code,
        // plus the legacy flags for API 26 where the attributes do not exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // Hang up must stay visible; at the ear the proximity lock (CallService)
        // switches the screen off instead.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        setContentView(frame);

        CallService.addListener(this);
        if (Build.VERSION.SDK_INT >= 33) registerBack();
        // A recreated screen must not answer again from the old intent.
        if (saved == null) handleIntent(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        CallService.screenVisibilityChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        CallService.screenVisibilityChanged();
    }

    /**
     * Whether the call screen is what the senior sees right now. With the
     * screen off it is not: a new ringing call then needs the full-screen
     * intent, which reliably wakes the phone.
     */
    static boolean inFront(Context ctx) {
        return started && interactive(ctx);
    }

    private static boolean interactive(Context ctx) {
        try {
            android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        CallService.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 33) unregisterBack();
        super.onDestroy();
    }

    @Override
    public void onCallsChanged() {
        render();
    }

    /**
     * Back never leaves a live call (a senior pressing it by habit would lose
     * the Hang-up button); Hang up and the notification remain. On the
     * "Call ended" screen it closes.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        onBack();
    }

    private void onBack() {
        if (endedShowing) {
            close();
        } else if (keypadOpen) {
            showKeypad(false);
        }
    }

    /** Opens or closes the keypad; taps on the new layout wait a moment, as on any new screen. */
    private void showKeypad(boolean open) {
        keypadOpen = open;
        guardUntil = SystemClock.uptimeMillis() + Ui.TOUCH_GUARD_MS;
        render();
    }

    @android.annotation.TargetApi(33)
    private void registerBack() {
        try {
            android.window.OnBackInvokedCallback cb = this::onBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        } catch (Throwable t) {
            Log.w(TAG, "back callback", t);
        }
    }

    @android.annotation.TargetApi(33)
    private void unregisterBack() {
        try {
            if (backCallback != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (android.window.OnBackInvokedCallback) backCallback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "back callback", t);
        }
    }

    /** Answer from the notification's button arrives here. */
    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_KEYPAD, false)) {
            intent.removeExtra(EXTRA_SHOW_KEYPAD);
            keypadOpen = true;
            guardUntil = SystemClock.uptimeMillis() + Ui.TOUCH_GUARD_MS;
        }
        if (intent == null || !ACTION_ANSWER.equals(intent.getAction())) return;
        CallService.answer(intent.getIntExtra(EXTRA_KEY, 0));
        // Done; a later re-delivery of this intent must not answer again.
        setIntent(new Intent(this, CallActivity.class));
    }

    /** Opens the call screen, e.g. from a "Back to the call" banner. */
    public static void open(Context ctx) {
        CallService.openScreen(ctx);
    }

    // ---- Rendering --------------------------------------------------------

    private void render() {
        if (isFinishing() || frame == null) return;
        CallService.Entry p = CallService.primary();
        if (p != null) {
            handler.removeCallbacks(close);
            endedShowing = false;
            CallState.Screen s = p.screen();
            if (shownKey != 0 && p.key != shownKey) {
                // Another call took the screen: its own keypad, closed.
                keypadOpen = false;
                typed = "";
            }
            if (p.key != shownKey || s != shownScreen) {
                // Calling -> connected keeps every button where it was (Hang up
                // included), so a tap then is meant for that button: no guard.
                if (!(p.key == shownKey && sameButtons(shownScreen, s))) {
                    guardUntil = SystemClock.uptimeMillis() + Ui.TOUCH_GUARD_MS;
                }
                choiceLockedUntil = 0;
            }
            String sig = signature(p, s);
            if (!sig.equals(shownSig)) {
                build(p, s);
                shownSig = sig;
            }
            shownKey = p.key;
            shownScreen = s;
            shownTitle = p.title();
            updateStatus(p, s);
            return;
        }
        if (endedShowing) return;
        // No live call. If we were showing one and the senior is looking, say
        // that it ended; otherwise (background, or opened for a call that is
        // already gone) just close.
        CallService.Ended ended = CallService.lastEnded();
        if (shownKey != 0 && resumed) {
            CallState.End end = ended != null && ended.key == shownKey ? ended.end : CallState.End.ENDED;
            String title = ended != null && ended.key == shownKey && ended.title != null
                    ? ended.title : shownTitle;
            buildEnded(title, end);
            endedShowing = true;
            handler.postDelayed(close, CallState.endedShowMillis(end));
        } else {
            close();
        }
    }

    /** Screens with the same buttons in the same places. */
    private static boolean sameButtons(CallState.Screen a, CallState.Screen b) {
        return talking(a) && talking(b);
    }

    private static boolean talking(CallState.Screen s) {
        return s == CallState.Screen.OUTGOING || s == CallState.Screen.ACTIVE;
    }

    private void close() {
        handler.removeCallbacksAndMessages(null);
        if (!isFinishing()) finishAndRemoveTask();
    }

    /** Everything that changes the layout (not the ticking timer). */
    private String signature(CallService.Entry p, CallState.Screen s) {
        return p.key + "|" + s + "|" + p.title() + "|" + p.formatted() + "|"
                + (p.photo != null ? System.identityHashCode(p.photo) : 0) + "|"
                + speakerOn() + "|" + CallService.muted() + "|" + keypadOpen + "|"
                + CallService.hasOtherLiveCall(p);
    }

    private void build(CallService.Entry p, CallState.Screen s) {
        LinearLayout root = column();
        boolean small = getResources().getConfiguration().screenHeightDp < 640;
        typedView = null;
        if (keypadOpen && talking(s)) {
            buildKeypad(root, p, small);
            show(root);
            return;
        }
        int avatar = s == CallState.Screen.INCOMING ? (small ? 88 : 128) : (small ? 56 : 96);
        root.addView(header(p.title(), p.contactName, p.formatted(), p.photo,
                        p.contactId >= 0 ? p.contactId : (p.number != null ? p.number.hashCode() : 0), avatar),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final int key = p.key;
        switch (s) {
            case INCOMING: {
                if (CallService.hasOtherLiveCall(p)) {
                    TextView hint = Ui.text(this, getString(R.string.call_waiting_hint), Ui.TEXT_BODY);
                    hint.setTextColor(Ui.color(this, R.color.text_muted));
                    hint.setGravity(Gravity.CENTER);
                    root.addView(hint, Ui.fullWidth(this, 0));
                }
                View answer = action(R.drawable.ic_call_answer, getString(R.string.call_answer), null,
                        R.color.green, true, 36, () -> {
                            if (!choiceOk()) return;
                            CallService.answer(key);
                        });
                root.addView(answer, sized(small ? 120 : 150, 16));
                View decline = action(R.drawable.ic_call_end, getString(R.string.call_decline), null,
                        R.color.red, true, 26, () -> {
                            if (!choiceOk()) return;
                            CallService.decline(key);
                        });
                root.addView(decline, sized(88, small ? 32 : 48));
                break;
            }
            case OUTGOING:
            case ACTIVE: {
                // The same rows while calling and when connected: nothing moves
                // when the other side answers.
                root.addView(audioRow(), sized(small ? 80 : 96, 16));
                root.addView(micKeypadRow(), sized(small ? 80 : 96, 16));
                root.addView(hangUp(key), sized(small ? 96 : 112, 32));
                break;
            }
            case WAITING:
                root.addView(hangUp(key), sized(small ? 96 : 112, 32));
                break;
            case HOLDING: {
                View resume = action(R.drawable.ic_call_answer, getString(R.string.call_continue), null,
                        R.color.green, true, 28, () -> CallService.unhold(key));
                root.addView(resume, sized(96, 16));
                root.addView(hangUp(key), sized(small ? 96 : 112, 32));
                break;
            }
            case CHOOSE_SIM: {
                List<PhoneAccountHandle> sims = CallService.simChoices(this, p);
                int n = 1;
                for (PhoneAccountHandle h : sims) {
                    final PhoneAccountHandle handle = h;
                    String label = simLabel(h, n++);
                    root.addView(action(0, label, null, R.color.blue, true, 26,
                            () -> CallService.chooseSim(key, handle)), sized(80, 12));
                }
                if (sims.isEmpty()) {
                    TextView none = Ui.text(this, getString(R.string.call_no_sim), Ui.TEXT_BODY);
                    none.setGravity(Gravity.CENTER);
                    root.addView(none, Ui.fullWidth(this, 0));
                }
                View cancel = action(R.drawable.ic_call_end, getString(R.string.call_cancel_call), null,
                        R.color.red, true, 26, () -> CallService.hangUp(key));
                root.addView(cancel, sized(88, 32));
                break;
            }
            default:
                break;
        }
        show(root);
    }

    /**
     * The keypad: who and how long on top, then what was typed, the twelve
     * keys (the number pad's look), "Close keypad", and Hang up exactly where
     * it is on the talking screen.
     */
    private void buildKeypad(LinearLayout root, CallService.Entry p, boolean small) {
        final int key = p.key;
        String title = p.title() != null ? p.title() : getString(R.string.call_unknown_number);
        TextView name = Ui.bold(this, title, 28);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(name, Ui.fullWidth(this, 0));
        status = Ui.text(this, "", 22);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.fullWidth(this, 0));

        typedView = Ui.bold(this, "", 40);
        typedView.setGravity(Gravity.CENTER);
        typedView.setSingleLine(true);
        typedView.setEllipsize(TextUtils.TruncateAt.START); // the newest digits stay in sight
        typedView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        showTyped();
        root.addView(typedView, Ui.fullWidth(this, 4));

        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        String keys = "123456789*0#";
        for (int row = 0; row < 4; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                char c = keys.charAt(row * 3 + col);
                FrameLayout k = DialActivity.keyView(this, c, small ? 36 : 44);
                k.setOnClickListener(v -> {
                    if (SystemClock.uptimeMillis() < guardUntil) return;
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    CallService.playDtmf(key, c);
                    handler.postDelayed(() -> CallService.stopDtmf(key), DTMF_MS);
                    typed = typed + c;
                    showTyped();
                });
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                int m = Ui.dp(this, 4);
                klp.setMargins(m, m, m, m);
                r.addView(k, klp);
            }
            pad.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        plp.topMargin = Ui.dp(this, 8);
        root.addView(pad, plp);

        View hide = action(R.drawable.ic_contacts_dialpad, getString(R.string.call_keypad_close), null,
                R.color.blue, false, 24, () -> showKeypad(false));
        root.addView(hide, sized(small ? 72 : 80, 12));
        root.addView(hangUp(key), sized(small ? 96 : 112, 32));
    }

    private void showTyped() {
        if (typedView == null) return;
        typedView.setText(typed);
        typedView.setContentDescription(typed.isEmpty() ? getString(R.string.call_keypad_hint)
                : DialNumber.spoken(typed, getString(R.string.dial_plus),
                        getString(R.string.dial_star), getString(R.string.dial_hash)));
        if (typed.isEmpty()) typedView.setHint(R.string.call_keypad_hint);
        typedView.setHintTextColor(Ui.color(this, R.color.text_muted));
    }

    private void buildEnded(String title, CallState.End end) {
        LinearLayout root = column();
        View h = header(title, null, null, null, 0, 96);
        root.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        status.setText(endText(end));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Ui.color(this, end == CallState.End.ENDED || end == CallState.End.DECLINED
                ? R.color.text_black : R.color.red_text));
        show(root);
        shownSig = null; // whatever comes next is built afresh
    }

    private void show(LinearLayout root) {
        frame.removeAllViews();
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout column() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        root.setPadding(p, p, p, Ui.dp(this, 24)); // clear of the bottom edge, where palms rest
        return root;
    }

    /** Avatar, name, number (if the name is not the number), status line. */
    private View header(String title, String contactName, String number, android.graphics.Bitmap photo,
                        long colourSeed, int avatarDp) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setGravity(Gravity.CENTER);

        h.addView(avatar(contactName, photo, colourSeed, avatarDp),
                new LinearLayout.LayoutParams(Ui.dp(this, avatarDp), Ui.dp(this, avatarDp)));

        TextView name = Ui.bold(this, title != null ? title : getString(R.string.call_unknown_number), 36);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        h.addView(name, Ui.fullWidth(this, 16));

        if (number != null && !number.equals(title)) {
            TextView num = Ui.text(this, number, 24);
            num.setTextColor(Ui.color(this, R.color.text_muted));
            num.setGravity(Gravity.CENTER);
            num.setSingleLine(true);
            h.addView(num, Ui.fullWidth(this, 4));
        }

        status = Ui.text(this, "", 26);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        h.addView(status, Ui.fullWidth(this, 12));
        return h;
    }

    /** Photo in a circle; else the initial, else a person icon, on a coloured circle. */
    private View avatar(String contactName, android.graphics.Bitmap photo, long seed, int sizeDp) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        boolean colourful = Palette.colouredTiles(Ui.currentTheme(this));
        circle.setColor(colourful ? Palette.tileColor((int) (seed ^ (seed >>> 32)) & 0x7fffffff)
                : Ui.color(this, R.color.blue));
        if (photo != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(photo);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(circle);
            iv.setClipToOutline(true);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return iv;
        }
        String initial = CallState.initial(contactName);
        if (initial != null) {
            TextView t = new TextView(this);
            t.setText(initial);
            t.setTextColor(Color.WHITE);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(this, sizeDp) * 0.45f);
            t.setGravity(Gravity.CENTER);
            t.setBackground(circle);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return t;
        }
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_call_person);
        iv.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        int pad = Ui.dp(this, sizeDp) / 5;
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(circle);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return iv;
    }

    private void updateStatus(CallService.Entry p, CallState.Screen s) {
        if (status == null) return;
        String text;
        switch (s) {
            case INCOMING: text = getString(R.string.call_is_calling); break;
            case OUTGOING: text = getString(R.string.call_calling); break;
            case WAITING: text = getString(R.string.call_please_wait); break;
            case CHOOSE_SIM: text = getString(R.string.call_choose_sim); break;
            case HOLDING: text = getString(R.string.call_on_hold); break;
            case ACTIVE: {
                long at = p.connectTime();
                text = at > 0
                        ? getString(R.string.call_connected_for,
                                CallState.duration(System.currentTimeMillis() - at))
                        : getString(R.string.call_connected);
                break;
            }
            default: text = "";
        }
        if (!text.contentEquals(status.getText())) status.setText(text);
    }

    private String endText(CallState.End end) {
        switch (end) {
            case DECLINED: return getString(R.string.call_ended_declined);
            case MISSED: return getString(R.string.call_ended_missed);
            case BUSY: return getString(R.string.call_ended_busy);
            case FAILED: return getString(R.string.call_ended_failed);
            default: return getString(R.string.call_ended);
        }
    }

    // ---- Buttons ----------------------------------------------------------

    private View hangUp(int key) {
        return action(R.drawable.ic_call_end, getString(R.string.call_hang_up), null,
                R.color.red, true, 32, () -> CallService.hangUp(key));
    }

    /** Speaker (two-part label: what it is, and whether it is on) and Louder. */
    private View audioRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean on = speakerOn();
        View speaker = action(R.drawable.ic_call_speaker, getString(R.string.call_speaker),
                getString(on ? R.string.call_speaker_on : R.string.call_speaker_off),
                R.color.green, on, 24, () -> {
                    CallService.setSpeaker(!speakerOn());
                });
        View louder = action(R.drawable.ic_call_louder, getString(R.string.call_louder), null,
                R.color.blue, false, 24, this::louder);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        row.addView(speaker, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.setMarginStart(Ui.dp(this, 12));
        row.addView(louder, rp);
        return row;
    }

    /**
     * Microphone (said in words: "is on" / "is off – they can't hear you"),
     * filled while muted so it stands out, and Keypad.
     */
    private View micKeypadRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean muted = CallService.muted();
        View mic = action(muted ? R.drawable.ic_call_mic_off : R.drawable.ic_call_mic,
                getString(R.string.call_microphone),
                getString(muted ? R.string.call_microphone_off : R.string.call_microphone_on),
                R.color.blue, muted, 24, () -> CallService.setMicrophoneMuted(!CallService.muted()));
        if (muted) mic.setContentDescription(getString(R.string.call_microphone_off_cd));
        View keypad = action(R.drawable.ic_contacts_dialpad, getString(R.string.call_keypad), null,
                R.color.blue, false, 24, () -> showKeypad(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        row.addView(mic, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.setMarginStart(Ui.dp(this, 12));
        row.addView(keypad, rp);
        return row;
    }

    private static boolean speakerOn() {
        return CallService.audioRoute() == CallState.R_SPEAKER;
    }

    /** One step up on the call volume, with the system's volume bar as feedback. */
    private void louder() {
        if (!CallService.louder(this)) Toast.makeText(this, R.string.call_loudest, Toast.LENGTH_SHORT).show();
    }

    /**
     * A big button: icon and label side by side, centred, optionally a second
     * line (e.g. "On"). Filled = solid colour with white content; else a card
     * with a dark outline.
     */
    private View action(int iconRes, String label, String sub, int colorRes, boolean filled,
                        int textSp, Runnable onTap) {
        int fg = filled ? Palette.ON_COLOR : Ui.color(this, R.color.text_black);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 12);
        b.setPadding(pad, pad, pad, pad);
        b.setMinimumHeight(Ui.dp(this, Ui.BUTTON_HEIGHT));
        b.setBackground(filled
                ? Ui.ripple(this, R.color.press_light, Ui.color(this, colorRes), 0, 28)
                : Ui.ripple(this, R.color.press_dark, Ui.color(this, R.color.card),
                        Ui.color(this, R.color.outline), 28));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(sub == null ? label : label + ", " + sub);
        b.setOnClickListener(v -> {
            if (SystemClock.uptimeMillis() < guardUntil) return; // see class comment
            try {
                onTap.run();
            } catch (Throwable t) {
                Log.e(TAG, "button", t);
            }
        });

        if (iconRes != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(fg));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = Ui.dp(this, textSp * 1.4f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
            ilp.setMarginEnd(Ui.dp(this, 12));
            b.addView(icon, ilp);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER);
        TextView l = Ui.bold(this, label, textSp);
        l.setTextColor(fg);
        l.setGravity(Gravity.CENTER);
        l.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texts.addView(l, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fitOneLine(l, textSp);
        if (sub != null) {
            TextView s = Ui.text(this, sub, textSp - 2);
            s.setTextColor(fg);
            s.setGravity(Gravity.CENTER);
            s.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            texts.addView(s, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fitOneLine(s, textSp - 2);
        }
        // The words take the width the icon leaves, so they can be fitted to it.
        b.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    /**
     * Keeps a button word on one line: shrinks it (down to 16sp) until it fits the
     * width it got, instead of breaking it in the middle ("Micropho-ne") and
     * pushing the line below out of the button.
     */
    private static void fitOneLine(TextView t, int maxSp) {
        t.setMaxLines(1);
        t.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            int avail = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
            if (avail <= 0) return;
            float sp = maxSp;
            android.text.TextPaint p = new android.text.TextPaint(t.getPaint());
            float scale = t.getResources().getDisplayMetrics().scaledDensity;
            p.setTextSize(sp * scale);
            while (sp > 16 && p.measureText(t.getText().toString()) > avail) {
                sp -= 1;
                p.setTextSize(sp * scale);
            }
            if (Math.abs(t.getTextSize() - sp * scale) > 0.5f) {
                final float fit = sp;
                t.post(() -> t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fit));
            }
        });
    }

    /** Answer/Decline: one choice at a time, until the call reacts. */
    private boolean choiceOk() {
        long now = SystemClock.uptimeMillis();
        if (now < choiceLockedUntil) return false;
        choiceLockedUntil = now + CHOICE_LOCK_MS;
        return true;
    }

    private LinearLayout.LayoutParams sized(int heightDp, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, heightDp));
        lp.topMargin = Ui.dp(this, topMarginDp);
        return lp;
    }

    private String simLabel(PhoneAccountHandle h, int n) {
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            PhoneAccount a = tm != null ? tm.getPhoneAccount(h) : null;
            CharSequence label = a != null ? a.getLabel() : null;
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable t) {
            Log.w(TAG, "sim label", t);
        }
        return getString(R.string.call_sim_n, n);
    }
}
