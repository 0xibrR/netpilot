package org.netpilot.model;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * International calling codes to choose from next to a phone number: the Arab countries and most
 * others, Palestine as {@code +970}. Names come from Java in the app's language, so the list needs
 * no translation; a number with a code not listed can still be typed in full with {@code +}.
 */
public final class CallingCodes {

    /**
     * One choice in the list. Countries that share one calling code (the USA and Canada on +1,
     * Russia and Kazakhstan on +7) are one choice naming both, so no code appears twice.
     *
     * @param region ISO country code, e.g. {@code PS}
     * @param code   calling code without {@code +}, e.g. {@code 970}
     * @param also   a second country on the same code, or empty
     */
    public record Entry(String region, String code, String also) {

        public Entry {
            also = also == null ? "" : also;
        }

        /** {@code فلسطين  +970}; a shared code: {@code الولايات المتحدة / كندا  +1}. */
        public String label(Locale language) {
            String name = name(region, language);
            if (!also.isEmpty()) {
                name += " / " + name(also, language);
            }
            return name + "  +" + code;
        }

        private static String name(String region, Locale language) {
            String name = Locale.of("", region).getDisplayCountry(language);
            return name.isBlank() ? region : name;
        }
    }

    private static final List<Entry> ALL = List.of(
            // the Arab countries
            e("PS", "970"), e("JO", "962"), e("SY", "963"), e("LB", "961"), e("IQ", "964"),
            e("EG", "20"), e("SA", "966"), e("AE", "971"), e("KW", "965"), e("QA", "974"), e("BH", "973"),
            e("OM", "968"), e("YE", "967"), e("LY", "218"), e("TN", "216"), e("DZ", "213"), e("MA", "212"),
            e("MR", "222"), e("SD", "249"), e("SO", "252"), e("DJ", "253"), e("KM", "269"),
            // neighbours and where many people work or live
            e("TR", "90"), e("IL", "972"), e("CY", "357"), e("IR", "98"), e("AZ", "994"), e("AM", "374"),
            e("GE", "995"), e("GB", "44"), e("DE", "49"), e("FR", "33"), e("IT", "39"), e("ES", "34"),
            e("NL", "31"), e("BE", "32"), e("SE", "46"), e("NO", "47"), e("DK", "45"), e("FI", "358"),
            e("CH", "41"), e("AT", "43"), e("GR", "30"), e("PT", "351"), e("IE", "353"), e("PL", "48"),
            e("RO", "40"), e("UA", "380"), e("BR", "55"),
            e("AR", "54"), e("MX", "52"), e("IN", "91"), e("PK", "92"), e("BD", "880"), e("AF", "93"),
            e("CN", "86"), e("JP", "81"), e("KR", "82"), e("ID", "62"), e("MY", "60"), e("PH", "63"),
            e("TH", "66"), e("VN", "84"), e("SG", "65"), e("AU", "61"), e("NZ", "64"), e("ZA", "27"),
            e("NG", "234"), e("ET", "251"), e("KE", "254"),
            // one code shared by two countries: one choice naming both
            shared("US", "CA", "1"), shared("RU", "KZ", "7"));

    private CallingCodes() {
    }

    private static Entry e(String region, String code) {
        return new Entry(region, code, "");
    }

    private static Entry shared(String region, String also, String code) {
        return new Entry(region, code, also);
    }

    /** Every choice, sorted by country name in {@code language}, then by code. */
    public static List<Entry> all(Locale language) {
        Collator byName = Collator.getInstance(language);
        List<Entry> sorted = new ArrayList<>(ALL);
        sorted.sort(Comparator.comparing((Entry x) -> Locale.of("", x.region()).getDisplayCountry(language), byName)
                .thenComparing(Entry::code));
        return sorted;
    }

    /** The first choice with this code ({@code 970} → Palestine). */
    public static Optional<Entry> forCode(String code) {
        return ALL.stream().filter(x -> x.code().equals(code)).findFirst();
    }
}
