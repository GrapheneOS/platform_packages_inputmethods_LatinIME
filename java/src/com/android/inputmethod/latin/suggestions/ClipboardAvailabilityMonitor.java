package com.android.inputmethod.latin.suggestions;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.View;

import java.util.concurrent.TimeUnit;

/** Tracks clipboard availability from metadata without reading or retaining clipboard contents. */
final class ClipboardAvailabilityMonitor
        implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final long REFRESH_DELAY_MILLIS = 100;
    private static final long AUTOMATIC_OFFER_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long NO_CLIP_TIMESTAMP = Long.MIN_VALUE;
    private static final String PREFERENCES_FILE = "paste_action_state";
    private static final String PREF_LAST_DISPATCHED_CLIP_TIMESTAMP =
            "suggestion_strip_last_dispatched_clip_timestamp";
    private static final String CONTROL_KEYGUARD_PERMISSION =
            "android.permission.CONTROL_KEYGUARD";

    interface Listener {
        void onClipboardAvailabilityChanged(boolean available);
    }

    private final Context mContext;
    private final ClipboardManager mClipboardManager;
    private final KeyguardManager mKeyguardManager;
    private final SharedPreferences mPreferences;
    private final View mCallbackView;
    private final Listener mListener;

    private boolean mActive;
    private boolean mRefreshScheduled;
    private long mClipTimestamp = NO_CLIP_TIMESTAMP;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            mRefreshScheduled = false;
            refreshAndNotify();
        }
    };

    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelPendingRefresh();
                clearAndNotify();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                refreshAndNotify();
            }
        }
    };

    ClipboardAvailabilityMonitor(final Context context, final View callbackView,
            final Listener listener) {
        mContext = context;
        mClipboardManager = (ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mPreferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
        mCallbackView = callbackView;
        mListener = listener;
    }

    boolean setActive(final boolean active) {
        if (mActive == active) {
            return isClipboardAvailable();
        }
        if (!active) {
            stop();
            return isClipboardAvailable();
        }

        mActive = true;
        mClipboardManager.addPrimaryClipChangedListener(this);
        // Clipboard callbacks do not report lock transitions, so resample around screen and
        // keyguard interaction changes. Protected broadcasts plus the signature permission stop
        // apps from driving this exported receiver; USER_PRESENT is sent across UIDs by SystemUI.
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(mScreenStateReceiver, filter, CONTROL_KEYGUARD_PERMISSION,
                null /* scheduler */, Context.RECEIVER_EXPORTED);
        refreshClipboardState();
        return isClipboardAvailable();
    }

    boolean refresh() {
        cancelPendingRefresh();
        refreshClipboardState();
        return isClipboardAvailable();
    }

    boolean shouldOfferAutomatically() {
        // Age is intentionally sampled lazily; an already visible chip does not need an expiry
        // timer.
        final long clipAgeMillis = System.currentTimeMillis() - mClipTimestamp;
        return isClipboardAvailable()
                && clipAgeMillis >= 0
                && clipAgeMillis < AUTOMATIC_OFFER_MAX_AGE_MILLIS
                && mClipTimestamp != mPreferences.getLong(
                        PREF_LAST_DISPATCHED_CLIP_TIMESTAMP, NO_CLIP_TIMESTAMP);
    }

    void markCurrentClipDispatched() {
        if (!isClipboardAvailable()) {
            return;
        }
        // Persistence prevents the same clip from being offered again after an editor or process
        // change. The timestamp is clipboard metadata and does not reveal the copied value.
        mPreferences.edit().putLong(PREF_LAST_DISPATCHED_CLIP_TIMESTAMP, mClipTimestamp).apply();
    }

    private void stop() {
        cancelPendingRefresh();
        if (mActive) {
            mClipboardManager.removePrimaryClipChangedListener(this);
            mContext.unregisterReceiver(mScreenStateReceiver);
            mActive = false;
        }
        clearClipboardState();
    }

    @Override
    public void onPrimaryClipChanged() {
        // Coalesce rapid replacements so clipboard writers cannot make every callback perform a
        // synchronous service query on the keyboard UI thread.
        if (!mActive || mRefreshScheduled) {
            return;
        }
        mRefreshScheduled = true;
        if (!mCallbackView.postDelayed(mRefresh, REFRESH_DELAY_MILLIS)) {
            mRefreshScheduled = false;
        }
    }

    private void cancelPendingRefresh() {
        if (!mRefreshScheduled) {
            return;
        }
        mCallbackView.removeCallbacks(mRefresh);
        mRefreshScheduled = false;
    }

    private void refreshAndNotify() {
        cancelPendingRefresh();
        final boolean wasAvailable = isClipboardAvailable();
        final boolean wasAutomaticallyOffered = shouldOfferAutomatically();
        refreshClipboardState();
        if (wasAvailable != isClipboardAvailable()
                || wasAutomaticallyOffered != shouldOfferAutomatically()) {
            mListener.onClipboardAvailabilityChanged(isClipboardAvailable());
        }
    }

    private void clearAndNotify() {
        if (!isClipboardAvailable()) {
            return;
        }
        clearClipboardState();
        mListener.onClipboardAvailabilityChanged(false);
    }

    private void clearClipboardState() {
        mClipTimestamp = NO_CLIP_TIMESTAMP;
    }

    private boolean isClipboardAvailable() {
        return mClipTimestamp != NO_CLIP_TIMESTAMP;
    }

    private void refreshClipboardState() {
        // ClipboardService also withholds clipboard state while the device is locked. The explicit
        // check removes a cached action promptly, so the strip does not offer a paste that platform
        // policy will reject.
        // Do not read ClipData here. A full read can grant content URIs before the user chooses an
        // action; the focused editor reads and handles the clip after the action is dispatched.
        if (!mActive || mKeyguardManager == null || mKeyguardManager.isDeviceLocked()) {
            clearClipboardState();
            return;
        }
        final ClipDescription description = mClipboardManager.getPrimaryClipDescription();
        // The chip invokes the editor's generic Paste action, which can deliver content through a
        // receive content handler even when EditorInfo.contentMimeTypes does not advertise support
        // for the separate InputConnection.commitContent() API. Match the clipboard availability
        // check in TextView.canPaste() by offering Paste for every clip, then let the focused
        // editor decide whether to consume it.
        if (description == null) {
            clearClipboardState();
            return;
        }
        mClipTimestamp = description.getTimestamp();
    }
}
