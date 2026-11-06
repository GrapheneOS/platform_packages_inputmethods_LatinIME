/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Parcel;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.common.StringUtils;
import com.android.inputmethod.latin.settings.SpacingAndPunctuations;
import com.android.inputmethod.latin.utils.NgramContextUtils;
import com.android.inputmethod.latin.utils.RunInLocale;
import com.android.inputmethod.latin.utils.ScriptUtils;
import com.android.inputmethod.latin.utils.TextRange;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RichInputConnectionAndTextRangeTests {

    // The following is meant to be a reasonable default for
    // the "word_separators" resource.
    private SpacingAndPunctuations mSpacingAndPunctuations;

    @Before
    public void setUp() throws Exception {
        final RunInLocale<SpacingAndPunctuations> job = new RunInLocale<SpacingAndPunctuations>() {
            @Override
            protected SpacingAndPunctuations job(final Resources res) {
                return new SpacingAndPunctuations(res);
            }
        };
        final Resources res = InstrumentationRegistry.getTargetContext().getResources();
        mSpacingAndPunctuations = job.runInLocale(res, Locale.ENGLISH);
    }

    private class MockConnection extends InputConnectionWrapper {
        final CharSequence mTextBefore;
        final CharSequence mTextAfter;
        final ExtractedText mExtractedText;

        public MockConnection(final CharSequence text, final int cursorPosition) {
            super(null, false);
            // Interaction of spans with Parcels is completely non-trivial, but in the actual case
            // the CharSequences do go through Parcels because they go through IPC. There
            // are some significant differences between the behavior of Spanned objects that
            // have and that have not gone through parceling, so it's much easier to simulate
            // the environment with Parcels than try to emulate things by hand.
            final Parcel p = Parcel.obtain();
            TextUtils.writeToParcel(text.subSequence(0, cursorPosition), p, 0 /* flags */);
            TextUtils.writeToParcel(text.subSequence(cursorPosition, text.length()), p,
                    0 /* flags */);
            final byte[] marshalled = p.marshall();
            p.unmarshall(marshalled, 0, marshalled.length);
            p.setDataPosition(0);
            mTextBefore = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            mTextAfter = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(p);
            mExtractedText = null;
            p.recycle();
        }

        public MockConnection(String textBefore, String textAfter, ExtractedText extractedText) {
            super(null, false);
            mTextBefore = textBefore;
            mTextAfter = textAfter;
            mExtractedText = extractedText;
        }

        public int cursorPos() {
            return mTextBefore.length();
        }

        /* (non-Javadoc)
         * @see android.view.inputmethod.InputConnectionWrapper#getTextBeforeCursor(int, int)
         */
        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            return mTextBefore;
        }

        /* (non-Javadoc)
         * @see android.view.inputmethod.InputConnectionWrapper#getTextAfterCursor(int, int)
         */
        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            return mTextAfter;
        }

        /* (non-Javadoc)
         * @see android.view.inputmethod.InputConnectionWrapper#getExtractedText(
         *         ExtractedTextRequest, int)
         */
        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            return mExtractedText;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }

        @Override
        public boolean finishComposingText() {
            return true;
        }
    }

    private class LaggingConnection extends MockConnection {
        final SpannableStringBuilder mText;
        int mCursorPosition;
        int mPendingDeleteLength;
        int mMaxCharsToReturn = Integer.MAX_VALUE;
        int mBeforeCursorReads;
        boolean mReturnNullBeforeCursor;
        int mNullBeforeCursorReads;
        int mComposingStart = -1;
        int mComposingEnd = -1;

        LaggingConnection(final CharSequence text, final int cursorPosition) {
            super(text, cursorPosition);
            mText = new SpannableStringBuilder(text);
            mCursorPosition = cursorPosition;
        }

        @Override
        public int cursorPos() {
            return mCursorPosition;
        }

        @Override
        public CharSequence getTextBeforeCursor(final int n, final int flags) {
            ++mBeforeCursorReads;
            if (mReturnNullBeforeCursor) {
                ++mNullBeforeCursorReads;
                return null;
            }
            final int length = Math.min(n, mMaxCharsToReturn);
            return parcelText(mText.subSequence(
                    Math.max(0, mCursorPosition - length), mCursorPosition));
        }

        @Override
        public CharSequence getTextAfterCursor(final int n, final int flags) {
            return parcelText(mText.subSequence(
                    mCursorPosition, Math.min(mText.length(), mCursorPosition + n)));
        }

        private CharSequence parcelText(final CharSequence text) {
            // Each IPC response contains its own instances of the parcelled suggestion spans.
            final Parcel parcel = Parcel.obtain();
            TextUtils.writeToParcel(text, parcel, 0);
            parcel.setDataPosition(0);
            final CharSequence result = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
            parcel.recycle();
            return result;
        }

        @Override
        public boolean deleteSurroundingText(final int beforeLength, final int afterLength) {
            assertEquals(0, afterLength);
            mPendingDeleteLength += beforeLength;
            return true;
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            assertEquals(KeyEvent.KEYCODE_DEL, event.getKeyCode());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                ++mPendingDeleteLength;
            }
            return true;
        }

        void applyPendingDeletes() {
            mText.delete(mCursorPosition - mPendingDeleteLength, mCursorPosition);
            mCursorPosition -= mPendingDeleteLength;
            mPendingDeleteLength = 0;
        }

        @Override
        public boolean setComposingRegion(final int start, final int end) {
            mComposingStart = start;
            mComposingEnd = end;
            return true;
        }

        @Override
        public boolean setComposingText(final CharSequence text, final int newCursorPosition) {
            return true;
        }
    }

    private class RecordingConnection extends LaggingConnection {
        int mCommitTextCalls;
        int mSetComposingTextCalls;
        int mCommitCompletionCalls;
        int mSentKeyEvents;
        String mLastText;

        RecordingConnection(final CharSequence text, final int cursorPosition) {
            super(text, cursorPosition);
        }

        @Override
        public boolean commitText(final CharSequence text, final int newCursorPosition) {
            ++mCommitTextCalls;
            mLastText = text.toString();
            return true;
        }

        @Override
        public boolean setComposingText(final CharSequence text, final int newCursorPosition) {
            ++mSetComposingTextCalls;
            mLastText = text.toString();
            return super.setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean commitCompletion(final CompletionInfo completion) {
            ++mCommitCompletionCalls;
            mLastText = completion.getText().toString();
            return true;
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            ++mSentKeyEvents;
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                return super.sendKeyEvent(event);
            }
            return true;
        }
    }

    static class MockInputMethodService extends InputMethodService {
        private MockConnection mMockConnection;
        public void setInputConnection(final MockConnection mockConnection) {
            mMockConnection = mockConnection;
        }
        public int cursorPos() {
            return mMockConnection.cursorPos();
        }
        @Override
        public InputConnection getCurrentInputConnection() {
            return mMockConnection;
        }
    }

    /************************** Tests ************************/

    private RichInputConnection createCachedConnection(final LaggingConnection connection) {
        final MockInputMethodService ims = new MockInputMethodService();
        ims.setInputConnection(connection);
        final RichInputConnection ic = new RichInputConnection(ims);
        assertTrue(ic.resetCachesUponCursorMoveAndReturnSuccess(
                connection.cursorPos(), connection.cursorPos(), false /* shouldFinishComposition */));
        return ic;
    }

    private void assertExpectedSelection(final RichInputConnection ic,
            final int start, final int end) {
        assertEquals("expected selection start", start, ic.getExpectedSelectionStart());
        assertEquals("expected selection end", end, ic.getExpectedSelectionEnd());
    }

    private RichInputConnection createConnectionWithFailedReload(
            final LaggingConnection connection) {
        final RichInputConnection ic = createCachedConnection(connection);
        connection.mReturnNullBeforeCursor = true;
        assertFalse(ic.resetCachesUponCursorMoveAndReturnSuccess(
                connection.cursorPos(), connection.cursorPos(),
                false /* shouldFinishComposition */));
        assertEquals(2, connection.mBeforeCursorReads);
        assertEquals(1, connection.mNullBeforeCursorReads);
        assertExpectedSelection(ic, -1, -1);
        connection.mReturnNullBeforeCursor = false;
        return ic;
    }

    @Test
    public void testCommitTextPreservesUnknownCursor() {
        final RecordingConnection connection = new RecordingConnection("alpha echo", 10);
        final RichInputConnection ic = createConnectionWithFailedReload(connection);
        ic.commitText(" s", 1);
        assertEquals(1, connection.mCommitTextCalls);
        assertEquals(" s", connection.mLastText);
        assertEquals('s', ic.getCodePointBeforeCursor());
        assertExpectedSelection(ic, -1, -1);
    }

    @Test
    public void testSetComposingTextPreservesUnknownCursor() {
        final RecordingConnection connection = new RecordingConnection("alpha echo", 10);
        final RichInputConnection ic = createConnectionWithFailedReload(connection);
        ic.setComposingText("st", 1);
        assertEquals(1, connection.mSetComposingTextCalls);
        assertEquals("st", connection.mLastText);
        assertExpectedSelection(ic, -1, -1);
        ic.setComposingText("s", 1);
        assertEquals(2, connection.mSetComposingTextCalls);
        assertEquals("s", connection.mLastText);
        assertExpectedSelection(ic, -1, -1);
    }

    @Test
    public void testCommitCompletionPreservesUnknownCursor() {
        final RecordingConnection connection = new RecordingConnection("alpha echo", 10);
        final RichInputConnection ic = createConnectionWithFailedReload(connection);
        ic.commitCompletion(new CompletionInfo(1, 0, "test"));
        assertEquals(1, connection.mCommitCompletionCalls);
        assertEquals("test", connection.mLastText);
        assertEquals('t', ic.getCodePointBeforeCursor());
        assertExpectedSelection(ic, -1, -1);
    }

    @Test
    public void testDeleteTextBeforeCursorPreservesUnknownCursor() {
        for (final int length : new int[] { 1, 20 }) {
            final LaggingConnection connection = new LaggingConnection("alpha echo", 10);
            final RichInputConnection ic = createConnectionWithFailedReload(connection);
            ic.deleteTextBeforeCursor(length);
            assertEquals(length, connection.mPendingDeleteLength);
            assertExpectedSelection(ic, -1, -1);
        }
    }

    private void assertKeyPreservesUnknownCursor(final KeyEvent event) {
        final RecordingConnection connection = new RecordingConnection("alpha echo", 10);
        final RichInputConnection ic = createConnectionWithFailedReload(connection);
        ic.sendKeyEvent(event);
        assertEquals(1, connection.mSentKeyEvents);
        assertExpectedSelection(ic, -1, -1);
    }

    private KeyEvent charactersKeyDown() throws Exception {
        // The public character constructor forces ACTION_MULTIPLE. Use the framework factory
        // to exercise RIC's legacy ACTION_DOWN branch without losing the character payload.
        return (KeyEvent)KeyEvent.class.getMethod("obtain", long.class, long.class,
                int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, String.class).invoke(null,
                        0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0, 0, 0, 0, 0,
                        "xy");
    }

    @Test
    public void testEnterKeyPreservesUnknownCursor() {
        assertKeyPreservesUnknownCursor(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    }

    @Test
    public void testCharactersKeyPreservesUnknownCursor() throws Exception {
        final KeyEvent event = charactersKeyDown();
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, event.getKeyCode());
        assertEquals(KeyEvent.ACTION_DOWN, event.getAction());
        assertEquals("xy", event.getCharacters());
        assertKeyPreservesUnknownCursor(event);
    }

    @Test
    public void testDefaultKeyPreservesUnknownCursor() {
        assertKeyPreservesUnknownCursor(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
    }

    @Test
    public void testDeleteAndKeyUpPreserveUnknownCursor() {
        assertKeyPreservesUnknownCursor(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        assertKeyPreservesUnknownCursor(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    @Test
    public void testTextMutationsAdvanceKnownCursor() {
        final RecordingConnection connection = new RecordingConnection("alpha echo", 10);
        final RichInputConnection ic = createCachedConnection(connection);
        ic.setComposingText("ab", 1);
        assertExpectedSelection(ic, 12, 12);
        ic.setComposingText("a", 1);
        assertExpectedSelection(ic, 11, 11);
        ic.commitText("cat", 1);
        assertExpectedSelection(ic, 13, 13);
        ic.setComposingText("xy", 1);
        ic.commitCompletion(new CompletionInfo(1, 0, "dog"));
        assertExpectedSelection(ic, 16, 16);
        assertEquals("alpha echocatdog", ic.getTextBeforeCursor(16, 0).toString());
        ic.deleteTextBeforeCursor(3);
        assertExpectedSelection(ic, 13, 13);
        assertEquals("alpha echocat", ic.getTextBeforeCursor(13, 0).toString());
        ic.deleteTextBeforeCursor(20);
        assertExpectedSelection(ic, 0, 0);
        assertEquals("", ic.getTextBeforeCursor(1, 0).toString());
        assertEquals(1, connection.mCommitTextCalls);
        assertEquals(3, connection.mSetComposingTextCalls);
        assertEquals(1, connection.mCommitCompletionCalls);
        assertEquals(23, connection.mPendingDeleteLength);
    }

    @Test
    public void testDeleteBeforeKnownSelectionPreservesSelectionLength() {
        final LaggingConnection connection = new LaggingConnection("alpha echo", 10);
        final RichInputConnection ic = createCachedConnection(connection);
        assertTrue(ic.resetCachesUponCursorMoveAndReturnSuccess(10, 12, false));
        ic.deleteTextBeforeCursor(2);
        assertExpectedSelection(ic, 8, 10);
        ic.deleteTextBeforeCursor(20);
        assertExpectedSelection(ic, 0, 2);
    }

    @Test
    public void testKeyEventsAdvanceKnownCursor() throws Exception {
        final RecordingConnection connection = new RecordingConnection("alpha echo", 10);
        final RichInputConnection ic = createCachedConnection(connection);
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        assertExpectedSelection(ic, 11, 11);
        ic.sendKeyEvent(charactersKeyDown());
        assertExpectedSelection(ic, 13, 13);
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
        assertExpectedSelection(ic, 14, 14);
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
        assertExpectedSelection(ic, 14, 14);
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        assertExpectedSelection(ic, 13, 13);
        assertEquals("alpha echo\nxy", ic.getTextBeforeCursor(13, 0).toString());
        assertEquals(5, connection.mSentKeyEvents);
        assertEquals(1, connection.mPendingDeleteLength);
    }

    @Test
    public void testUnknownCursorNeverTreatsSelectionUpdateAsBelated() {
        final LaggingConnection connection = new LaggingConnection("alpha echo", 10);
        final RichInputConnection ic = createConnectionWithFailedReload(connection);
        assertFalse(ic.isBelatedExpectedUpdate(10, 9, 10, 9));
        assertFalse(ic.isBelatedExpectedUpdate(10, 10, 10, 10));
        assertFalse(ic.isBelatedExpectedUpdate(10, -1, 10, -1));
    }

    @Test
    public void testKnownCursorDetectsBelatedSelectionUpdates() {
        final LaggingConnection connection = new LaggingConnection("alpha echo", 10);
        final RichInputConnection ic = createCachedConnection(connection);
        assertTrue(ic.isBelatedExpectedUpdate(8, 10, 8, 10));
        assertTrue(ic.isBelatedExpectedUpdate(10, 10, 10, 10));
        assertTrue(ic.isBelatedExpectedUpdate(6, 8, 6, 8));
        assertTrue(ic.isBelatedExpectedUpdate(12, 11, 12, 11));
        assertFalse(ic.isBelatedExpectedUpdate(10, 9, 10, 9));
        assertFalse(ic.isBelatedExpectedUpdate(6, 11, 6, 11));
        assertFalse(ic.isBelatedExpectedUpdate(6, 8, 6, 9));
    }

    @Test
    public void testRevertSwapPunctuationRejectsOneCharacterRead() {
        final RecordingConnection connection = new RecordingConnection(". ", 2);
        connection.mMaxCharsToReturn = 1;
        final RichInputConnection ic = createCachedConnection(connection);
        assertFalse(ic.revertSwapPunctuation());
        assertEquals(2, connection.mBeforeCursorReads);
        assertEquals(0, connection.mCommitTextCalls);
        assertEquals(0, connection.mPendingDeleteLength);
        assertEquals(". ", connection.mText.toString());
        assertExpectedSelection(ic, 2, 2);
    }

    private void assertWordRangeStatus(final RichInputConnection ic,
            final RichInputConnection.WordRangeStatus status) {
        final RichInputConnection.WordRangeResult result = ic.getWordRangeAtCursorResult(
                mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN);
        assertEquals(status, result.status);
        assertEquals(status == RichInputConnection.WordRangeStatus.FOUND, result.range != null);
    }

    @Test
    public void testWordRangeResultClassifiesEveryCacheGuard() {
        final LaggingConnection tooLong = new LaggingConnection("   ", 3);
        final RichInputConnection lengthCache = createCachedConnection(tooLong);
        lengthCache.deleteTextBeforeCursor(1);
        assertWordRangeStatus(lengthCache, RichInputConnection.WordRangeStatus.CACHE_REJECTED);

        final LaggingConnection partial = new LaggingConnection("abcdefghij", 10);
        partial.mMaxCharsToReturn = 3;
        final RichInputConnection partialCache = createCachedConnection(partial);
        partial.mMaxCharsToReturn = Integer.MAX_VALUE;
        assertWordRangeStatus(partialCache, RichInputConnection.WordRangeStatus.CACHE_REJECTED);

        final LaggingConnection changed = new LaggingConnection("alpha echo", 10);
        final RichInputConnection changedCache = createCachedConnection(changed);
        changed.mText.replace(0, 5, "bravo");
        assertWordRangeStatus(changedCache, RichInputConnection.WordRangeStatus.CACHE_REJECTED);
        changed.mText.replace(0, 5, "alpha");
        assertWordRangeStatus(changedCache, RichInputConnection.WordRangeStatus.FOUND);
    }

    @Test
    public void testWordRangeResultClassifiesUnavailableReads() {
        final MockInputMethodService ims = new MockInputMethodService();
        final RichInputConnection ic = new RichInputConnection(ims);
        assertWordRangeStatus(ic, RichInputConnection.WordRangeStatus.UNAVAILABLE);
        ims.setInputConnection(new MockConnection(null, "after", null));
        assertWordRangeStatus(ic, RichInputConnection.WordRangeStatus.UNAVAILABLE);
        ims.setInputConnection(new MockConnection("before", null, null));
        assertWordRangeStatus(ic, RichInputConnection.WordRangeStatus.UNAVAILABLE);
        assertNull(ic.getWordRangeAtCursor(mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN));
    }

    @Test
    public void testWordRangeRejectsPendingDeleteThenAcceptsAppliedDelete() {
        final String text = "alpha bravo charlie delta echo";
        final LaggingConnection connection = new LaggingConnection(text, text.length());
        final RichInputConnection ic = createCachedConnection(connection);
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
        assertNull(ic.getWordRangeAtCursor(mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN));
        assertEquals(text.length() - 1, ic.getExpectedSelectionStart());

        connection.applyPendingDeletes();
        final TextRange range = ic.getWordRangeAtCursor(
                mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN);
        assertNotNull(range);
        assertEquals("ech", range.mWord.toString());
        final int cursor = ic.getExpectedSelectionStart();
        ic.setComposingRegion(cursor - range.getNumberOfCharsInWordBeforeCursor(), cursor);
        assertEquals(text.indexOf("echo"), connection.mComposingStart);
        assertEquals(cursor, connection.mComposingEnd);
        ic.setComposingText("ec", 1);
        assertEquals("alpha bravo charlie delta ec",
                ic.getTextBeforeCursor(text.length(), 0).toString());
    }

    @Test
    public void testWordRangeRejectsTextLongerThanExpectedCursorWithMatchingSuffix() {
        final LaggingConnection connection = new LaggingConnection("   ", 3);
        final RichInputConnection ic = createCachedConnection(connection);
        ic.deleteTextBeforeCursor(1);
        assertNull(ic.getWordRangeAtCursor(mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN));
    }

    @Test
    public void testWordRangeRequiresWholeBeforeCursorWordInCache() {
        final String text = "abcdefghij";
        final LaggingConnection connection = new LaggingConnection(text, text.length());
        connection.mMaxCharsToReturn = 3;
        final RichInputConnection ic = createCachedConnection(connection);
        connection.mMaxCharsToReturn = Integer.MAX_VALUE;
        assertNull(ic.getWordRangeAtCursor(mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN));
    }

    @Test
    public void testWordRangeChecksCachedPrefixAsWellAsCandidateWord() {
        final LaggingConnection connection = new LaggingConnection("alpha echo", 10);
        final RichInputConnection ic = createCachedConnection(connection);
        connection.mText.replace(0, 5, "bravo");
        assertNull(ic.getWordRangeAtCursor(mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN));
    }

    @Test
    public void testWordRangeAfterSupplementaryCharacterDelete() {
        final String text = "alpha echo\uD83D\uDE07";
        final LaggingConnection connection = new LaggingConnection(text, text.length());
        final RichInputConnection ic = createCachedConnection(connection);
        ic.deleteTextBeforeCursor(2);
        assertNull(ic.getWordRangeAtCursor(mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN));
        connection.applyPendingDeletes();
        final TextRange range = ic.getWordRangeAtCursor(
                mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN);
        assertNotNull(range);
        assertEquals("echo", range.mWord.toString());
        assertEquals(4, range.getNumberOfCharsInWordBeforeCursor());
    }

    @Test
    public void testKnownCachePreservesRecorrectionSpansAndRepeatedComposingRegion() {
        final SpannableString text = new SpannableString("prefix echo suffix");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, new String[] { "each" }, 0),
                7, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        final LaggingConnection connection = new LaggingConnection(text, 9);
        final RichInputConnection ic = createCachedConnection(connection);
        final TextRange range = ic.getWordRangeAtCursor(
                mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN);
        assertNotNull(range);
        assertEquals("echo", range.mWord.toString());
        assertEquals(2, range.getNumberOfCharsInWordBeforeCursor());
        assertEquals(2, range.getNumberOfCharsInWordAfterCursor());
        assertEquals(1, range.getSuggestionSpansAtWord().length);
        assertEquals("each", range.getSuggestionSpansAtWord()[0].getSuggestions()[0]);

        final int reads = connection.mBeforeCursorReads;
        ic.setComposingRegion(7, 11);
        ic.setComposingRegion(7, 11);
        assertEquals(reads, connection.mBeforeCursorReads);
        assertEquals("prefix ec", ic.getTextBeforeCursor(9, 0).toString());
        ic.setComposingText("echoes", 1);
        assertEquals(13, ic.getExpectedSelectionStart());
        assertEquals("prefix echoes", ic.getTextBeforeCursor(13, 0).toString());
    }

    @Test
    public void testComposingRegionKeepsLongOptimisticCacheAfterAmbiguousRead() {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; ++i) {
            text.append("prefix ");
        }
        final StringBuilder word = new StringBuilder();
        for (int i = 0; i < 41; ++i) {
            word.append('a');
        }
        text.append(word);
        final LaggingConnection connection = new LaggingConnection(text, text.length());
        final RichInputConnection ic = createCachedConnection(connection);
        ic.deleteTextBeforeCursor(1);
        // The stale 40-character response is identical to the optimistic suffix. A second,
        // larger read would include the pending deletion and shift the cached word boundary.
        final TextRange range = ic.getWordRangeAtCursor(
                mSpacingAndPunctuations, ScriptUtils.SCRIPT_LATIN);
        assertNotNull(range);
        assertEquals(40, range.getNumberOfCharsInWordBeforeCursor());
        final int reads = connection.mBeforeCursorReads;
        final int cursor = ic.getExpectedSelectionStart();
        ic.setComposingRegion(cursor - 40, cursor);
        assertEquals(reads, connection.mBeforeCursorReads);
        final int cachedLength = Constants.EDITOR_CONTENTS_CACHE_SIZE - 1;
        assertEquals(text.substring(text.length() - Constants.EDITOR_CONTENTS_CACHE_SIZE,
                        text.length() - 1),
                ic.getTextBeforeCursor(cachedLength, 0).toString());
        assertEquals(Constants.CODE_SPACE, ic.getCodePointBeforeCursor());

        ic.setComposingRegion(cursor - 40, cursor);
        word.setLength(39);
        ic.setComposingText(word, 1);
        assertEquals(cursor - 1, ic.getExpectedSelectionStart());
        assertEquals(reads, connection.mBeforeCursorReads);
        assertEquals(text.substring(text.length() - Constants.EDITOR_CONTENTS_CACHE_SIZE,
                        text.length() - 2),
                ic.getTextBeforeCursor(cachedLength - 1, 0).toString());
    }

    @Test
    public void testComposingRegionReadsEditorWhenCacheDoesNotCoverRegion() {
        final String text = "abcdefghij";
        final LaggingConnection connection = new LaggingConnection(text, text.length());
        connection.mMaxCharsToReturn = 3;
        final RichInputConnection ic = createCachedConnection(connection);
        connection.mMaxCharsToReturn = Integer.MAX_VALUE;
        final int reads = connection.mBeforeCursorReads;
        ic.setComposingRegion(0, text.length());
        assertEquals(reads + 1, connection.mBeforeCursorReads);
        assertEquals(text, ic.getTextBeforeCursor(text.length(), 0).toString());
    }

    /**
     * Test for getting previous word (for bigram suggestions)
     */
    @Test
    public void testGetPreviousWord() {
        // If one of the following cases breaks, the bigram suggestions won't work.
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def", mSpacingAndPunctuations, 2).getNthPrevWord(1), "abc");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc", mSpacingAndPunctuations, 2), NgramContext.BEGINNING_OF_SENTENCE);
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc. def", mSpacingAndPunctuations, 2), NgramContext.BEGINNING_OF_SENTENCE);

        assertFalse(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def", mSpacingAndPunctuations, 2).isBeginningOfSentenceContext());
        assertTrue(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc", mSpacingAndPunctuations, 2).isBeginningOfSentenceContext());

        // For n-gram
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def", mSpacingAndPunctuations, 1).getNthPrevWord(1), "def");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def", mSpacingAndPunctuations, 1).getNthPrevWord(2), "abc");
        assertTrue(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def", mSpacingAndPunctuations, 2).isNthPrevWordBeginningOfSentence(2));

        // The following tests reflect the current behavior of the function
        // RichInputConnection#getNthPreviousWord.
        // TODO: However at this time, the code does never go
        // into such a path, so it should be safe to change the behavior of
        // this function if needed - especially since it does not seem very
        // logical. These tests are just there to catch any unintentional
        // changes in the behavior of the RichInputConnection#getPreviousWord method.
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def ", mSpacingAndPunctuations, 2).getNthPrevWord(1), "abc");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def.", mSpacingAndPunctuations, 2).getNthPrevWord(1), "abc");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def .", mSpacingAndPunctuations, 2).getNthPrevWord(1), "def");
        assertTrue(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc ", mSpacingAndPunctuations, 2).isBeginningOfSentenceContext());

        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def", mSpacingAndPunctuations, 1).getNthPrevWord(1), "def");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def ", mSpacingAndPunctuations, 1).getNthPrevWord(1), "def");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc 'def", mSpacingAndPunctuations, 1).getNthPrevWord(1), "'def");
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def.", mSpacingAndPunctuations, 1), NgramContext.BEGINNING_OF_SENTENCE);
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc def .", mSpacingAndPunctuations, 1), NgramContext.BEGINNING_OF_SENTENCE);
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc, def", mSpacingAndPunctuations, 2), NgramContext.EMPTY_PREV_WORDS_INFO);
        // question mark is treated as the end of the sentence. Hence, beginning of the
        // sentence is expected.
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc? def", mSpacingAndPunctuations, 2), NgramContext.BEGINNING_OF_SENTENCE);
        // Exclamation mark is treated as the end of the sentence. Hence, beginning of the
        // sentence is expected.
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc! def", mSpacingAndPunctuations, 2), NgramContext.BEGINNING_OF_SENTENCE);
        assertEquals(NgramContextUtils.getNgramContextFromNthPreviousWord(
                "abc 'def", mSpacingAndPunctuations, 2), NgramContext.EMPTY_PREV_WORDS_INFO);
    }

    @Test
    public void testGetWordRangeAtCursor() {
        /**
         * Test logic in getting the word range at the cursor.
         */
        final SpacingAndPunctuations SPACE = new SpacingAndPunctuations(
                mSpacingAndPunctuations, new int[] { Constants.CODE_SPACE });
        final SpacingAndPunctuations TAB = new SpacingAndPunctuations(
                mSpacingAndPunctuations, new int[] { Constants.CODE_TAB });
        // A character that needs surrogate pair to represent its code point (U+2008A).
        final String SUPPLEMENTARY_CHAR_STRING = "\uD840\uDC8A";
        final SpacingAndPunctuations SUPPLEMENTARY_CHAR = new SpacingAndPunctuations(
                mSpacingAndPunctuations, StringUtils.toSortedCodePointArray(
                        SUPPLEMENTARY_CHAR_STRING));
        final String HIRAGANA_WORD = "\u3042\u3044\u3046\u3048\u304A"; // あいうえお
        final String GREEK_WORD = "\u03BA\u03B1\u03B9"; // και

        ExtractedText et = new ExtractedText();
        final MockInputMethodService mockInputMethodService = new MockInputMethodService();
        final RichInputConnection ic = new RichInputConnection(mockInputMethodService);
        mockInputMethodService.setInputConnection(new MockConnection("word wo", "rd", et));
        et.startOffset = 0;
        et.selectionStart = 7;
        TextRange r;

        ic.beginBatchEdit();
        // basic case
        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        assertTrue(TextUtils.equals("word", r.mWord));

        // tab character instead of space
        mockInputMethodService.setInputConnection(new MockConnection("one\tword\two", "rd", et));
        ic.beginBatchEdit();
        r = ic.getWordRangeAtCursor(TAB, ScriptUtils.SCRIPT_LATIN);
        ic.endBatchEdit();
        assertTrue(TextUtils.equals("word", r.mWord));

        // splitting on supplementary character
        mockInputMethodService.setInputConnection(
                new MockConnection("one word" + SUPPLEMENTARY_CHAR_STRING + "wo", "rd", et));
        ic.beginBatchEdit();
        r = ic.getWordRangeAtCursor(SUPPLEMENTARY_CHAR, ScriptUtils.SCRIPT_LATIN);
        ic.endBatchEdit();
        assertTrue(TextUtils.equals("word", r.mWord));

        // split on chars outside the specified script
        mockInputMethodService.setInputConnection(
                new MockConnection(HIRAGANA_WORD + "wo", "rd" + GREEK_WORD, et));
        ic.beginBatchEdit();
        r = ic.getWordRangeAtCursor(SUPPLEMENTARY_CHAR, ScriptUtils.SCRIPT_LATIN);
        ic.endBatchEdit();
        assertTrue(TextUtils.equals("word", r.mWord));

        // likewise for greek
        mockInputMethodService.setInputConnection(
                new MockConnection("text" + GREEK_WORD, "text", et));
        ic.beginBatchEdit();
        r = ic.getWordRangeAtCursor(SUPPLEMENTARY_CHAR, ScriptUtils.SCRIPT_GREEK);
        ic.endBatchEdit();
        assertTrue(TextUtils.equals(GREEK_WORD, r.mWord));
    }

    /**
     * Test logic in getting the word range at the cursor.
     */
    @Test
    public void testGetSuggestionSpansAtWord() {
        helpTestGetSuggestionSpansAtWord(10);
        helpTestGetSuggestionSpansAtWord(12);
        helpTestGetSuggestionSpansAtWord(15);
        helpTestGetSuggestionSpansAtWord(16);
    }

    private void helpTestGetSuggestionSpansAtWord(final int cursorPos) {
        final SpacingAndPunctuations SPACE = new SpacingAndPunctuations(
                mSpacingAndPunctuations, new int[] { Constants.CODE_SPACE });
        final MockInputMethodService mockInputMethodService = new MockInputMethodService();
        final RichInputConnection ic = new RichInputConnection(mockInputMethodService);

        final String[] SUGGESTIONS1 = { "swing", "strong" };
        final String[] SUGGESTIONS2 = { "storing", "strung" };

        // Test the usual case.
        SpannableString text = new SpannableString("This is a string for test");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS1, 0 /* flags */),
                10 /* start */, 16 /* end */, 0 /* flags */);
        mockInputMethodService.setInputConnection(new MockConnection(text, cursorPos));
        TextRange r;
        SuggestionSpan[] suggestions;

        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        suggestions = r.getSuggestionSpansAtWord();
        assertEquals(suggestions.length, 1);
        assertEquals(suggestions[0].getSuggestions(), SUGGESTIONS1);

        // Test the case with 2 suggestion spans in the same place.
        text = new SpannableString("This is a string for test");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS1, 0 /* flags */),
                10 /* start */, 16 /* end */, 0 /* flags */);
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS2, 0 /* flags */),
                10 /* start */, 16 /* end */, 0 /* flags */);
        mockInputMethodService.setInputConnection(new MockConnection(text, cursorPos));
        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        suggestions = r.getSuggestionSpansAtWord();
        assertEquals(suggestions.length, 2);
        assertEquals(suggestions[0].getSuggestions(), SUGGESTIONS1);
        assertEquals(suggestions[1].getSuggestions(), SUGGESTIONS2);

        // Test a case with overlapping spans, 2nd extending past the start of the word
        text = new SpannableString("This is a string for test");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS1, 0 /* flags */),
                10 /* start */, 16 /* end */, 0 /* flags */);
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS2, 0 /* flags */),
                5 /* start */, 16 /* end */, 0 /* flags */);
        mockInputMethodService.setInputConnection(new MockConnection(text, cursorPos));
        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        suggestions = r.getSuggestionSpansAtWord();
        assertEquals(suggestions.length, 1);
        assertEquals(suggestions[0].getSuggestions(), SUGGESTIONS1);

        // Test a case with overlapping spans, 2nd extending past the end of the word
        text = new SpannableString("This is a string for test");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS1, 0 /* flags */),
                10 /* start */, 16 /* end */, 0 /* flags */);
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS2, 0 /* flags */),
                10 /* start */, 20 /* end */, 0 /* flags */);
        mockInputMethodService.setInputConnection(new MockConnection(text, cursorPos));
        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        suggestions = r.getSuggestionSpansAtWord();
        assertEquals(suggestions.length, 1);
        assertEquals(suggestions[0].getSuggestions(), SUGGESTIONS1);

        // Test a case with overlapping spans, 2nd extending past both ends of the word
        text = new SpannableString("This is a string for test");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS1, 0 /* flags */),
                10 /* start */, 16 /* end */, 0 /* flags */);
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS2, 0 /* flags */),
                5 /* start */, 20 /* end */, 0 /* flags */);
        mockInputMethodService.setInputConnection(new MockConnection(text, cursorPos));
        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        suggestions = r.getSuggestionSpansAtWord();
        assertEquals(suggestions.length, 1);
        assertEquals(suggestions[0].getSuggestions(), SUGGESTIONS1);

        // Test a case with overlapping spans, none right on the word
        text = new SpannableString("This is a string for test");
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS1, 0 /* flags */),
                5 /* start */, 16 /* end */, 0 /* flags */);
        text.setSpan(new SuggestionSpan(Locale.ENGLISH, SUGGESTIONS2, 0 /* flags */),
                5 /* start */, 20 /* end */, 0 /* flags */);
        mockInputMethodService.setInputConnection(new MockConnection(text, cursorPos));
        r = ic.getWordRangeAtCursor(SPACE, ScriptUtils.SCRIPT_LATIN);
        suggestions = r.getSuggestionSpansAtWord();
        assertEquals(suggestions.length, 0);
    }

    @Test
    public void testCursorTouchingWord() {
        final MockInputMethodService ims = new MockInputMethodService();
        final RichInputConnection ic = new RichInputConnection(ims);
        final SpacingAndPunctuations sap = mSpacingAndPunctuations;

        ims.setInputConnection(new MockConnection("users", 5));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true /* checkTextAfter */));

        ims.setInputConnection(new MockConnection("users'", 5));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("users'", 6));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("'users'", 6));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("'users'", 7));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("users '", 6));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("users '", 7));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("re-", 3));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("re--", 4));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("-", 1));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection("--", 2));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" -", 2));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" --", 3));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" users '", 1));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" users '", 3));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" users '", 7));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" users are", 7));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertTrue(ic.isCursorTouchingWord(sap, true));

        ims.setInputConnection(new MockConnection(" users 'are", 7));
        ic.resetCachesUponCursorMoveAndReturnSuccess(ims.cursorPos(), ims.cursorPos(), true);
        assertFalse(ic.isCursorTouchingWord(sap, true));
    }
}
