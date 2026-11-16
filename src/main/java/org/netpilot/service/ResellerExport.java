package org.netpilot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.netpilot.i18n.Messages;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.Money;
import org.netpilot.service.ResellerAccounts.Statement;

/** Points of sale in Excel: one point's statement and history, or every point's figures. */
public final class ResellerExport {

    private ResellerExport() {
    }

    /** One point of sale: its statement, then every movement on its own sheet. */
    public static void statement(Statement s, Path file) throws IOException {
        List<List<Xlsx.Cell>> summary = List.of(
                List.of(Xlsx.text(t("reseller.column.name")), Xlsx.text(s.reseller().name())),
                List.of(Xlsx.text(t("reseller.column.billing")), Xlsx.text(s.reseller().billing().toString())),
                List.of(Xlsx.text(t("reseller.column.given")), Xlsx.count(s.given())),
                List.of(Xlsx.text(t("reseller.column.sold")), Xlsx.count(s.sold())),
                List.of(Xlsx.text(t("reseller.column.left")), Xlsx.count(s.left())),
                List.of(Xlsx.text(t("reseller.column.value")), money(s.salesValue(), s.currency())),
                List.of(Xlsx.text(t("reseller.column.charged")), money(s.chargedValue(), s.currency())),
                List.of(Xlsx.text(t("reseller.column.commission")), money(s.commission(), s.currency())),
                List.of(Xlsx.text(t("reseller.column.paid")), money(s.paid(), s.currency())),
                List.of(Xlsx.text(t("reseller.column.owes")), money(s.due(), s.currency())),
                List.of(Xlsx.text(t("reseller.column.credit")), money(s.credit(), s.currency())));
        List<List<Xlsx.Cell>> moves = new ArrayList<>();
        for (LedgerEvent e : s.events()) {
            boolean payment = e.kind() == LedgerEvent.Kind.PAYMENT || e.kind() == LedgerEvent.Kind.PAYMENT_VOID;
            moves.add(List.of(Xlsx.moment(e.at()), Xlsx.text(t("ledger.kind." + e.kind().name())),
                    Xlsx.text(e.card()), Xlsx.text(e.profile()),
                    payment ? Xlsx.text("") : money(e.price(), e.currency()),
                    payment ? Xlsx.text("") : money(e.commission(), e.currency()),
                    payment ? money(e.amount(), e.currency()) : Xlsx.text(""), Xlsx.text(e.note())));
        }
        Xlsx.write(file, List.of(
                new Xlsx.Sheet(t("export.sheet.statement"), List.of(t("export.column.item"),
                        t("export.column.value")), summary),
                new Xlsx.Sheet(t("export.sheet.movements"), List.of(t("reseller.column.when"),
                        t("reseller.column.kind"), t("reseller.column.card"), t("column.profile"),
                        t("report.column.price"), t("reseller.column.commission"), t("export.column.paid"),
                        t("reseller.column.note")), moves)),
                Messages.isArabic());
    }

    /** Every point of sale on one sheet, one row each. */
    public static void all(List<Statement> statements, Path file) throws IOException {
        List<List<Xlsx.Cell>> rows = new ArrayList<>();
        for (Statement s : statements) {
            rows.add(List.of(Xlsx.text(s.reseller().name()), Xlsx.text(s.reseller().phone()),
                    Xlsx.text(s.reseller().billing().toString()), Xlsx.count(s.given()), Xlsx.count(s.sold()),
                    Xlsx.count(s.left()), money(s.chargedValue(), s.currency()),
                    money(s.commission(), s.currency()), money(s.paid(), s.currency()),
                    money(s.due(), s.currency()), money(s.credit(), s.currency())));
        }
        Xlsx.write(file, List.of(new Xlsx.Sheet(t("export.sheet.resellers"), List.of(t("reseller.column.name"),
                t("export.column.phone"), t("reseller.column.billing"), t("reseller.column.given"),
                t("reseller.column.sold"), t("reseller.column.left"), t("reseller.column.charged"),
                t("reseller.column.commission"), t("reseller.column.paid"), t("reseller.column.owes"),
                t("reseller.column.credit")), rows)), Messages.isArabic());
    }

    private static Xlsx.Cell money(BigDecimal value, String currency) {
        return value == null ? Xlsx.text("") : Xlsx.amount(new Money(value, currency == null ? "" : currency));
    }

    private static String t(String key) {
        return Messages.get(key);
    }
}
