/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.inputmethod.latin.spellcheck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.service.textservice.SpellCheckerService;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SuggestionsInfo;

import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.keyboard.KeyboardId;
import com.android.inputmethod.keyboard.KeyboardLayoutSet;
import com.android.inputmethod.latin.DictionaryFacilitator;
import com.android.inputmethod.latin.DictionaryFacilitatorLruCache;
import com.android.inputmethod.latin.NgramContext;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.RichInputMethodManager;
import com.android.inputmethod.latin.RichInputMethodSubtype;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.common.ComposedData;
import com.android.inputmethod.latin.common.LocaleUtils;
import com.android.inputmethod.latin.common.StringUtils;
import com.android.inputmethod.latin.settings.SettingsValuesForSuggestion;
import com.android.inputmethod.latin.utils.AdditionalSubtypeUtils;
import com.android.inputmethod.latin.utils.ScriptUtils;
import com.android.inputmethod.latin.utils.SuggestionResults;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import javax.annotation.Nonnull;

/**
 * Service for spell checking, using LatinIME's dictionaries and mechanisms.
 */
public final class AndroidSpellCheckerService extends SpellCheckerService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = AndroidSpellCheckerService.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final String PREF_USE_CONTACTS_KEY = "pref_spellcheck_use_contacts";

    private static final int SPELLCHECKER_DUMMY_KEYBOARD_WIDTH = 480;
    private static final int SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT = 301;

    private static final String DICTIONARY_NAME_PREFIX = "spellcheck_";

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final int MAX_NUM_OF_THREADS_READ_DICTIONARY = 2;
    private final Semaphore mSemaphore = new Semaphore(MAX_NUM_OF_THREADS_READ_DICTIONARY,
            true /* fair */);
    // TODO: Make each spell checker session has its own session id.
    private final ConcurrentLinkedQueue<Integer> mSessionIdPool = new ConcurrentLinkedQueue<>();

    private final DictionaryFacilitatorLruCache mDictionaryFacilitatorCache =
            new DictionaryFacilitatorLruCache(this /* context */, DICTIONARY_NAME_PREFIX);
    private final ConcurrentHashMap<Locale, Keyboard> mKeyboardCache = new ConcurrentHashMap<>();

    // The threshold for a suggestion to be considered "recommended".
    private float mRecommendedThreshold;
    // TODO: make a spell checker option to block offensive words or not
    private final SettingsValuesForSuggestion mSettingsValuesForSuggestion =
            new SettingsValuesForSuggestion(true /* blockPotentiallyOffensive */);

    public static final String SINGLE_QUOTE = "\u0027";
    public static final String APOSTROPHE = "\u2019";

    // guarded by mSemaphore
    private Locale[] mEnabledLocales;
    // guarded by mSemaphore
    private DictionaryFacilitatorLruCache[] mDictionaryFacilitators;

    public AndroidSpellCheckerService() {
        super();
        for (int i = 0; i < MAX_NUM_OF_THREADS_READ_DICTIONARY; i++) {
            mSessionIdPool.add(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Locale[] enabledLocales = fetchEnabledLocales();
        mEnabledLocales = enabledLocales;
        mDictionaryFacilitators = createDictionaryFacilitators(enabledLocales);

        registerReceiver(checkLocalesReceiver, new IntentFilter(CHECK_ENABLED_LOCALES_BROADCAST), Context.RECEIVER_NOT_EXPORTED);

        mRecommendedThreshold = Float.parseFloat(
                getString(R.string.spellchecker_recommended_threshold_value));
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(prefs, PREF_USE_CONTACTS_KEY);
    }

    static Locale[] fetchEnabledLocales() {
        var rimm = RichInputMethodManager.getInstance();
        List<InputMethodSubtype> enabledSubtypes = rimm.getMyEnabledInputMethodSubtypeList(true);

        int numLocales = enabledSubtypes.size();
        Locale[] res = new Locale[numLocales];

        for (int i = 0; i < numLocales; ++i) {
            InputMethodSubtype subtype = enabledSubtypes.get(i);
            Locale locale = LocaleUtils.constructLocaleFromString(subtype.getLocale());
            res[i] = locale;
        }
        return res;
    }

    DictionaryFacilitatorLruCache[] createDictionaryFacilitators(Locale[] locales) {
        int numLocales = locales.length;
        var dictionaryCaches = new DictionaryFacilitatorLruCache[numLocales];
        var sb = new StringBuilder();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useContactsDict = prefs.getBoolean(PREF_USE_CONTACTS_KEY, true);

        for (int i = 0; i < numLocales; ++i) {
            Locale locale = locales[i];
            var c = new DictionaryFacilitatorLruCache(this, DICTIONARY_NAME_PREFIX);
            c.setUseContactsDictionary(useContactsDict);
            dictionaryCaches[i] = c;

            if (i != 0) {
                sb.append(", ");
            }
            sb.append(locale);
        }
        Log.d(TAG, "created dictionary caches for: " + sb);
        return dictionaryCaches;
    }

    public static final String CHECK_ENABLED_LOCALES_BROADCAST = AndroidSpellCheckerService.class.getName()
            + ".CHECK_LOCALES_BROADCAST";

    private BroadcastReceiver checkLocalesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Locale[] enabledLocaled = fetchEnabledLocales();

            if (!Arrays.deepEquals(enabledLocaled, mEnabledLocales)) {
                Log.d(TAG, "list of enabled locales changed");

                mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY);
                try {
                    for (DictionaryFacilitatorLruCache c : mDictionaryFacilitators) {
                        c.closeDictionaries();
                    }
                    mEnabledLocales = enabledLocaled;
                    mDictionaryFacilitators = createDictionaryFacilitators(enabledLocaled);
                } finally {
                    mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY);
                }
            }
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(checkLocalesReceiver);
    }

    public float getRecommendedThreshold() {
        return mRecommendedThreshold;
    }

    private static String getKeyboardLayoutNameForLocale(final Locale locale) {
        // See b/19963288.
        if (locale.getLanguage().equals("sr")) {
            return "south_slavic";
        }
        final int script = ScriptUtils.getScriptFromSpellCheckerLocale(locale);
        switch (script) {
        case ScriptUtils.SCRIPT_LATIN:
            return "qwerty";
        case ScriptUtils.SCRIPT_CYRILLIC:
            return "east_slavic";
        case ScriptUtils.SCRIPT_GREEK:
            return "greek";
        case ScriptUtils.SCRIPT_HEBREW:
            return "hebrew";
        default:
            throw new RuntimeException("Wrong script supplied: " + script);
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (!PREF_USE_CONTACTS_KEY.equals(key)) return;
        final boolean useContactsDictionary = prefs.getBoolean(PREF_USE_CONTACTS_KEY, true);

        mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        try {
            mDictionaryFacilitatorCache.setUseContactsDictionary(useContactsDictionary);

            for (DictionaryFacilitatorLruCache c : mDictionaryFacilitators) {
                c.setUseContactsDictionary(useContactsDictionary);
            }
        } finally {
            mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        }
    }

    @Override
    public Session createSession() {
        // Should not refer to AndroidSpellCheckerSession directly considering
        // that AndroidSpellCheckerSession may be overlaid.
        return AndroidSpellCheckerSessionFactory.newInstance(this);
    }

    /**
     * Returns an empty SuggestionsInfo with flags signaling the word is not in the dictionary.
     * @param reportAsTypo whether this should include the flag LOOKS_LIKE_TYPO, for red underline.
     * @return the empty SuggestionsInfo with the appropriate flags set.
     */
    public static SuggestionsInfo getNotInDictEmptySuggestions(final boolean reportAsTypo) {
        return new SuggestionsInfo(reportAsTypo ? SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO : 0,
                EMPTY_STRING_ARRAY);
    }

    /**
     * Returns an empty suggestionInfo with flags signaling the word is in the dictionary.
     * @return the empty SuggestionsInfo with the appropriate flags set.
     */
    public static SuggestionsInfo getInDictEmptySuggestions() {
        return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                EMPTY_STRING_ARRAY);
    }

    public boolean isValidWord(final Locale locale, final String word) {
        mSemaphore.acquireUninterruptibly();
        try {
            DictionaryFacilitator dictionaryFacilitatorForLocale =
                    mDictionaryFacilitatorCache.get(locale);
            return dictionaryFacilitatorForLocale.isValidSpellingWord(word);
        } finally {
            mSemaphore.release();
        }
    }

    private static final String MLSC_TAG = "MultiLocaleSpellCheck";
    private static final boolean MLSC_LOG = Log.isLoggable(MLSC_TAG, Log.VERBOSE);

    public boolean isValidWordInEnabledLocalesForAnyCapitalization(final String text, final int capitalizeType) {
        mSemaphore.acquireUninterruptibly();
        Locale[] locales = mEnabledLocales;
        try {
            for (int i = 0; i < locales.length; ++i) {
                Locale locale = locales[i];
                DictionaryFacilitator dictionary = mDictionaryFacilitators[i].get(locale);
                if (isValidWordInLocaleForAnyCapitalization(locale, dictionary, text, capitalizeType)) {
                    if (MLSC_LOG) {
                        Log.d(MLSC_TAG, text + " | valid in " + locale + " locale");
                    }
                    return true;
                }
            }
            if (MLSC_LOG) {
                Log.d(MLSC_TAG, text + " | invalid in all enabled locales");
            }
            return false;
        } finally {
            mSemaphore.release();
        }
    }

    private static boolean isValidWordInLocaleForAnyCapitalization(
            final Locale locale, final DictionaryFacilitator dictionary,
            final String text, final int capitalizeType)
    {
        // inlined from AndroidWordLevelSpellCheckerSession#isInDictForAnyCapitalization

        // If the word is in there as is, then it's in the dictionary. If not, we'll test lower
        // case versions, but only if the word is not already all-lower case or mixed case.
        if (dictionary.isValidSpellingWord(text)) return true;
        if (StringUtils.CAPITALIZE_NONE == capitalizeType) return false;

        // If we come here, we have a capitalized word (either First- or All-).
        // Downcase the word and look it up again. If the word is only capitalized, we
        // tested all possibilities, so if it's still negative we can return false.
        final String lowerCaseText = text.toLowerCase(locale);
        if (dictionary.isValidSpellingWord(lowerCaseText)) return true;
        if (StringUtils.CAPITALIZE_FIRST == capitalizeType) return false;

        // If the lower case version is not in the dictionary, it's still possible
        // that we have an all-caps version of a word that needs to be capitalized
        // according to the dictionary. E.g. "GERMANS" only exists in the dictionary as "Germans".
        return dictionary.isValidSpellingWord(StringUtils.capitalizeFirstAndDowncaseRest(lowerCaseText, locale));
    }

    public SuggestionResults getSuggestionResults(final Locale locale,
            final ComposedData composedData, final NgramContext ngramContext,
            @Nonnull final Keyboard keyboard) {
        Integer sessionId = null;
        mSemaphore.acquireUninterruptibly();
        try {
            sessionId = mSessionIdPool.poll();
            DictionaryFacilitator dictionaryFacilitatorForLocale =
                    mDictionaryFacilitatorCache.get(locale);
            return dictionaryFacilitatorForLocale.getSuggestionResults(composedData, ngramContext,
                    keyboard, mSettingsValuesForSuggestion,
                    sessionId, SuggestedWords.INPUT_STYLE_TYPING);
        } finally {
            if (sessionId != null) {
                mSessionIdPool.add(sessionId);
            }
            mSemaphore.release();
        }
    }

    public boolean hasMainDictionaryForLocale(final Locale locale) {
        mSemaphore.acquireUninterruptibly();
        try {
            final DictionaryFacilitator dictionaryFacilitator =
                    mDictionaryFacilitatorCache.get(locale);
            return dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary();
        } finally {
            mSemaphore.release();
        }
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        mSemaphore.acquireUninterruptibly(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        try {
            mDictionaryFacilitatorCache.closeDictionaries();
            for (DictionaryFacilitatorLruCache c : mDictionaryFacilitators) {
                c.closeDictionaries();
            }
        } finally {
            mSemaphore.release(MAX_NUM_OF_THREADS_READ_DICTIONARY);
        }
        mKeyboardCache.clear();
        return false;
    }

    public Keyboard getKeyboardForLocale(final Locale locale) {
        Keyboard keyboard = mKeyboardCache.get(locale);
        if (keyboard == null) {
            keyboard = createKeyboardForLocale(locale);
            if (keyboard != null) {
                mKeyboardCache.put(locale, keyboard);
            }
        }
        return keyboard;
    }

    private Keyboard createKeyboardForLocale(final Locale locale) {
        final String keyboardLayoutName = getKeyboardLayoutNameForLocale(locale);
        final InputMethodSubtype subtype = AdditionalSubtypeUtils.createDummyAdditionalSubtype(
                locale.toString(), keyboardLayoutName);
        final KeyboardLayoutSet keyboardLayoutSet = createKeyboardSetForSpellChecker(subtype);
        return keyboardLayoutSet.getKeyboard(KeyboardId.ELEMENT_ALPHABET);
    }

    private KeyboardLayoutSet createKeyboardSetForSpellChecker(final InputMethodSubtype subtype) {
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(this, editorInfo);
        builder.setKeyboardGeometry(
                SPELLCHECKER_DUMMY_KEYBOARD_WIDTH, SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT);
        builder.setSubtype(RichInputMethodSubtype.getRichInputMethodSubtype(subtype));
        builder.setIsSpellChecker(true /* isSpellChecker */);
        builder.disableTouchPositionCorrectionData();
        return builder.build();
    }
}
