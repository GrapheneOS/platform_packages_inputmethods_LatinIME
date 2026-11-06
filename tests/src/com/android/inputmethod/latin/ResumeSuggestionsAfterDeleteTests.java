/*
 * Copyright (C) 2026 GrapheneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin;

import android.text.InputFilter;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.test.filters.LargeTest;

import com.android.inputmethod.event.Event;
import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.inputlogic.InputLogic;
import com.android.inputmethod.latin.inputlogic.InputLogic.PendingSuggestionsRestart;
import com.android.inputmethod.latin.settings.Settings;
import com.android.inputmethod.latin.utils.ScriptUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Uses an EditText and wrapped InputConnection to simulate EditorInfo/InputConnection contracts,
 * including delayed edits, without a real Compose runtime.
 */
@LargeTest
public class ResumeSuggestionsAfterDeleteTests extends InputTestsBase {
    private TrackingInputConnection mTrackingConnection;

    private class TrackingInputConnection extends InputConnectionWrapper {
        int mComposingRegionCalls;
        int mDeleteKeyDowns;
        boolean mTruncateNextCacheRead;
        boolean mTruncateCacheReads;
        int mTruncatedCacheReads;
        int mNullCacheReadsRemaining;
        int mNullCacheReads;
        boolean mAlterNextStyledRead;
        int mAlteredStyledReads;
        int mStyledReads;
        boolean mUnavailableStyledRead;
        boolean mLagDeletes;
        int mPendingDeletes;
        Runnable mDuringStyledRead;
        Runnable mDuringSetComposingRegion;
        boolean mRecordPaste;
        int mPasteCalls;
        final ArrayList<Integer> mComposingRegionStarts = new ArrayList<>();

        TrackingInputConnection(final InputConnection target) {
            super(target, false);
        }

        @Override
        public CharSequence getTextBeforeCursor(final int length, final int flags) {
            final CharSequence text = super.getTextBeforeCursor(length, flags);
            if (length == 40 && flags == GET_TEXT_WITH_STYLES) {
                ++mStyledReads;
                final Runnable duringRead = mDuringStyledRead;
                mDuringStyledRead = null;
                if (duringRead != null) {
                    duringRead.run();
                }
                if (mUnavailableStyledRead) {
                    return null;
                }
            }
            if (mNullCacheReadsRemaining > 0 && length == Constants.EDITOR_CONTENTS_CACHE_SIZE
                    && flags == 0) {
                --mNullCacheReadsRemaining;
                ++mNullCacheReads;
                return null;
            }
            if ((mTruncateNextCacheRead || mTruncateCacheReads)
                    && length == Constants.EDITOR_CONTENTS_CACHE_SIZE
                    && flags == 0) {
                mTruncateNextCacheRead = false;
                ++mTruncatedCacheReads;
                assertNotNull(text);
                assertTrue(text.length() > 2);
                return text.subSequence(text.length() - 2, text.length());
            }
            if (mAlterNextStyledRead && length == 40 && flags == GET_TEXT_WITH_STYLES) {
                mAlterNextStyledRead = false;
                ++mAlteredStyledReads;
                assertNotNull(text);
                assertTrue(text.length() > 0);
                return text.subSequence(0, text.length() - 1).toString() + "x";
            }
            return text;
        }

        @Override
        public boolean setComposingRegion(final int start, final int end) {
            ++mComposingRegionCalls;
            mComposingRegionStarts.add(start);
            final Runnable duringRegion = mDuringSetComposingRegion;
            mDuringSetComposingRegion = null;
            if (duringRegion != null) {
                duringRegion.run();
            }
            return super.setComposingRegion(start, end);
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                ++mDeleteKeyDowns;
                if (mLagDeletes) {
                    ++mPendingDeletes;
                }
            }
            if (mLagDeletes && event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                return true;
            }
            // The fixture's EditText has no ViewRoot to receive forwarded input events.
            return mEditText.dispatchKeyEvent(event);
        }

        @Override
        public boolean deleteSurroundingText(final int beforeLength, final int afterLength) {
            if (mLagDeletes) {
                assertEquals(0, afterLength);
                mPendingDeletes += beforeLength;
                return true;
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        void applyDeletes(final int count) {
            assertTrue(count <= mPendingDeletes);
            for (int i = 0; i < count; ++i) {
                mEditText.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                mEditText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
            }
            mPendingDeletes -= count;
        }

        @Override
        public boolean performContextMenuAction(final int id) {
            if (mRecordPaste && id == android.R.id.paste) {
                ++mPasteCalls;
                try {
                    assertNull(pendingRequest());
                } catch (final Exception e) {
                    throw new AssertionError(e);
                }
                return true;
            }
            return super.performContextMenuAction(id);
        }
    }

    @Override
    protected EditorInfo enrichEditorInfo(final EditorInfo ei) {
        ei.fieldId = View.NO_ID;
        ei.fieldName = null;
        return ei;
    }

    @Override
    protected InputConnection enrichInputConnection(final InputConnection ic) {
        mTrackingConnection = new TrackingInputConnection(ic);
        return mTrackingConnection;
    }

    private void setCommittedText(final String text) {
        mInputConnection.commitText(text, 1);
        sendUpdateForCursorMoveTo(text.length());
        mLatinIME.mHandler.removeAllMessages();
        mTrackingConnection.mComposingRegionCalls = 0;
        mTrackingConnection.mDeleteKeyDowns = 0;
    }

    private void runDelayedMessages() {
        sleep(DELAY_TO_WAIT_FOR_PREDICTIONS_MILLIS);
        runMessages();
    }

    private void typeEchoAndMoveCursorInsideWord() {
        setCommittedText("alpha ");
        type("echo");
        sendUpdateForCursorMoveTo(10);
        mLatinIME.mHandler.removeAllMessages();
        sendUpdateForCursorMoveTo(9);
    }

    private void assertComposition(final String text, final int selection,
            final int composingStart, final int composingEnd) {
        assertEquals(text, mEditText.getText().toString());
        assertEquals(selection, mEditText.getSelectionStart());
        assertEquals(selection, mEditText.getSelectionEnd());
        assertEquals(composingStart,
                BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(composingEnd, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals("expected selection start", selection,
                mLatinIME.mInputLogic.mConnection.getExpectedSelectionStart());
        assertEquals("expected selection end", selection,
                mLatinIME.mInputLogic.mConnection.getExpectedSelectionEnd());
    }

    private void assertUnknownExpectedSelection() {
        assertEquals(-1, mLatinIME.mInputLogic.mConnection.getExpectedSelectionStart());
        assertEquals(-1, mLatinIME.mInputLogic.mConnection.getExpectedSelectionEnd());
    }

    private String typeEchoAfterLongPrefix() {
        final StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 200; ++i) {
            prefix.append("alpha ");
        }
        setCommittedText(prefix.toString());
        type("echo");
        sendUpdateForCursorMoveTo(1204);
        mLatinIME.mHandler.removeAllMessages();
        return prefix.toString();
    }

    private void checkAllCapsRecoveryDeclines(final boolean longText, final boolean compose) {
        final StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < (longText ? 200 : 1); ++i) {
            prefix.append("ALPHA ");
        }
        setCommittedText(prefix.toString());
        mLatinIME.getCurrentInputEditorInfo().fieldId = compose ? View.NO_ID : 1;
        mEditText.setFilters(new InputFilter[] {new InputFilter.AllCaps()});
        mLatinIME.mInputLogic.mConnection.commitText("echo ", 1);
        type(Constants.CODE_DELETE);
        final int cursor = prefix.length() + 4;
        assertEquals(prefix + "ECHO", mEditText.getText().toString());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        final int reads = mTrackingConnection.mStyledReads;
        sendUpdateForCursorMoveTo(cursor);

        assertComposition(prefix + "ECHO", cursor, -1, -1);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertFalse(mLatinIME.mInputLogic.mConnection.hasComposingText());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        runDelayedMessages();
        assertComposition(prefix + "ECHO", cursor, -1, -1);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertFalse(mLatinIME.mInputLogic.mConnection.hasComposingText());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals(reads + 2, mTrackingConnection.mStyledReads);
    }

    public void testAllCapsRecoveryDeclinesShortOrdinary() {
        checkAllCapsRecoveryDeclines(false, false);
    }

    public void testAllCapsRecoveryDeclinesLongOrdinary() {
        checkAllCapsRecoveryDeclines(true, false);
    }

    public void testAllCapsRecoveryDeclinesShortCompose() {
        checkAllCapsRecoveryDeclines(false, true);
    }

    public void testAllCapsRecoveryDeclinesLongCompose() {
        checkAllCapsRecoveryDeclines(true, true);
    }

    public void testTypingBeforeCursorResumeWithTruncatedCacheRead() {
        typeEchoAndMoveCursorInsideWord();
        mTrackingConnection.mTruncateNextCacheRead = true;
        sendUpdateForCursorMoveTo(10);
        type("s");

        assertEquals(1, mTrackingConnection.mTruncatedCacheReads);
        assertComposition("alpha echos", 11, 6, 11);
    }

    public void testTypingBeforeCursorResumeKeepsComposingCache() {
        typeEchoAndMoveCursorInsideWord();
        sendUpdateForCursorMoveTo(10);
        type("s");

        assertComposition("alpha echos", 11, 6, 11);
    }

    public void testCursorResumeBailKeepsRestoredComposition() {
        typeEchoAndMoveCursorInsideWord();
        sendUpdateForCursorMoveTo(10);
        mTrackingConnection.mAlterNextStyledRead = true;
        mLatinIME.mHandler.postResumeSuggestions(true /* shouldDelay */);
        runDelayedMessages();

        assertEquals(1, mTrackingConnection.mAlteredStyledReads);
        assertEquals(2, mTrackingConnection.mComposingRegionCalls);
        assertEquals(4, mLatinIME.mInputLogic.getComposingLength());
        assertComposition("alpha echo", 10, 6, 10);
        type("s");
        assertComposition("alpha echos", 11, 6, 11);
    }

    public void testCursorResumeAtRestoredWordEndMarksWordResumed() throws Exception {
        typeEchoAndMoveCursorInsideWord();
        sendUpdateForCursorMoveTo(10);
        final Field composerField =
                mLatinIME.mInputLogic.getClass().getDeclaredField("mWordComposer");
        composerField.setAccessible(true);
        final WordComposer composer = (WordComposer)composerField.get(mLatinIME.mInputLogic);
        assertFalse(composer.isResumed());
        runDelayedMessages();

        assertTrue(composer.isResumed());
        assertEquals(3, mTrackingConnection.mComposingRegionCalls);
        assertComposition("alpha echo", 10, 6, 10);
        type("s");
        assertComposition("alpha echos", 11, 6, 11);
    }

    public void testRetryResetCachesPreservesComposition() {
        setCommittedText("alpha ");
        type("echo");
        sendUpdateForCursorMoveTo(10);
        mLatinIME.mHandler.removeAllMessages();
        mLatinIME.mHandler.postResetCaches(false /* tryResumeSuggestions */,
                0 /* remainingTries */);
        runMessages();

        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
        assertComposition("alpha echo", 10, 6, 10);
        type("s");
        assertComposition("alpha echos", 11, 6, 11);
    }

    public void testRetryResetCachesFailureRetiresComposition() {
        setCommittedText("alpha ");
        type("echo");
        sendUpdateForCursorMoveTo(10);
        mLatinIME.mHandler.removeAllMessages();
        mTrackingConnection.mNullCacheReadsRemaining = 1;
        mLatinIME.mHandler.postResetCaches(false /* tryResumeSuggestions */,
                0 /* remainingTries */);
        runMessages();

        assertEquals(1, mTrackingConnection.mNullCacheReads);
        assertEquals(0, mTrackingConnection.mNullCacheReadsRemaining);
        assertEquals("alpha echo", mEditText.getText().toString());
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        type("s");
        assertComposition("alpha echos", 11, -1, -1);
    }

    public void testRetryResetCachesDoubleFailureRetiresComposition() {
        setCommittedText("alpha ");
        type("echo");
        sendUpdateForCursorMoveTo(10);
        mLatinIME.mHandler.removeAllMessages();
        mTrackingConnection.mNullCacheReadsRemaining = 2;
        mLatinIME.mHandler.postResetCaches(false /* tryResumeSuggestions */,
                0 /* remainingTries */);
        runMessages();

        assertEquals(2, mTrackingConnection.mNullCacheReads);
        assertEquals(0, mTrackingConnection.mNullCacheReadsRemaining);
        assertEquals("alpha echo", mEditText.getText().toString());
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        type("s");
        assertComposition("alpha echos", 11, -1, -1);
    }

    public void testCursorMoveDoubleReloadFailureRetiresComposition() {
        typeEchoAndMoveCursorInsideWord();
        mTrackingConnection.mNullCacheReadsRemaining = 2;
        sendUpdateForCursorMoveTo(10);

        assertEquals(2, mTrackingConnection.mNullCacheReads);
        assertEquals(0, mTrackingConnection.mNullCacheReadsRemaining);
        assertEquals("alpha echo", mEditText.getText().toString());
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertUnknownExpectedSelection();
        type("s");
        assertEquals("alpha echos", mEditText.getText().toString());
        assertEquals(11, mEditText.getSelectionStart());
        assertEquals(11, mEditText.getSelectionEnd());
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertUnknownExpectedSelection();
        // The framework has not reported the insertion yet: the callback's old position is 10.
        assertEquals(10, mLastCursorPos);
        sendUpdateForCursorMoveTo(11);
        assertComposition("alpha echos", 11, -1, -1);
    }

    public void testLongPrefixRepostRetiresNewCompositionWithoutReplacingPrefix() {
        final String prefix = typeEchoAfterLongPrefix();
        mTrackingConnection.mNullCacheReadsRemaining = 2;
        assertFalse(mLatinIME.mInputLogic.retryResetCachesAndReturnSuccess(
                false /* tryResumeSuggestions */, 1 /* remainingTries */, mLatinIME.mHandler));
        assertEquals(2, mTrackingConnection.mNullCacheReads);
        assertEquals(0, mTrackingConnection.mNullCacheReadsRemaining);
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertUnknownExpectedSelection();

        type(" s");
        assertEquals(1205, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(1206, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(1, mLatinIME.mInputLogic.getComposingLength());
        final RichInputConnection connection = mLatinIME.mInputLogic.mConnection;
        final int expectedStartBeforeRepost = connection.getExpectedSelectionStart();
        final int expectedEndBeforeRepost = connection.getExpectedSelectionEnd();
        mTrackingConnection.mComposingRegionStarts.clear();
        runMessages();
        final int composingStart = BaseInputConnection.getComposingSpanStart(mEditText.getText());
        final int composingEnd = BaseInputConnection.getComposingSpanEnd(mEditText.getText());
        final int composingLength = mLatinIME.mInputLogic.getComposingLength();
        final int expectedStartAfterRepost = connection.getExpectedSelectionStart();
        final int expectedEndAfterRepost = connection.getExpectedSelectionEnd();
        type("t");

        assertEquals(prefix + "echo st", mEditText.getText().toString());
        for (final int start : mTrackingConnection.mComposingRegionStarts) {
            assertTrue("Composing region crossed into the committed prefix: " + start,
                    start >= prefix.length());
        }
        assertEquals(-1, expectedStartBeforeRepost);
        assertEquals(-1, expectedEndBeforeRepost);
        assertEquals(-1, expectedStartAfterRepost);
        assertEquals(-1, expectedEndAfterRepost);
        assertEquals(-1, composingStart);
        assertEquals(-1, composingEnd);
        assertEquals(0, composingLength);
        assertEquals(1207, mEditText.getSelectionStart());
        assertEquals(1207, mEditText.getSelectionEnd());
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertUnknownExpectedSelection();
        assertEquals(2, mTrackingConnection.mNullCacheReads);
    }

    public void testLeftwardCallbackAfterTypingWithUnknownCursor() {
        final String prefix = typeEchoAfterLongPrefix();
        sendUpdateForCursorMoveTo(1203);
        mLatinIME.mHandler.removeAllMessages();
        mTrackingConnection.mNullCacheReadsRemaining = 2;
        sendUpdateForCursorMoveTo(1204);
        assertEquals(2, mTrackingConnection.mNullCacheReads);
        assertEquals(0, mTrackingConnection.mNullCacheReadsRemaining);
        assertUnknownExpectedSelection();
        type(" s");
        assertUnknownExpectedSelection();
        sendUpdateForCursorMoveTo(1203);
        assertComposition(prefix + "echo s", 1203, -1, -1);
        type("t");

        assertComposition(prefix + "echto s", 1204, -1, -1);
        assertEquals(2, mTrackingConnection.mNullCacheReads);
    }

    public void testTruncatedReadRetirementPreservesAuthoritativeCursor() {
        typeEchoAndMoveCursorInsideWord();
        mTrackingConnection.mNullCacheReadsRemaining = 1;
        mTrackingConnection.mTruncateCacheReads = true;
        sendUpdateForCursorMoveTo(10);

        assertEquals(1, mTrackingConnection.mNullCacheReads);
        assertEquals(0, mTrackingConnection.mNullCacheReadsRemaining);
        assertComposition("alpha echo", 10, -1, -1);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertEquals(1, mTrackingConnection.mTruncatedCacheReads);
        type(" s");
        assertComposition("alpha echo s", 12, 11, 12);
        sendUpdateForCursorMoveTo(9);
        assertComposition("alpha echo s", 9, -1, -1);
        assertEquals(3, mTrackingConnection.mTruncatedCacheReads);
        type("t");
        assertComposition("alpha echto s", 10, -1, -1);
        assertEquals(1, mTrackingConnection.mNullCacheReads);
        assertEquals(3, mTrackingConnection.mTruncatedCacheReads);
    }

    public void testSuccessfulRetryWithSelectionRetiresComposer() {
        setCommittedText("alpha ");
        type("echo");
        sendUpdateForCursorMoveTo(10);
        mLatinIME.mHandler.removeAllMessages();
        assertTrue(mLatinIME.mInputLogic.mConnection.setSelection(6, 10));
        assertEquals(4, mLatinIME.mInputLogic.getComposingLength());
        assertEquals(6, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(10, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        mLatinIME.mHandler.postResetCaches(false /* tryResumeSuggestions */,
                0 /* remainingTries */);
        runMessages();

        assertEquals("alpha echo", mEditText.getText().toString());
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        assertEquals(6, mEditText.getSelectionStart());
        assertEquals(10, mEditText.getSelectionEnd());
        assertEquals(6, mLatinIME.mInputLogic.mConnection.getExpectedSelectionStart());
        assertEquals(10, mLatinIME.mInputLogic.mConnection.getExpectedSelectionEnd());
        type("s");
        assertComposition("alpha s", 7, 6, 7);
    }

    public void testPendingDeadCircumflexSurvivesCursorMoves() {
        typeEchoAndMoveCursorInsideWord();
        sendUpdateForCursorMoveTo(10);
        mLatinIME.mHandler.removeAllMessages();
        mLatinIME.onEvent(Event.createDeadEvent('^', KeyEvent.KEYCODE_6, null));
        assertComposition("alpha echo^", 11, 6, 11);
        assertEquals(5, mLatinIME.mInputLogic.getComposingLength());
        sendUpdateForCursorMoveTo(11);
        mLatinIME.mHandler.removeAllMessages();
        sendUpdateForCursorMoveTo(9);
        sendUpdateForCursorMoveTo(11);
        assertComposition("alpha echo^", 11, 6, 11);
        type("a");

        assertComposition("alpha echo\u00E2", 11, 6, 11);
        assertEquals(5, mLatinIME.mInputLogic.getComposingLength());
    }

    public void testCursorMovesPreserveSupplementaryComposingWord() {
        setCommittedText("alpha ");
        type("a\uD801\uDC28");
        assertEquals(2, mLatinIME.mInputLogic.getComposingLength());
        sendUpdateForCursorMoveTo(9);
        mLatinIME.mHandler.removeAllMessages();
        sendUpdateForCursorMoveTo(7);
        assertComposition("alpha a\uD801\uDC28", 7, 6, 9);
        sendUpdateForCursorMoveTo(9);
        assertComposition("alpha a\uD801\uDC28", 9, 6, 9);
        type("s");

        assertComposition("alpha a\uD801\uDC28s", 10, 6, 10);
    }

    public void testComposeBackspacesCoalesceBeforeResumingSuggestions() {
        setCommittedText("alpha bravo echo ");
        type(Constants.CODE_DELETE);
        type(Constants.CODE_DELETE);
        assertEquals("alpha bravo ech", mEditText.getText().toString());
        assertEquals(2, mTrackingConnection.mDeleteKeyDowns);
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);

        runDelayedMessages();
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
        assertEquals(12, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(15, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
    }

    public void testAcceleratedComposeBackspaceResumesAfterBothDeletes() {
        final StringBuilder text = new StringBuilder("alpha ");
        for (int i = 0; i < 60; ++i) {
            text.append('a');
        }
        text.append(' ');
        setCommittedText(text.toString());
        for (int i = 0; i <= Constants.DELETE_ACCELERATE_AT; ++i) {
            type(Constants.CODE_DELETE);
        }
        final int deletedLength = Constants.DELETE_ACCELERATE_AT + 2;
        assertEquals(deletedLength, mTrackingConnection.mDeleteKeyDowns);
        assertEquals(text.substring(0, text.length() - deletedLength),
                mEditText.getText().toString());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);

        runDelayedMessages();
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
        assertEquals(6, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(text.length() - deletedLength,
                BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
    }

    public void testDeleteResumePreservesNewTypedComposition() {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        type(" beta");
        assertEquals(4, mLatinIME.mInputLogic.getComposingLength());

        runDelayedMessages();
        assertEquals("alpha echo beta", mEditText.getText().toString());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals(11, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(15, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(4, mLatinIME.mInputLogic.getComposingLength());
    }

    public void testCursorResumeSupersedesDeleteResumeWithActiveComposer() {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        type(" beta");
        sendUpdateForCursorMoveTo(15);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        sendUpdateForCursorMoveTo(14);
        // A move inside the composing word preserves WordComposer while reloading the RIC cache.
        assertEquals(4, mLatinIME.mInputLogic.getComposingLength());
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);

        runDelayedMessages();
        assertEquals(2, mTrackingConnection.mComposingRegionCalls);
        assertEquals(11, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(15, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
    }

    public void testStartInputResumeSupersedesDeleteResume() {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        mLatinIME.mHandler.postResumeSuggestionsForStartInput(false /* shouldDelay */);

        runMessages();
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
        assertEquals(6, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
        assertEquals(10, BaseInputConnection.getComposingSpanEnd(mEditText.getText()));
        assertEquals(4, mLatinIME.mInputLogic.getComposingLength());
    }

    public void testDeleteResumePreservesGestureUiWithEmptyComposer() throws Exception {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        mLatinIME.onStartBatchInput();
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        // Clear the worker's batch state while keeping the gesture UI active. The UI can
        // outlive the worker until a tail result has been delivered to the main thread.
        mLatinIME.mInputLogic.onCancelBatchInput(mLatinIME.mHandler);
        runMessages();
        armRestartForTest(10);
        final SuggestedWords gestureSuggestions = new SuggestedWords(
                new ArrayList<SuggestedWords.SuggestedWordInfo>(), null, null,
                false, false, false, SuggestedWords.INPUT_STYLE_UPDATE_BATCH, 1);
        mLatinIME.mInputLogic.setSuggestedWords(gestureSuggestions);

        runDelayedMessages();
        assertSame(gestureSuggestions, mLatinIME.mInputLogic.mSuggestedWords);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        mLatinIME.onCancelBatchInput();
    }

    private static Object privateField(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private PendingSuggestionsRestart pendingRequest() throws Exception {
        return (PendingSuggestionsRestart)privateField(
                mLatinIME.mInputLogic, "mPendingSuggestionsRestart");
    }

    private void armRestartForTest(final int cursor) throws Exception {
        final Method method = InputLogic.class.getDeclaredMethod(
                "armPendingSuggestionsRestart", int.class);
        method.setAccessible(true);
        method.invoke(mLatinIME.mInputLogic, cursor);
    }

    private void tryPending(final PendingSuggestionsRestart request) {
        mLatinIME.mInputLogic.tryRestartSuggestionsAfterEditorUpdate(
                Settings.getInstance().getCurrent(), ScriptUtils.SCRIPT_LATIN, request);
    }

    private void restartNow() {
        mLatinIME.mInputLogic.restartSuggestionsOnWordTouchedByCursor(
                Settings.getInstance().getCurrent(), false, ScriptUtils.SCRIPT_LATIN);
    }

    private void acknowledgeAt(final int cursor) {
        mLatinIME.onUpdateSelection(mLastCursorPos, mLastCursorPos, cursor, cursor, -1, -1);
        mLastCursorPos = cursor;
    }

    private PendingSuggestionsRestart prepareTransformedDelete(final boolean compose)
            throws Exception {
        setCommittedText("ALPHA ");
        mLatinIME.getCurrentInputEditorInfo().fieldId = compose ? View.NO_ID : 1;
        mEditText.setFilters(new InputFilter[] {new InputFilter.AllCaps()});
        mLatinIME.mInputLogic.mConnection.commitText("echo ", 1);
        type(Constants.CODE_DELETE);
        assertEquals("ALPHA ECHO", mEditText.getText().toString());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);
        return request;
    }

    public void testTimerOnlyRecoversUntransformedLaggingDelete() throws Exception {
        setCommittedText("alpha echo ");
        mTrackingConnection.mLagDeletes = true;
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);
        assertEquals("alpha echo ", mEditText.getText().toString());
        mTrackingConnection.applyDeletes(1);
        assertNoRecoveryComposition("alpha echo");
        final int reads = mTrackingConnection.mStyledReads;
        runDelayedMessages();
        assertRecoveryComposition("alpha echo");
        assertNull(pendingRequest());
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        runDelayedMessages();
        assertRecoveryComposition("alpha echo");
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
    }

    public void testLaggingDeletesKeepIntermediateAckAndRecoverAtFinalAck() throws Exception {
        setCommittedText("alpha echo  ");
        mTrackingConnection.mLagDeletes = true;
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart first = pendingRequest();
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(first);
        assertNotNull(request);
        assertNotSame(first, request);
        mTrackingConnection.applyDeletes(1);
        acknowledgeAt(11);
        assertSame(request, pendingRequest());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        mTrackingConnection.applyDeletes(1);
        acknowledgeAt(10);
        assertComposition("alpha echo", 10, 6, 10);
        assertNull(pendingRequest());
        runDelayedMessages();
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
    }

    public void testTimerBeforeFinalAckPreservesRequestForLaterRecovery() throws Exception {
        setCommittedText("alpha echo ");
        mTrackingConnection.mLagDeletes = true;
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        runDelayedMessages();
        assertSame(request, pendingRequest());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals("alpha echo ", mEditText.getText().toString());
        mTrackingConnection.applyDeletes(1);
        acknowledgeAt(10);
        assertComposition("alpha echo", 10, 6, 10);
        assertNull(pendingRequest());
        runDelayedMessages();
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
    }

    public void testPersistentFailureNeverPostsAnotherTimer() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(true);
        runDelayedMessages();
        assertSame(request, pendingRequest());
        final int reads = mTrackingConnection.mStyledReads;
        runDelayedMessages();
        assertEquals(reads, mTrackingConnection.mStyledReads);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    public void testUnavailableReadKeepsRequestWithoutReload() throws Exception {
        final PendingSuggestionsRestart request = preparePendingDelete();
        mTrackingConnection.mUnavailableStyledRead = true;
        final int reads = mTrackingConnection.mStyledReads;
        acknowledgeAt(10);
        assertSame(request, pendingRequest());
        assertNoRecoveryComposition("alpha echo");
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        mTrackingConnection.mUnavailableStyledRead = false;
        runDelayedMessages();
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
        assertEquals(reads + 2, mTrackingConnection.mStyledReads);
    }

    public void testSkippedRestartCancelsRequest() throws Exception {
        setCommittedText("alpha echo  ");
        type(Constants.CODE_DELETE);
        assertNotNull(pendingRequest());
        acknowledgeAt(11);
        assertNull(pendingRequest());
        runDelayedMessages();
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    public void testMultiCharacterInsertDeleteCannotReviveOldRequest() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(false);
        mLatinIME.onTextInput("xy");
        assertNull(pendingRequest());
        assertEquals(12, mLatinIME.mInputLogic.mConnection.getExpectedSelectionStart());
        mLatinIME.mInputLogic.mConnection.deleteTextBeforeCursor(2);
        assertEquals(10, mLatinIME.mInputLogic.mConnection.getExpectedSelectionStart());
        tryPending(request);
        acknowledgeAt(10);
        runDelayedMessages();
        assertNull(pendingRequest());
        assertComposition("ALPHA ECHO", 10, -1, -1);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    private void checkNormalExecutionSupersedesBeforeAck(final boolean forStartInput)
            throws Exception {
        setCommittedText("alpha echo ");
        mTrackingConnection.mLagDeletes = true;
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);
        if (forStartInput) {
            mLatinIME.mHandler.postResumeSuggestionsForStartInput(false);
        } else {
            mLatinIME.mHandler.postResumeSuggestions(false);
        }
        assertSame(request, pendingRequest());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        runMessages();
        final PendingSuggestionsRestart replacement = pendingRequest();
        assertNotNull(replacement);
        assertNotSame(request, replacement);
        final int reads = mTrackingConnection.mStyledReads;
        tryPending(request);
        assertSame(replacement, pendingRequest());
        assertEquals(reads, mTrackingConnection.mStyledReads);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals("alpha echo ", mEditText.getText().toString());
        mTrackingConnection.applyDeletes(1);
        acknowledgeAt(10);
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
        tryPending(request);
        runDelayedMessages();
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        assertRecoveryComposition("alpha echo");
    }

    public void testNormalExecutionSupersedesRecoveryBeforeAck() throws Exception {
        checkNormalExecutionSupersedesBeforeAck(false);
    }

    public void testStartExecutionSupersedesRecoveryBeforeAck() throws Exception {
        checkNormalExecutionSupersedesBeforeAck(true);
    }

    public void testWorkerPostDefersCancellationUntilNormalExecution() throws Exception {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);
        assertNoRecoveryComposition("alpha echo");

        postNormalRestartFromWorker();
        assertSame("worker posting must preserve the pending request until UI execution",
                request, pendingRequest());
        assertNoRecoveryComposition("alpha echo");

        runMessages();
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
        tryPending(request);
        runDelayedMessages();
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
    }

    public void testArmingRecoveryCoalescesQueuedNormalRestart() throws Exception {
        setCommittedText("alpha echo ");
        mLatinIME.mHandler.postResumeSuggestions(false);
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);

        runMessages();
        assertSame(request, pendingRequest());
        assertNoRecoveryComposition("alpha echo");
        runDelayedMessages();
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
        runDelayedMessages();
        assertRecoveryComposition("alpha echo");
    }

    public void testCanceledRecoveryPostDoesNotRemoveSupersedingNormalRestart() throws Exception {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);
        assertNoRecoveryComposition("alpha echo");

        // Model arming paused after publication, before its timer is queued.
        mLatinIME.mHandler.cancelResumeSuggestionsAfterEditorUpdate(request);
        assertSame(request, pendingRequest());
        postNormalRestartFromWorker();
        assertSame(request, pendingRequest());
        // Resume the interrupted post after the worker has queued a normal restart.
        mLatinIME.mHandler.postResumeSuggestionsAfterEditorUpdate(request);
        assertSame(request, pendingRequest());
        assertNoRecoveryComposition("alpha echo");

        runMessages();
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
        runDelayedMessages();
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
    }

    public void testPasteCancelsBeforeContextMenuAction() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(true);
        mTrackingConnection.mRecordPaste = true;
        assertTrue(mLatinIME.onPaste());
        assertEquals(1, mTrackingConnection.mPasteCalls);
        assertNull(pendingRequest());
        tryPending(request);
        acknowledgeAt(10);
        runDelayedMessages();
        assertComposition("ALPHA ECHO", 10, -1, -1);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    private PendingSuggestionsRestart preparePendingDelete() throws Exception {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        final PendingSuggestionsRestart request = pendingRequest();
        assertNotNull(request);
        assertNoRecoveryComposition("alpha echo");
        return request;
    }

    private WordComposer wordComposer() throws Exception {
        return (WordComposer)privateField(mLatinIME.mInputLogic, "mWordComposer");
    }

    private void assertNoRecoveryComposition(final String text) throws Exception {
        assertComposition(text, 10, -1, -1);
        assertEquals(0, wordComposer().size());
        assertFalse(mLatinIME.mInputLogic.mConnection.hasComposingText());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    private void assertRecoveryComposition(final String text) throws Exception {
        assertRecoveryComposition(text, 1);
    }

    private void assertRecoveryComposition(final String text, final int regionCalls)
            throws Exception {
        assertEquals(text, mLatinIME.mInputLogic.mConnection.getTextBeforeCursor(10, 0).toString());
        assertComposition(text, 10, 6, 10);
        assertEquals(4, wordComposer().size());
        assertEquals(text.substring(6), wordComposer().getTypedWord());
        assertTrue(mLatinIME.mInputLogic.mConnection.hasComposingText());
        assertEquals(text.substring(6), privateField(
                mLatinIME.mInputLogic.mConnection, "mComposingText").toString());
        assertEquals(regionCalls, mTrackingConnection.mComposingRegionCalls);
    }

    private void postNormalRestartFromWorker() {
        final Throwable[] failure = new Throwable[1];
        final Thread worker = new Thread(() -> {
            try {
                mLatinIME.mHandler.postResumeSuggestions(false);
            } catch (final Throwable t) {
                failure[0] = t;
            }
        }, "suggestions-restart-cancel");
        worker.start();
        try {
            worker.join(5000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertFalse("normal restart worker did not finish", worker.isAlive());
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    public void testWorkerPostDuringSuccessfulReadAllowsRecoveryAndNormalExecution()
            throws Exception {
        final PendingSuggestionsRestart request = preparePendingDelete();
        final int reads = mTrackingConnection.mStyledReads;
        mTrackingConnection.mDuringStyledRead = this::postNormalRestartFromWorker;
        tryPending(request);
        assertNull(mTrackingConnection.mDuringStyledRead);
        assertNull(pendingRequest());
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        assertRecoveryComposition("alpha echo");

        acknowledgeAt(10);
        tryPending(request);
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        runMessages();
        assertNull(pendingRequest());
        assertEquals(reads + 2, mTrackingConnection.mStyledReads);
        assertRecoveryComposition("alpha echo", 2);
        tryPending(request);
        runDelayedMessages();
        assertEquals(reads + 2, mTrackingConnection.mStyledReads);
        assertRecoveryComposition("alpha echo", 2);
    }

    public void testWorkerPostDuringComposingRegionPreservesQueuedNormalRestart() throws Exception {
        final PendingSuggestionsRestart request = preparePendingDelete();
        final int reads = mTrackingConnection.mStyledReads;
        mTrackingConnection.mDuringSetComposingRegion = () -> {
            try {
                assertSame(request, pendingRequest());
                assertEquals("echo", wordComposer().getTypedWord());
                assertTrue(mLatinIME.mInputLogic.mConnection.hasComposingText());
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
                postNormalRestartFromWorker();
                assertSame(request, pendingRequest());
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        };
        tryPending(request);
        assertNull(mTrackingConnection.mDuringSetComposingRegion);
        assertNull(pendingRequest());
        assertRecoveryComposition("alpha echo");
        assertEquals(reads + 1, mTrackingConnection.mStyledReads);
        acknowledgeAt(10);
        tryPending(request);
        runMessages();
        assertNull(pendingRequest());
        assertEquals(reads + 2, mTrackingConnection.mStyledReads);
        assertRecoveryComposition("alpha echo", 2);
        runDelayedMessages();
        assertEquals(reads + 2, mTrackingConnection.mStyledReads);
        assertRecoveryComposition("alpha echo", 2);
    }

    public void testCanceledAndNullRequestsDoNothing() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(false);
        mLatinIME.mInputLogic.cancelPendingSuggestionsRestart();
        mLatinIME.mInputLogic.cancelPendingSuggestionsRestart();
        final int reads = mTrackingConnection.mStyledReads;
        tryPending(null);
        tryPending(request);
        runDelayedMessages();
        assertEquals(reads, mTrackingConnection.mStyledReads);
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    public void testRecoveryRejectsChangedOrInvalidExpectedSelection() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(false);
        assertTrue(mLatinIME.mInputLogic.mConnection.setSelection(9, 10));
        tryPending(request);
        assertNull(pendingRequest());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        armRestartForTest(9);
        final Field start = RichInputConnection.class.getDeclaredField("mExpectedSelStart");
        start.setAccessible(true);
        start.setInt(mLatinIME.mInputLogic.mConnection, -1);
        tryPending(pendingRequest());
        assertNull(pendingRequest());
    }

    public void testBelatedAckCancelsWhenExpectedCursorChanged() throws Exception {
        prepareTransformedDelete(false);
        mLatinIME.mInputLogic.mConnection.commitText(" ", 1);
        acknowledgeAt(11);
        assertNull(pendingRequest());
        runDelayedMessages();
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    public void testRecoveryRejectsActiveWordComposer() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(true);
        final WordComposer composer = (WordComposer)privateField(
                mLatinIME.mInputLogic, "mWordComposer");
        composer.setComposingWord(new int[] {'a'}, new int[] {0, 0});
        assertFalse(mLatinIME.mInputLogic.mConnection.hasComposingText());
        tryPending(request);
        assertNull(pendingRequest());
        assertEquals(1, composer.size());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    public void testRecoveryRejectsActiveRicComposition() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(true);
        mLatinIME.mInputLogic.mConnection.setComposingRegion(6, 10);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
        tryPending(request);
        assertNull(pendingRequest());
        assertEquals(1, mTrackingConnection.mComposingRegionCalls);
        assertComposition("ALPHA ECHO", 10, 6, 10);
    }

    private void setBatchGate(final boolean ui) throws Exception {
        final Object owner = ui ? mLatinIME : privateField(mLatinIME.mInputLogic,
                "mInputLogicHandler");
        final Field field = (ui ? LatinIME.class : owner.getClass()).getDeclaredField(
                ui ? "mBatchInputUiActive" : "mInBatchInput");
        field.setAccessible(true);
        field.setBoolean(owner, true);
    }

    private void checkBatchGate(final boolean ui, final boolean timer) throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(true);
        setBatchGate(ui);
        if (timer) {
            runDelayedMessages();
        } else {
            tryPending(request);
        }
        assertNull(pendingRequest());
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
    }

    public void testRecoveryAckRejectsGestureWorker() throws Exception {
        checkBatchGate(false, false);
    }

    public void testRecoveryTimerRejectsGestureWorker() throws Exception {
        checkBatchGate(false, true);
    }

    public void testRecoveryAckRejectsGestureUi() throws Exception {
        checkBatchGate(true, false);
    }

    public void testRecoveryTimerRejectsGestureUi() throws Exception {
        checkBatchGate(true, true);
    }

    public void testComposeDoesNotArmWithActiveRicComposition() throws Exception {
        setCommittedText("alpha echo ");
        mLatinIME.mInputLogic.mConnection.setComposingRegion(6, 11);
        type(Constants.CODE_DELETE);
        assertNull(pendingRequest());
        assertTrue(mLatinIME.mInputLogic.mConnection.hasComposingText());
        final int calls = mTrackingConnection.mComposingRegionCalls;
        runDelayedMessages();
        assertEquals(calls, mTrackingConnection.mComposingRegionCalls);
    }

    public void testComposeDoesNotArmWithActiveGestureUi() throws Exception {
        setCommittedText("alpha echo ");
        setBatchGate(true);
        type(Constants.CODE_DELETE);
        assertNull(pendingRequest());
        runDelayedMessages();
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    public void testFinishInputInvalidatesAckAndTimer() throws Exception {
        final PendingSuggestionsRestart request = prepareTransformedDelete(true);
        mLatinIME.mInputLogic.finishInput();
        assertNull(pendingRequest());
        tryPending(request);
        acknowledgeAt(10);
        runDelayedMessages();
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
    }

    private void finishInputAndRunPendingMessages() {
        mLatinIME.onFinishInputViewInternal(true /* finishingInput */);
        runDelayedMessages();
        assertEquals(0, mTrackingConnection.mComposingRegionCalls);
        assertEquals(0, mLatinIME.mInputLogic.getComposingLength());
    }

    public void testFinishInputCancelsDeleteResume() {
        setCommittedText("alpha echo ");
        type(Constants.CODE_DELETE);
        finishInputAndRunPendingMessages();
    }

    public void testFinishInputCancelsCursorResume() {
        setCommittedText("alpha echo");
        mLatinIME.mHandler.postResumeSuggestions(true /* shouldDelay */);
        finishInputAndRunPendingMessages();
    }

    public void testFinishInputCancelsStartInputResume() {
        setCommittedText("alpha echo");
        mLatinIME.mHandler.postResumeSuggestionsForStartInput(true /* shouldDelay */);
        finishInputAndRunPendingMessages();
    }
}
