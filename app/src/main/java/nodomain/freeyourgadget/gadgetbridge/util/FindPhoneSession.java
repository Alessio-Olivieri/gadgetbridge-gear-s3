package nodomain.freeyourgadget.gadgetbridge.util;

import java.util.UUID;

/** Opt-in ownership for asynchronous Find Phone activities; legacy device events remain tokenless. */
public final class FindPhoneSession {
    public static final FindPhoneSession INSTANCE = new FindPhoneSession();
    public static final String EXTRA_TOKEN = "find_phone_session_token";
    public interface Listener {
        void started();
        void finished(boolean failed);
    }
    private String token;
    private Listener listener;
    private Runnable stop;
    private boolean started, legacyActive;

    public synchronized String begin(final Listener listener) {
        if (token != null || legacyActive) return null;
        token = UUID.randomUUID().toString();
        this.listener = listener;
        started = false;
        return token;
    }
    public synchronized boolean current(final String candidate) {
        return candidate != null && candidate.equals(token);
    }
    /** Serializes playback startup against cancellation, including a delayed PendingIntent. */
    public synchronized boolean attachAndRun(final String candidate, final Runnable stop, final Runnable start) {
        if (!current(candidate) || started) return false;
        this.stop = stop;
        start.run();
        if (current(candidate)) {
            started = true;
            listener.started();
        }
        return true;
    }
    public void cancel(final String candidate) {
        final Runnable cleanup;
        synchronized (this) {
            if (!current(candidate)) return;
            cleanup = stop;
            clear();
        }
        if (cleanup != null) cleanup.run();
    }
    public void finish(final String candidate, final boolean failed) {
        final Listener completion;
        synchronized (this) {
            if (!current(candidate)) return;
            completion = listener;
            clear();
        }
        completion.finished(failed);
    }
    public synchronized boolean idle() { return token == null && !legacyActive; }
    public synchronized boolean enterLegacy() {
        if (token != null) return false;
        legacyActive = true;
        return true;
    }
    public synchronized void leaveLegacy() { legacyActive = false; }
    private void clear() { token = null; listener = null; stop = null; started = false; }
}
