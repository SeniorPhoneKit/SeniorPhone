package app.phueber.seniorphone;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.ContactsContract;

/**
 * Keeps the widgets right while the app is not running: Android starts this
 * when the address book changes (a content-URI trigger, not a timer), it
 * redraws the widgets and waits for the next change. Only scheduled while a
 * widget is placed.
 */
public final class ContactsChangedJob extends JobService {
    private static final int JOB_ID = 0x5e1f0001;

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, ContactsChangedJob.class))
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(
                            ContactsContract.Contacts.CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(1000)
                    .setTriggerContentMaxDelay(10_000)
                    .build());
        } catch (RuntimeException e) {
            // no job scheduler, or too many jobs: the app's own refreshes remain
        }
    }

    static void cancel(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Context app = getApplicationContext();
        Contacts.BACKGROUND.execute(() -> {
            PhoneWidgets.refreshNow(app);
            jobFinished(params, false);
            // A content trigger fires once; ask again for the next change.
            if (PhoneWidgets.any(app)) schedule(app);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
