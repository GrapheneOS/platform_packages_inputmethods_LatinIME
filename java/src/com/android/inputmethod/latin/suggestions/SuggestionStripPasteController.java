package com.android.inputmethod.latin.suggestions;

import android.content.Context;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.android.inputmethod.latin.R;

/** Owns clipboard observation and Paste presentation in the suggestion strip. */
final class SuggestionStripPasteController
        implements ClipboardAvailabilityMonitor.Listener {
    interface Listener {
        boolean onPaste();
        boolean isTextFieldEmpty();
        void onPasteActionAvailabilityChanged();
    }

    private static final long PASTE_BUTTON_REENABLE_DELAY_MILLIS = 500;

    private final View mRootView;
    private final View mSuggestionsStrip;
    private final View mImportantNoticeStrip;
    private final HorizontalScrollView mPasteActions;
    private final ImageButton mPasteModeKey;
    private final View mPasteButton;
    private final Runnable mDismissMoreSuggestions;
    private final ClipboardAvailabilityMonitor mClipboardMonitor;
    private final PasteActionState mState = new PasteActionState();

    private Listener mListener;

    private final Runnable mReenablePasteButton = new Runnable() {
        @Override
        public void run() {
            mPasteButton.setEnabled(true);
        }
    };

    private final Runnable mAlignPasteActions = new Runnable() {
        @Override
        public void run() {
            final View content = mPasteActions.getChildAt(0);
            final int scrollX = ViewCompat.getLayoutDirection(mPasteActions)
                    == ViewCompat.LAYOUT_DIRECTION_RTL
                    ? Math.max(0, content.getWidth() - mPasteActions.getWidth()) : 0;
            mPasteActions.scrollTo(scrollX, 0);
        }
    };

    SuggestionStripPasteController(final Context context, final View rootView,
            final ViewGroup suggestionsStrip, final View importantNoticeStrip,
            final HorizontalScrollView pasteActions, final ImageButton pasteModeKey,
            final View pasteButton, final ImageView pasteButtonIcon,
            final TextView pasteButtonText, final OnClickListener clickListener,
            final Runnable dismissMoreSuggestions) {
        mRootView = rootView;
        mSuggestionsStrip = suggestionsStrip;
        mImportantNoticeStrip = importantNoticeStrip;
        mPasteActions = pasteActions;
        mPasteModeKey = pasteModeKey;
        mPasteButton = pasteButton;
        mDismissMoreSuggestions = dismissMoreSuggestions;
        mClipboardMonitor = new ClipboardAvailabilityMonitor(context, rootView, this);

        mPasteActions.setFocusable(false);
        pasteButtonIcon.setImageTintList(pasteButtonText.getTextColors());
        mPasteModeKey.setImageTintList(pasteButtonText.getTextColors());
        mPasteModeKey.setOnClickListener(clickListener);
        mPasteButton.setOnClickListener(clickListener);
        showSuggestionsStrip();
    }

    void setListener(final Listener listener) {
        mListener = listener;
    }

    void setLayoutDirection(final boolean isRtlLanguage) {
        final int layoutDirection = isRtlLanguage ? ViewCompat.LAYOUT_DIRECTION_RTL
                : ViewCompat.LAYOUT_DIRECTION_LTR;
        ViewCompat.setLayoutDirection(mRootView, layoutDirection);
        ViewCompat.setLayoutDirection(mSuggestionsStrip, layoutDirection);
        ViewCompat.setLayoutDirection(mImportantNoticeStrip, layoutDirection);
        ViewCompat.setLayoutDirection(mPasteActions, layoutDirection);
        ViewCompat.setLayoutDirection(mPasteModeKey, layoutDirection);
    }

    void showSuggestionsStrip() {
        mSuggestionsStrip.setVisibility(View.VISIBLE);
        mImportantNoticeStrip.setVisibility(View.INVISIBLE);
        mPasteActions.setVisibility(View.INVISIBLE);
    }

    void showImportantNoticeStrip() {
        mState.closeTemporaryMode();
        mSuggestionsStrip.setVisibility(View.INVISIBLE);
        mImportantNoticeStrip.setVisibility(View.VISIBLE);
        mPasteActions.setVisibility(View.INVISIBLE);
        mPasteModeKey.setVisibility(View.INVISIBLE);
    }

    boolean isShowingSuggestionsStrip() {
        return mSuggestionsStrip.getVisibility() == View.VISIBLE;
    }

    boolean updateState(final boolean eligible, final boolean suggestionsCanTakePriority) {
        mState.updateContext(eligible, false /* suggestionsTakePriority */);
        refreshClipboardAvailabilityState();
        // InputConnection queries can block the UI thread. Ask about editor text only when an
        // available clip and visible suggestions make the answer affect presentation.
        final boolean suggestionsTakePriority = mState.shouldOfferPasteAutomatically()
                && suggestionsCanTakePriority
                && mListener != null
                && !mListener.isTextFieldEmpty();
        mState.updateContext(eligible, suggestionsTakePriority);
        updatePresentation();
        return mState.hasPasteAction();
    }

    void onSuggestionsShown() {
        mState.closeTemporaryMode();
        showSuggestionsStrip();
        updatePresentation();
    }

    void closeTemporaryMode() {
        mState.closeTemporaryMode();
        updatePresentation();
    }

    void reset() {
        cancelPasteButtonDebounce();
        mPasteActions.removeCallbacks(mAlignPasteActions);
        mState.reset();
        mClipboardMonitor.setActive(false);
        updatePresentation();
    }

    private void cancelPasteButtonDebounce() {
        mPasteButton.removeCallbacks(mReenablePasteButton);
        mPasteButton.setEnabled(true);
    }

    @Override
    public void onClipboardAvailabilityChanged(final boolean available) {
        updateClipboardAvailability(available, true /* notifyAvailabilityChange */);
    }

    private void synchronizeClipboardAvailability(final boolean notifyAvailabilityChange) {
        // A hidden keyboard should neither observe clipboard changes nor inspect its state.
        final boolean availabilityChanged = refreshClipboardAvailabilityState();
        updatePresentation();
        if (notifyAvailabilityChange && availabilityChanged && mListener != null) {
            mListener.onPasteActionAvailabilityChanged();
        }
    }

    private boolean refreshClipboardAvailabilityState() {
        final boolean active = mState.shouldMonitorClipboard()
                && mRootView.isAttachedToWindow()
                && mRootView.getWindowVisibility() == View.VISIBLE;
        return setClipboardAvailability(mClipboardMonitor.setActive(active));
    }

    private void updateClipboardAvailability(final boolean available,
            final boolean notifyAvailabilityChange) {
        final boolean availabilityChanged = setClipboardAvailability(available);
        updatePresentation();
        if (notifyAvailabilityChange && availabilityChanged && mListener != null) {
            mListener.onPasteActionAvailabilityChanged();
        }
    }

    private boolean setClipboardAvailability(final boolean available) {
        final boolean wasAvailable = mState.hasPasteAction();
        mState.setClipboardAvailability(available, mClipboardMonitor.shouldOfferAutomatically());
        return wasAvailable != mState.hasPasteAction();
    }

    private void updatePresentation() {
        // Clipboard actions must not replace the contact permission notice.
        if (mImportantNoticeStrip.getVisibility() == View.VISIBLE) {
            mPasteModeKey.setVisibility(View.INVISIBLE);
            return;
        }

        if (mState.shouldShowPasteActions()) {
            mDismissMoreSuggestions.run();
            final boolean shouldRealign = mPasteActions.getVisibility() != View.VISIBLE;
            mSuggestionsStrip.setVisibility(View.INVISIBLE);
            mImportantNoticeStrip.setVisibility(View.INVISIBLE);
            mPasteActions.setVisibility(View.VISIBLE);
            if (shouldRealign) {
                // Wait for measurement so an overflowing RTL row starts at its logical beginning.
                mPasteActions.removeCallbacks(mAlignPasteActions);
                mPasteActions.post(mAlignPasteActions);
            }
        } else if (mPasteActions.getVisibility() == View.VISIBLE) {
            showSuggestionsStrip();
        }

        switch (mState.getSideButtonMode()) {
            case CLOSE_PASTE_MODE:
                mPasteModeKey.setImageResource(R.drawable.ic_close_paste_mode);
                mPasteModeKey.setContentDescription(mRootView.getResources().getString(
                        R.string.spoken_description_show_suggestions));
                mPasteModeKey.setVisibility(View.VISIBLE);
                break;
            case OPEN_PASTE_MODE:
                mPasteModeKey.setImageResource(R.drawable.ic_clipboard_action);
                mPasteModeKey.setContentDescription(mRootView.getResources().getString(
                        R.string.spoken_description_show_paste_button));
                mPasteModeKey.setVisibility(View.VISIBLE);
                break;
            case NONE:
                mPasteModeKey.setVisibility(View.INVISIBLE);
                break;
        }
    }

    boolean onClick(final View view) {
        if (view == mPasteModeKey) {
            if (mState.getSideButtonMode() == PasteActionState.SideButtonMode.CLOSE_PASTE_MODE) {
                closeTemporaryMode();
                return true;
            }
            // Recheck before entering the mode because clipboard callbacks may be delayed.
            updateClipboardAvailability(mClipboardMonitor.refresh(),
                    true /* notifyAvailabilityChange */);
            mState.openTemporaryMode();
            updatePresentation();
            return true;
        }
        if (view != mPasteButton) {
            return false;
        }

        updateClipboardAvailability(mClipboardMonitor.refresh(),
                true /* notifyAvailabilityChange */);
        if (!mState.hasPasteAction()) {
            return true;
        }
        mPasteButton.setEnabled(false);
        if (!mPasteButton.postDelayed(
                mReenablePasteButton, PASTE_BUTTON_REENABLE_DELAY_MILLIS)) {
            cancelPasteButtonDebounce();
        }
        if (mListener != null && mListener.onPaste()) {
            // InputConnection reports dispatch, not whether the editor consumed the clip. Persist
            // only that dispatch and its metadata timestamp; the focused editor owns the payload.
            mClipboardMonitor.markCurrentClipDispatched();
            mState.closeTemporaryMode();
            updateClipboardAvailability(true /* available */, false /* notifyAvailabilityChange */);
        }
        return true;
    }

    void onWindowVisibilityChanged(final int visibility) {
        if (visibility != View.VISIBLE) {
            updateClipboardAvailability(mClipboardMonitor.setActive(false),
                    false /* notifyAvailabilityChange */);
            return;
        }
        synchronizeClipboardAvailability(false /* notifyAvailabilityChange */);
        // The clip may have changed while clipboard monitoring was stopped.
        if (mListener != null) {
            mListener.onPasteActionAvailabilityChanged();
        }
    }
}
