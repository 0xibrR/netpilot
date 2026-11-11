package org.netpilot.service.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.netpilot.model.LogEntry;

/**
 * Follows the router's log across repeated reads: each read returns the whole log RouterOS keeps
 * in memory, and this keeps only the lines not seen before, in order, so a page can append them
 * instead of rebuilding its list. Older lines beyond {@link #MAX_LINES} are let go.
 */
public final class LogFeed {

    /** Lines kept for the page; RouterOS itself keeps about 1000 in memory. */
    public static final int MAX_LINES = 5000;

    private final Map<String, LogEntry> seen = new LinkedHashMap<>();
    private final int maxLines;

    public LogFeed() {
        this(MAX_LINES);
    }

    LogFeed(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    /**
     * Adds one read of the log.
     *
     * @return the lines that are new since the last read, oldest first
     */
    public synchronized List<LogEntry> add(List<LogEntry> read) {
        List<LogEntry> fresh = new ArrayList<>();
        for (LogEntry entry : read) {
            if (!seen.containsKey(entry.id())) {
                fresh.add(entry);
            }
        }
        fresh.sort(Comparator.comparingLong(LogEntry::order));
        fresh.forEach(e -> seen.put(e.id(), e));
        var it = seen.keySet().iterator();
        while (seen.size() > maxLines && it.hasNext()) {
            it.next();
            it.remove();
        }
        return List.copyOf(fresh);
    }

    /** The most lines kept; a page showing them should keep no more. */
    public int limit() {
        return maxLines;
    }

    /** Forgets every line, e.g. after connecting to another router. */
    public synchronized void clear() {
        seen.clear();
    }
}
