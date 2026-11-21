package app.phueber.seniorphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Call control by automation (see {@link Automation}): ANSWER, HANG_UP,
 * SPEAKER, LOUDER. The manifest guards it with ANSWER_PHONE_CALLS, so only an
 * app that may answer and end calls itself can send these: we never lend
 * another app a power the system did not give it.
 *
 * Works through CallService exactly like the buttons of the call screen, so
 * the screen and notifications follow by themselves. Without a call every
 * command does nothing. Runs on the main thread, where call state lives.
 */
public final class CallControlReceiver extends BroadcastReceiver {
    private static final String TAG = "CallControlReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = Automation.action(intent != null ? intent.getAction() : null,
                    Automation.ANSWER, Automation.HANG_UP, Automation.SPEAKER, Automation.LOUDER);
            if (action == null) {
                Log.w(TAG, "unknown action");
                return;
            }
            if (!CallService.hasLiveCall()) return; // no call: nothing to do
            if (Automation.ANSWER.equals(action)) {
                CallService.answerRinging();
            } else if (Automation.HANG_UP.equals(action)) {
                CallService.endShown();
            } else if (Automation.SPEAKER.equals(action)) {
                Automation.Speaker s = Automation.parseSpeaker(intent.getStringExtra(Automation.EXTRA_STATE));
                if (s == null) {
                    Log.w(TAG, "bad state for SPEAKER");
                    return;
                }
                boolean on = s == Automation.Speaker.ON
                        || (s == Automation.Speaker.TOGGLE && CallService.audioRoute() != CallState.R_SPEAKER);
                CallService.setSpeaker(on);
            } else if (Automation.LOUDER.equals(action)) {
                CallService.louder(context);
            }
        } catch (Throwable t) {
            Log.e(TAG, "action", t);
        }
    }
}
