package org.netpilot.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Rules that make card numbers hard to guess and hard to confuse, on top of being random:
 *
 * <ul>
 * <li><b>No weak patterns</b> in the random part: no long runs of one character
 * ({@code 7777}), no counting up or down ({@code 1234}, {@code 9876}, {@code abcd}), and no short
 * unit repeated ({@code 1212}, {@code 123123}). People try these first.</li>
 * <li><b>Never one typo apart</b>: any two cards differ in at least two positions. A customer
 * who mistypes one character, or someone changing one digit of a card they bought, never lands
 * on another valid card.</li>
 * <li><b>No leading zero</b> for digit-only numbers without a prefix, which spreadsheets drop.</li>
 * </ul>
 *
 * <p>A {@link Batch} keeps, for every accepted number, each variant with one position blanked out;
 * two numbers one substitution apart share such a variant, so the check costs one set lookup per
 * position instead of comparing every pair.
 */
final class CodeRules {

    /** Runs and sequences from this length are refused. */
    private static final int MAX_RUN = 3;
    private static final char BLANK = '\u0000';

    private CodeRules() {
    }

    /**
     * @param random      the random part of the number (without prefix and suffix)
     * @param digitsOnly  whether the number uses digits only
     * @param leadingFree whether the random part starts the number (no prefix)
     */
    static boolean strong(String random, boolean digitsOnly, boolean leadingFree) {
        if (random.isEmpty()) {
            return true;
        }
        if (digitsOnly && leadingFree && random.charAt(0) == '0') {
            return false;
        }
        int limit = random.length() <= 4 ? MAX_RUN : MAX_RUN + 1;
        return !hasRun(random, limit) && !hasSequence(random, limit) && !repeatsUnit(random);
    }

    /** {@code limit} or more identical characters in a row. */
    private static boolean hasRun(String s, int limit) {
        int run = 1;
        for (int i = 1; i < s.length(); i++) {
            run = s.charAt(i) == s.charAt(i - 1) ? run + 1 : 1;
            if (run >= limit) {
                return true;
            }
        }
        return false;
    }

    /** {@code limit} or more characters counting up or down by one ({@code 3456}, {@code dcba}). */
    private static boolean hasSequence(String s, int limit) {
        int up = 1;
        int down = 1;
        for (int i = 1; i < s.length(); i++) {
            int step = Character.toLowerCase(s.charAt(i)) - Character.toLowerCase(s.charAt(i - 1));
            up = step == 1 ? up + 1 : 1;
            down = step == -1 ? down + 1 : 1;
            if (up >= limit || down >= limit) {
                return true;
            }
        }
        return false;
    }

    /** The whole part is a shorter unit repeated: {@code 1212}, {@code 4545 45}, {@code 123123}. */
    private static boolean repeatsUnit(String s) {
        int n = s.length();
        for (int unit = 1; unit <= n / 2; unit++) {
            boolean repeats = true;
            for (int i = unit; i < n && repeats; i++) {
                repeats = s.charAt(i) == s.charAt(i - unit);
            }
            if (repeats) {
                return true;
            }
        }
        return false;
    }

    /** Numbers accepted so far; refuses any number equal to or one substitution away from them. */
    static final class Batch {

        private final Set<String> codes = new HashSet<>();
        private final Set<String> variants = new HashSet<>();

        Batch() {
        }

        /** Starts with numbers that already exist (e.g. on the router). */
        Batch(Collection<String> existing) {
            existing.forEach(this::add);
        }

        /** Accepts and remembers the number, or refuses it and changes nothing. */
        boolean tryAdd(String code) {
            if (codes.contains(code)) {
                return false;
            }
            for (int i = 0; i < code.length(); i++) {
                if (variants.contains(blank(code, i))) {
                    return false;
                }
            }
            add(code);
            return true;
        }

        private void add(String code) {
            codes.add(code);
            for (int i = 0; i < code.length(); i++) {
                variants.add(blank(code, i));
            }
        }

        private static String blank(String code, int position) {
            char[] chars = code.toCharArray();
            chars[position] = BLANK;
            return new String(chars);
        }
    }
}
