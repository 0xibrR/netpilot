package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.netpilot.model.CodeFormat;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;

class CodeRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"77771234", "48123456", "98765432", "51abcd92", "12121212", "123123", "0482193"})
    void weakPatternsAreRefused(String code) {
        assertFalse(CodeRules.strong(code, true, true), code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"48201937", "582917", "90613485", "7Kq2mZ9p"})
    void ordinaryRandomNumbersPass(String code) {
        assertTrue(CodeRules.strong(code, code.chars().allMatch(Character::isDigit), true), code);
    }

    @Test
    void leadingZeroIsFineAfterAPrefix() {
        assertTrue(CodeRules.strong("0482193", true, false));
    }

    @Test
    void numbersOneTypoApartAreRefused() {
        CodeRules.Batch batch = new CodeRules.Batch(Set.of("48201937"));

        assertFalse(batch.tryAdd("48201937"), "the same number");
        assertFalse(batch.tryAdd("48201938"), "one digit changed");
        assertFalse(batch.tryAdd("58201937"), "first digit changed");
        assertTrue(batch.tryAdd("48201983"), "two digits changed");
    }

    /** The guarantee in practice: no two cards of a big batch are within one character. */
    @Test
    void bigBatchHasNoWeakOrNeighbouringNumbers() {
        GenerationSettings settings = new GenerationSettings(10_000, CodeFormat.DIGITS, "", "", 8, 0, "2M", "all",
                "1h");
        List<HotspotUser> users = new UserGenerator(new Random(3)).generate(settings);

        assertEquals(10_000, users.size());
        Map<String, String> byVariant = new HashMap<>();
        for (HotspotUser u : users) {
            String code = u.username();
            assertTrue(CodeRules.strong(code, true, true), code);
            for (int i = 0; i < code.length(); i++) {
                String variant = code.substring(0, i) + '*' + code.substring(i + 1);
                String other = byVariant.put(variant, code);
                assertTrue(other == null, code + " and " + other + " differ in only one place");
            }
        }
    }

    @Test
    void replacementsKeepTheirDistanceFromNumbersOnTheRouter() {
        GenerationSettings settings = new GenerationSettings(1, CodeFormat.DIGITS, "9", "", 6, 0, "2M", "all", "1h");
        UserGenerator generator = new UserGenerator(new Random(5));
        List<String> router = new ArrayList<>();
        for (HotspotUser u : generator.generate(new GenerationSettings(500, CodeFormat.DIGITS, "9", "", 6, 0, "2M",
                "all", "1h"))) {
            router.add(u.username());
        }

        for (int i = 0; i < 50; i++) {
            String code = generator.replacement(settings, Set.copyOf(router)).orElseThrow().username();
            for (String existing : router) {
                int differences = 0;
                for (int c = 0; c < code.length(); c++) {
                    differences += code.charAt(c) == existing.charAt(c) ? 0 : 1;
                }
                assertTrue(differences >= 2, code + " vs " + existing);
            }
        }
    }

    @Test
    void separatePasswordsAvoidWeakPatternsToo() {
        GenerationSettings settings = new GenerationSettings(300, CodeFormat.DIGITS, "", "", 8, 6, "2M", "all", "1h");
        for (HotspotUser u : new UserGenerator(new Random(9)).generate(settings)) {
            assertTrue(CodeRules.strong(u.password(), false, false), u.password());
        }
    }
}
