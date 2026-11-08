package org.netpilot.model;

import java.util.Optional;
import org.netpilot.i18n.Messages;

/**
 * A card's data limit as the user types it: a whole number of megabytes or gigabytes, upload and
 * download together. Becomes RouterOS's {@code limit-bytes-total} (bytes) and back.
 *
 * <p>Like WinBox and RouterOS, 1 GB = 1024 MB and 1 MB = 1024 × 1024 bytes, so a 1 GB card shows
 * as {@code 1 GiB} in the users list, the same as in WinBox.
 *
 * @param amount how many units, at least 1
 * @param unit   megabytes or gigabytes
 */
public record DataLimit(long amount, Unit unit) {

    public static final long MAX_AMOUNT = 99_999;

    public enum Unit {

        MB(1L << 20),
        GB(1L << 30);

        private final long bytes;

        Unit(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return Messages.get("data.unit." + name());
        }
    }

    public DataLimit {
        amount = Math.clamp(amount, 1, MAX_AMOUNT);
        unit = unit == null ? Unit.GB : unit;
    }

    /** RouterOS {@code limit-bytes-total}: the limit in bytes. */
    public long bytes() {
        return amount * unit.bytes;
    }

    /** Short text for cards and receipts, e.g. {@code 1 GB} or {@code 500 MB}. */
    public String label() {
        return amount + " " + unit.name();
    }

    /**
     * Reads a byte count in the largest unit that keeps it exact (1073741824 → 1 GB, 524288000 →
     * 500 MB). Empty for 0 (no limit), negative values, or a size that is not whole megabytes.
     */
    public static Optional<DataLimit> ofBytes(long bytes) {
        if (bytes <= 0) {
            return Optional.empty();
        }
        if (bytes % Unit.GB.bytes == 0 && bytes / Unit.GB.bytes <= MAX_AMOUNT) {
            return Optional.of(new DataLimit(bytes / Unit.GB.bytes, Unit.GB));
        }
        if (bytes % Unit.MB.bytes == 0 && bytes / Unit.MB.bytes <= MAX_AMOUNT) {
            return Optional.of(new DataLimit(bytes / Unit.MB.bytes, Unit.MB));
        }
        return Optional.empty();
    }
}
