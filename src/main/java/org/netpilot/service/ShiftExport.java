package org.netpilot.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Money;
import org.netpilot.model.Shift;

/** Cashier shifts in Excel: one row per shift, then every withdrawal on its own sheet. */
public final class ShiftExport {

    private ShiftExport() {
    }

    /**
     * @param figures the money of each shift (for an open one, until now)
     * @param roles   the words for {@code OWNER} / {@code CASHIER}
     */
    public static void write(List<Shift> shifts, Function<Shift, ShiftBook.Figures> figures,
            Function<String, String> roles, Path file) throws IOException {
        List<List<Xlsx.Cell>> rows = new ArrayList<>();
        List<List<Xlsx.Cell>> withdrawals = new ArrayList<>();
        for (Shift s : shifts) {
            ShiftBook.Figures f = figures.apply(s);
            String c = s.currency();
            rows.add(List.of(Xlsx.moment(s.opened()), Xlsx.moment(s.closing().map(Shift.Closing::at).orElse(null)),
                    Xlsx.text(roles.apply(s.openedBy())), money(s.openingCash(), c), money(f.sales(), c),
                    money(f.payments(), c), money(f.withdrawn(), c), money(f.expected(), c),
                    s.closing().map(x -> money(x.counted(), c)).orElse(Xlsx.text("")),
                    s.closing().map(x -> money(x.difference(), c)).orElse(Xlsx.text("")),
                    Xlsx.text(s.closing().map(Shift.Closing::note).orElse(""))));
            for (Shift.Withdrawal w : s.withdrawals()) {
                withdrawals.add(List.of(Xlsx.moment(s.opened()), Xlsx.moment(w.at()), Xlsx.text(roles.apply(w.by())),
                        money(w.amount(), c), Xlsx.text(w.reason())));
            }
        }
        Xlsx.write(file, List.of(
                new Xlsx.Sheet(t("export.sheet.shifts"), List.of(t("shift.column.opened"), t("shift.column.closed"),
                        t("shift.column.by"), t("shift.openingCash"), t("shift.column.sales"),
                        t("shift.column.payments"), t("shift.column.withdrawn"), t("shift.column.expected"),
                        t("shift.counted"), t("shift.column.difference"), t("shift.note")), rows),
                new Xlsx.Sheet(t("export.sheet.withdrawals"), List.of(t("shift.column.opened"),
                        t("reseller.column.when"), t("shift.column.by"), t("shift.withdraw.amount"),
                        t("shift.note")), withdrawals)),
                Messages.isArabic());
    }

    private static Xlsx.Cell money(java.math.BigDecimal value, String currency) {
        return Xlsx.amount(new Money(value, currency));
    }

    private static String t(String key) {
        return Messages.get(key);
    }
}
