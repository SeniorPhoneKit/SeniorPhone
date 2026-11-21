package app.phueber.seniorphone;

import android.app.Activity;
import android.view.View;
import android.widget.Button;

/**
 * The green "Back to the call" button at the top of the contact list, the
 * number pad and a contact page, shown while a call is on: the senior pressed
 * Home during the call and came back through an app icon or a widget, and
 * notifications may be off, so this may be the only way back. It follows
 * CallService between start() and stop(), so it appears and goes as the call
 * starts and ends, not only when the screen is resumed.
 */
final class CallBanner implements CallService.Listener {
    final Button view;
    private final Runnable onStartOrEnd;
    private boolean live;

    /** onStartOrEnd (may be null) runs when a call starts or the last one ends, also while stopped. */
    CallBanner(Activity a, Runnable onStartOrEnd) {
        this.onStartOrEnd = onStartOrEnd;
        live = CallService.hasLiveCall();
        view = Ui.button(a, a.getString(R.string.contacts_back_to_call), R.color.green);
        view.setOnClickListener(v -> CallActivity.open(a));
        view.setVisibility(live ? View.VISIBLE : View.GONE);
    }

    void start() {
        CallService.addListener(this);
        onCallsChanged();
    }

    void stop() {
        CallService.removeListener(this);
    }

    @Override
    public void onCallsChanged() {
        boolean now = CallService.hasLiveCall();
        view.setVisibility(now ? View.VISIBLE : View.GONE);
        if (now == live) return;
        live = now;
        if (onStartOrEnd != null) onStartOrEnd.run();
    }
}
