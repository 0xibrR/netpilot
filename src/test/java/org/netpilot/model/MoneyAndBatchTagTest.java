package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyAndBatchTagTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1000 ل.س|1000|ل.س",
            "١٠٠٠ ل.س|1000|ل.س",
            "$5|5|$",
            "5$|5|$",
            "2,500 SYP|2500|SYP",
            "12.5 USD|12.5|USD",
            "٢٬٥٠٠ ل.س|2500|ل.س",
            "750|750|''"})
    void readsPricesInManyForms(String text, String amount, String currency) {
        Money money = Money.parse(text).orElseThrow();
        assertEquals(0, new BigDecimal(amount).compareTo(money.amount()), text);
        assertEquals(currency, money.currency(), text);
    }

    @Test
    void priceWithoutNumberIsNotMoney() {
        assertEquals(Optional.empty(), Money.parse("مجاني"));
        assertEquals(Optional.empty(), Money.parse(" "));
        assertEquals(Optional.empty(), Money.parse(null));
    }

    @Test
    void arithmeticAndFormatting() {
        Money price = Money.parse("1000 ل.س").orElseThrow();

        assertEquals("47,000 ل.س", price.times(47).format());
        assertEquals("2,000 ل.س", price.plus(price).format());
        assertEquals("12.5", new Money(new BigDecimal("12.50"), "").format());
        assertThrows(IllegalArgumentException.class, () -> price.plus(Money.parse("5$").orElseThrow()));
    }

    @Test
    void readsNetPilotBatchComments() {
        BatchTag tag = BatchTag.parse("NetPilot 2026-11-06 21:56 #b062").orElseThrow();

        assertEquals(LocalDateTime.of(2026, 11, 6, 21, 56), tag.created());
        assertEquals("b062", tag.id());
        assertEquals(Optional.empty(), BatchTag.parse("counters and limits for trial users"));
        assertEquals(Optional.empty(), BatchTag.parse("NetPilot 2026-13-06 21:56 #b062"), "invalid month");
        assertEquals(Optional.empty(), BatchTag.parse(null));
    }

    @Test
    void periods() {
        LocalDate today = LocalDate.of(2026, 11, 7);

        assertTrue(ReportPeriod.LAST_7_DAYS.contains(LocalDate.of(2026, 11, 1), today));
        assertTrue(!ReportPeriod.LAST_7_DAYS.contains(LocalDate.of(2026, 10, 31), today));
        assertEquals(LocalDate.of(2026, 11, 1), ReportPeriod.THIS_MONTH.from(today));
        assertEquals(LocalDate.of(2026, 10, 1), ReportPeriod.LAST_MONTH.from(today));
        assertEquals(LocalDate.of(2026, 10, 31), ReportPeriod.LAST_MONTH.to(today));
        assertTrue(ReportPeriod.ALL.contains(LocalDate.of(2000, 1, 1), today));
    }
}
