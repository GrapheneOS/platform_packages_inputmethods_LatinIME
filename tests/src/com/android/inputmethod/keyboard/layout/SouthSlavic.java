/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.inputmethod.keyboard.layout;

import com.android.inputmethod.keyboard.layout.expected.ExpectedKey;
import com.android.inputmethod.keyboard.layout.expected.ExpectedKeyboardBuilder;
import com.android.inputmethod.latin.Constants;

import java.util.Locale;

public final class SouthSlavic extends LayoutBase {
    private static final String LAYOUT_NAME = "south_slavic";

    public SouthSlavic(final LayoutCustomizer customizer) {
        super(customizer, Symbols.class, SymbolsShifted.class);
    }

    @Override
    public String getName() { return LAYOUT_NAME; }

    public static class SouthSlavicLayoutCustomizer extends LayoutCustomizer {
        public SouthSlavicLayoutCustomizer(final Locale locale) {
            super(locale);
        }

        @Override
        public final ExpectedKey getAlphabetKey() { return SOUTH_SLAVIC_ALPHABET_KEY; }

        @Override
        public ExpectedKey[] getRightShiftKeys(final boolean isPhone) {
            return isPhone ? EMPTY_KEYS : EXCLAMATION_AND_QUESTION_MARKS;
        }

        // U+0410: "А" CYRILLIC CAPITAL LETTER A
        // U+0411: "Б" CYRILLIC CAPITAL LETTER BE
        // U+0412: "В" CYRILLIC CAPITAL LETTER VE
        private static final ExpectedKey SOUTH_SLAVIC_ALPHABET_KEY = key(
                "\u0410\u0411\u0412", Constants.CODE_SWITCH_ALPHA_SYMBOL);
    }

    @Override
    ExpectedKey[][] getCommonAlphabetLayout(final boolean isPhone) { return ALPHABET_COMMON; }

    public static final String ROW1_6 = "ROW1_6";
    public static final String ROW2_11 = "ROW2_11";
    public static final String ROW3_1 = "ROW3_1";
    public static final String ROW3_8 = "ROW3_8";

    private static final ExpectedKey[][] ALPHABET_COMMON = new ExpectedKeyboardBuilder()
            .setKeysOfRow(1,
                    // U+0459: "љ" CYRILLIC SMALL LETTER LJE
                    key("\u0459", moreKey("1")),
                    // U+045A: "њ" CYRILLIC SMALL LETTER NJE
                    key("\u045A", moreKey("2")),
                    // U+0435: "е" CYRILLIC SMALL LETTER IE
                    key("\u0435", moreKey("3")),
                    // U+0440: "р" CYRILLIC SMALL LETTER ER
                    key("\u0440", moreKey("4")),
                    // U+0442: "т" CYRILLIC SMALL LETTER TE
                    key("\u0442", moreKey("5")),
                    key(ROW1_6, moreKey("6")),
                    // U+0443: "у" CYRILLIC SMALL LETTER U
                    key("\u0443", moreKey("7")),
                    // U+0438: "и" CYRILLIC SMALL LETTER I
                    key("\u0438", moreKey("8")),
                    // U+043E: "о" CYRILLIC SMALL LETTER O
                    key("\u043E", moreKey("9")),
                    // U+043F: "п" CYRILLIC SMALL LETTER PE
                    key("\u043F", moreKey("0")),
                    // U+0448: "ш" CYRILLIC SMALL LETTER SHA
                    key("\u0448"))
            // U+0430: "а" CYRILLIC SMALL LETTER A
            // U+0441: "с" CYRILLIC SMALL LETTER ES
            // U+0434: "д" CYRILLIC SMALL LETTER DE
            // U+0444: "ф" CYRILLIC SMALL LETTER EF
            // U+0433: "г" CYRILLIC SMALL LETTER GHE
            // U+0445: "х" CYRILLIC SMALL LETTER HA
            // U+0458: "ј" CYRILLIC SMALL LETTER JE
            // U+043A: "к" CYRILLIC SMALL LETTER KA
            // U+043B: "л" CYRILLIC SMALL LETTER EL
            // U+0447: "ч" CYRILLIC SMALL LETTER CHE
            .setLabelsOfRow(2,
                    "\u0430", "\u0441", "\u0434", "\u0444", "\u0433", "\u0445", "\u0458", "\u043A",
                    "\u043B", "\u0447", ROW2_11)
            // U+045F: "џ" CYRILLIC SMALL LETTER DZHE
            // U+0446: "ц" CYRILLIC SMALL LETTER TSE
            // U+0432: "в" CYRILLIC SMALL LETTER VE
            // U+0431: "б" CYRILLIC SMALL LETTER BE
            // U+043D: "н" CYRILLIC SMALL LETTER EN
            // U+043C: "м" CYRILLIC SMALL LETTER EM
            // U+0436: "ж" CYRILLIC SMALL LETTER ZHE
            .setLabelsOfRow(3,
                    ROW3_1, "\u045F", "\u0446", "\u0432", "\u0431", "\u043D", "\u043C", ROW3_8,
                    "\u0436")
            .build();
}