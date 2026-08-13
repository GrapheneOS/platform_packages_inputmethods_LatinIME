package com.android.inputmethod.latin.suggestions;

/** Keeps clipboard availability separate from whether the current clip is offered automatically. */
final class PasteActionState {
    enum SideButtonMode {
        NONE,
        OPEN_PASTE_MODE,
        CLOSE_PASTE_MODE,
    }

    private boolean mEligible;
    private boolean mClipboardAvailable;
    private boolean mAutomaticallyOffer;
    private boolean mSuggestionsTakePriority;
    private boolean mTemporaryMode;

    public void updateContext(final boolean eligible, final boolean suggestionsTakePriority) {
        mEligible = eligible;
        mSuggestionsTakePriority = suggestionsTakePriority;
        // The caller closes temporary mode when the editor changes or new suggestions arrive.
        if (!eligible) {
            mTemporaryMode = false;
        }
    }

    public void setClipboardAvailability(final boolean available,
            final boolean automaticallyOffer) {
        mClipboardAvailable = available;
        mAutomaticallyOffer = available && automaticallyOffer;
        if (!available) {
            mTemporaryMode = false;
        }
    }

    public void openTemporaryMode() {
        if (getSideButtonMode() != SideButtonMode.OPEN_PASTE_MODE) {
            return;
        }
        mTemporaryMode = true;
    }

    public void closeTemporaryMode() {
        mTemporaryMode = false;
    }

    public void reset() {
        mEligible = false;
        mClipboardAvailable = false;
        mAutomaticallyOffer = false;
        mSuggestionsTakePriority = false;
        mTemporaryMode = false;
    }

    public boolean shouldMonitorClipboard() {
        return mEligible;
    }

    public boolean hasPasteAction() {
        return mEligible && mClipboardAvailable;
    }

    public boolean shouldShowPasteActions() {
        return hasPasteAction()
                && (mTemporaryMode || (mAutomaticallyOffer && !mSuggestionsTakePriority));
    }

    public boolean shouldOfferPasteAutomatically() {
        return hasPasteAction() && mAutomaticallyOffer;
    }

    public SideButtonMode getSideButtonMode() {
        if (hasPasteAction() && mTemporaryMode) {
            return SideButtonMode.CLOSE_PASTE_MODE;
        }
        if (hasPasteAction() && (mSuggestionsTakePriority || !mAutomaticallyOffer)) {
            return SideButtonMode.OPEN_PASTE_MODE;
        }
        return SideButtonMode.NONE;
    }
}
