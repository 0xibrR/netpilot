package org.netpilot.service.update;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A published NetPilot release on GitHub.
 *
 * @param version its version
 * @param tag     the git tag, e.g. {@code v1.2.0}
 * @param notes   the release notes (the CHANGELOG section), as written
 * @param page    the release page, for people who download by hand
 * @param assets  the files attached to it
 */
public record Release(AppVersion version, String tag, String notes, String page, List<Asset> assets) {

    /** The checksum file the release workflow attaches. */
    public static final String CHECKSUMS = "SHA256SUMS";

    /** A file attached to a release. */
    public record Asset(String name, String url, long size) {

        public Asset {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
        }
    }

    public Release {
        Objects.requireNonNull(version, "version");
        tag = tag == null ? "" : tag;
        notes = notes == null ? "" : notes.strip();
        page = page == null ? "" : page;
        assets = List.copyOf(assets);
    }

    /** The Windows installer ({@code NetPilot-1.2.0.exe}). */
    public Optional<Asset> installer() {
        return assets.stream().filter(a -> a.name().toLowerCase(Locale.ROOT).endsWith(".exe")).findFirst();
    }

    public Optional<Asset> checksums() {
        return assets.stream().filter(a -> a.name().equals(CHECKSUMS)).findFirst();
    }
}
