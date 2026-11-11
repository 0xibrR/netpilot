package org.netpilot.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the router found for a name: the records in its DNS cache after looking the name up.
 *
 * @param name    the name asked for
 * @param records the answers, e.g. {@code A 142.250.74.46}, {@code CNAME other.example}
 */
public record DnsAnswer(String name, List<Record> records) {

    /**
     * One DNS record.
     *
     * @param name the name it belongs to (differs from the question after a CNAME)
     * @param type {@code A}, {@code AAAA}, {@code CNAME} …
     * @param data the address or the other name
     * @param ttl  how long the router keeps it; empty when not reported
     */
    public record Record(String name, String type, String data, Optional<Duration> ttl) {

        public Record {
            name = name == null ? "" : name;
            type = type == null ? "" : type;
            data = data == null ? "" : data;
            ttl = ttl == null ? Optional.empty() : ttl;
        }

        /** An address record (A or AAAA). */
        public boolean address() {
            return type.equals("A") || type.equals("AAAA");
        }
    }

    public DnsAnswer {
        Objects.requireNonNull(name, "name");
        records = List.copyOf(records);
    }

    /** The addresses found, in order. */
    public List<String> addresses() {
        return records.stream().filter(Record::address).map(Record::data).distinct().toList();
    }

    /**
     * A name that can be looked up: a host name, not an IP address. Checked before it reaches the
     * router, like a ping address.
     *
     * @throws IllegalArgumentException when it is not one
     */
    public static String validName(String name) {
        String value;
        try {
            value = PingReply.target(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(org.netpilot.i18n.Messages.get("dns.validation.name"), e);
        }
        if (PingReply.isIpAddress(value)) {
            throw new IllegalArgumentException(org.netpilot.i18n.Messages.get("dns.validation.name"));
        }
        return value;
    }
}
