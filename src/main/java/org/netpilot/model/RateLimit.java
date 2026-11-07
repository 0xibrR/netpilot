package org.netpilot.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Upload and download speed of a hotspot profile, as RouterOS writes it: {@code upload/download}
 * (the router's rx/tx), each a number with an optional unit {@code k}, {@code M} or {@code G}.
 * An empty value means no limit.
 *
 * @param upload   e.g. {@code 10M}, empty for no limit
 * @param download e.g. {@code 1000M}, empty for no limit
 */
public record RateLimit(String upload, String download) {

    private static final Pattern RATE = Pattern.compile("^\\d+(\\.\\d+)?[kKmMgG]?$");

    public RateLimit {
        upload = normalize(upload);
        download = normalize(download);
    }

    /** {@code 10M/1000M} → upload 10M, download 1000M; a single value applies to both. */
    public static RateLimit parse(String rateLimit) {
        if (rateLimit == null || rateLimit.isBlank()) {
            return new RateLimit("", "");
        }
        // RouterOS can add bursts after a space ("2M/2M 4M/4M ..."); only the first pair is the speed
        String first = rateLimit.strip().split("\\s+")[0];
        String[] parts = first.split("/", -1);
        return parts.length >= 2 ? new RateLimit(parts[0], parts[1]) : new RateLimit(parts[0], parts[0]);
    }

    /** Whether a typed speed (one side) is a RouterOS rate, or empty. */
    public static boolean validRate(String value) {
        String v = value == null ? "" : value.strip();
        return v.isEmpty() || RATE.matcher(v).matches();
    }

    /** Whether a whole rate-limit text can be sent to RouterOS. */
    public static boolean valid(String rateLimit) {
        RateLimit parsed = parse(rateLimit);
        return validRate(parsed.upload()) && validRate(parsed.download());
    }

    public boolean unlimited() {
        return upload.isEmpty() && download.isEmpty();
    }

    /** RouterOS form; {@code 0} stands for "no limit" on one side when the other has one. */
    public String format() {
        if (unlimited()) {
            return "";
        }
        return (upload.isEmpty() ? "0" : upload) + "/" + (download.isEmpty() ? "0" : download);
    }

    /** {@code 7M} → {@code 7M}, {@code 1000m} → {@code 1000M}, {@code 512K} → {@code 512k}. */
    private static String normalize(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.equals("0")) {
            return "";
        }
        char unit = v.charAt(v.length() - 1);
        if (Character.isLetter(unit)) {
            String number = v.substring(0, v.length() - 1);
            String fixed = unit == 'k' || unit == 'K' ? "k" : String.valueOf(unit).toUpperCase(Locale.ROOT);
            return number + fixed;
        }
        return v;
    }
}
