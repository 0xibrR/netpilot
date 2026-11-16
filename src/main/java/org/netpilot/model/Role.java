package org.netpilot.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who is using NetPilot when app protection is on. Without protection everyone is the owner.
 */
public enum Role {

    /** Everything. */
    OWNER(EnumSet.allOf(Permission.class)),

    /**
     * The counter: sells one card at a time (the quick sale records the sale in the same step),
     * sees the cards and online customers, sends WhatsApp messages, and takes payments from points
     * of sale (their balance only, every payment with a receipt). No batches, no reprints, no
     * deleting, no money figures, no settings.
     */
    CASHIER(EnumSet.of(Permission.QUICK_SALE, Permission.VIEW_USERS, Permission.MESSAGE_CUSTOMERS,
            Permission.RESELLER_PAYMENT));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    /** What a role may do; every privileged action checks one before it runs. */
    public enum Permission {
        /** Sell one card at the counter (quick sale). */
        QUICK_SALE,
        /** See the cards on the router and who is online. */
        VIEW_USERS,
        /** Link customer numbers and send WhatsApp messages. */
        MESSAGE_CUSTOMERS,
        /** Take a payment from a point of sale and see its current balance. */
        RESELLER_PAYMENT,
        /** Create card batches and send them to the router. */
        CREATE_BATCHES,
        /** Print or reprint cards and receipts of cards not sold at the counter. */
        REPRINT_CARDS,
        /** Delete, disable, enable and disconnect cards; delete expired cards. */
        MANAGE_CARDS,
        /** Create, edit and delete packages (user profiles). */
        MANAGE_PACKAGES,
        /** Card design. */
        CARD_DESIGN,
        /** Earnings, sales, monthly report and print history. */
        REPORTS,
        /** Points of sale: statements, handovers, returns, editing, cancelling payments. */
        MANAGE_RESELLERS,
        /** Ping, traceroute, DNS and the router log. */
        DEVELOPER_TOOLS,
        /** Add, edit and remove branches. */
        MANAGE_BRANCHES,
        /** See and handle router alerts. */
        ALERTS,
        /** Settings, backups and restoring them. */
        SETTINGS
    }
}
