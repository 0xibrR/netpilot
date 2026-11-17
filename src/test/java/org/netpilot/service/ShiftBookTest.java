package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Reseller;
import org.netpilot.model.Shift;
import org.netpilot.repository.ResellerRepository;

class ShiftBookTest {

    @TempDir
    Path dir;

    /** One clock for the ledger and the shifts, moved by hand. */
    private static final class TestClock extends Clock {

        Instant now = Instant.parse("2026-11-17T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void pass(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();

    private static final Map<String, String> PRICES = Map.of("1M", "1000 ل.س");

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Test
    void theDrawerIsTheFloatPlusSalesAndPaymentsLessWithdrawals() throws Exception {
        ResellerAccounts accounts = new ResellerAccounts(new ResellerRepository(dir.resolve("r.csv"),
                dir.resolve("l.csv")), clock);
        ShiftBook book = new ShiftBook(dir.resolve("shifts.csv"), accounts, clock);
        Reseller ali = accounts.save(new Reseller("ali", "محل علي", "", Reseller.CommissionType.PERCENT,
                BigDecimal.TEN, Reseller.Billing.ON_SALE));
        QuickSale sale = new QuickSale(accounts);

        // before the shift: not in this drawer
        sale.confirm(sale.reserve(new ResellerAccounts.Card("r", "early", "1M"), PRICES).orElseThrow());
        clock.pass(Duration.ofMinutes(5));

        assertEquals(Optional.empty(), book.current());
        Shift shift = book.open("CASHIER", n("150"), "ل.س");
        assertThrows(IllegalStateException.class, () -> book.open("OWNER", n("0"), "ل.س"), "one shift at a time");

        clock.pass(Duration.ofMinutes(10));
        sale.confirm(sale.reserve(new ResellerAccounts.Card("r", "c1", "1M"), PRICES).orElseThrow());
        sale.confirm(sale.reserve(new ResellerAccounts.Card("r", "c2", "1M"), PRICES).orElseThrow());
        accounts.pay("p1", ali, n("400"), "ل.س", LocalDate.of(2026, 11, 17), "");
        accounts.pay("p2", ali, n("250"), "ل.س", LocalDate.of(2026, 11, 17), "خطأ");
        accounts.voidPayment("p2", "entered twice"); // never reached the drawer
        book.withdraw("CASHIER", n("100"), "خبز");
        assertThrows(IllegalArgumentException.class, () -> book.withdraw("CASHIER", n("0"), "x"));

        ShiftBook.Figures now = book.figures(book.current().orElseThrow());
        assertEquals(n("2000"), now.sales());
        assertEquals(n("400"), now.payments());
        assertEquals(n("100"), now.withdrawn());
        assertEquals(n("2450"), now.expected(), "150 + 2000 + 400 − 100");

        clock.pass(Duration.ofHours(8));
        Shift closed = book.close("CASHIER", n("2400"), "نقص 50");
        assertFalse(closed.open());
        assertEquals(n("-50"), closed.closing().orElseThrow().difference(), "50 missing");
        assertEquals(Optional.empty(), book.current());

        // later sales never change a closed shift
        sale.confirm(sale.reserve(new ResellerAccounts.Card("r", "late", "1M"), PRICES).orElseThrow());
        ShiftBook again = new ShiftBook(dir.resolve("shifts.csv"), accounts, clock);
        Shift back = again.all().getFirst();
        assertEquals(shift.id(), back.id());
        assertEquals(n("2450"), back.closing().orElseThrow().expected());
        assertEquals(n("2000"), again.figures(back).sales());
        assertEquals("خبز", back.withdrawals().getFirst().reason());
        assertEquals("نقص 50", back.closing().orElseThrow().note());
        assertEquals("CASHIER", back.openedBy());
    }

    @Test
    void anOpenShiftSurvivesARestartAndOnlyOneCloseCounts() throws Exception {
        ResellerAccounts accounts = new ResellerAccounts(new ResellerRepository(dir.resolve("r.csv"),
                dir.resolve("l.csv")), clock);
        new ShiftBook(dir.resolve("shifts.csv"), accounts, clock).open("CASHIER", n("0"), "");
        ShiftBook reopened = new ShiftBook(dir.resolve("shifts.csv"), accounts, clock);
        assertTrue(reopened.current().isPresent(), "still open after NetPilot restarted");
        assertThrows(IllegalArgumentException.class, () -> reopened.withdraw("x", n("-1"), ""), "negative");
        reopened.close("OWNER", n("0"), "closed by the owner");
        assertThrows(IllegalStateException.class, () -> reopened.close("OWNER", n("0"), ""), "nothing open");
        assertThrows(IllegalStateException.class, () -> reopened.withdraw("OWNER", n("5"), ""), "nothing open");

        clock.pass(Duration.ofMinutes(1));
        reopened.open("OWNER", n("20"), "");
        assertEquals(2, reopened.all().size());
        assertTrue(reopened.all().getFirst().open(), "newest first");

        Files.writeString(dir.resolve("shifts.csv"), "\nOPEN,broken\nWHAT,x\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(2, reopened.all().size(), "bad lines are skipped");
        assertEquals(List.of(), new ShiftBook(dir.resolve("none.csv"), accounts, clock).all());
    }

    @Test
    void shiftsGoToExcelAndTheOwnerCanRequireAShift() throws Exception {
        ResellerAccounts accounts = new ResellerAccounts(new ResellerRepository(dir.resolve("r.csv"),
                dir.resolve("l.csv")), clock);
        ShiftBook book = new ShiftBook(dir.resolve("shifts.csv"), accounts, clock);
        book.open("CASHIER", n("100"), "ل.س");
        book.withdraw("CASHIER", n("30"), "شاي");
        clock.pass(Duration.ofHours(1));
        book.close("OWNER", n("75"), "");
        clock.pass(Duration.ofMinutes(1));
        book.open("OWNER", n("10"), "ل.س");

        Path file = dir.resolve("shifts.xlsx");
        ShiftExport.write(book.all(), s -> {
            try {
                return book.figures(s);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, role -> role.equals("OWNER") ? "المالك" : "الكاشير", file);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            String shifts = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(shifts.contains("<v>70</v>") && shifts.contains("<v>5</v>"),
                    "100 less 30 taken out is 70 expected; 75 counted is 5 over");
            assertTrue(shifts.contains("المالك") && shifts.contains("الكاشير"));
            String out = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(out.contains("شاي") && out.contains("<v>30</v>"));
        }

        PinVault vault = new PinVault(dir.resolve("security.properties"), clock);
        assertFalse(vault.requireShift(), "off unless the owner turns it on");
        vault.setRequireShift(true);
        assertTrue(new PinVault(dir.resolve("security.properties"), clock).requireShift());
    }
}
