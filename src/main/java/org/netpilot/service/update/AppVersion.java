package org.netpilot.service.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release version such as {@code 1.10.2}, compared number by number (so 1.10.0 is newer than
 * 1.9.0, which comparing text would get wrong).
 */
public record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {

    private static final Pattern VERSION = Pattern.compile("^v?(\\d{1,4})\\.(\\d{1,4})(?:\\.(\\d{1,4}))?$");

    /**
     * {@code 1.2.0}, {@code v1.2.0} or {@code 1.2}. Test builds ({@code 1.3.0-beta},
     * {@code 1.2.0-SNAPSHOT}) and anything else give empty: they are never offered as updates.
     */
    public static Optional<AppVersion> parse(String text) {
        Matcher m = VERSION.matcher(text == null ? "" : text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AppVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))));
    }

    public boolean newerThan(AppVersion other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(AppVersion other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        return c == 0 ? Integer.compare(patch, other.patch) : c;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
