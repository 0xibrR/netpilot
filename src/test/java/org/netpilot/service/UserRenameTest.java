package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Customer;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.CustomerRepository;
import org.netpilot.repository.UsageRepository;

class UserRenameTest {

    @TempDir
    Path dir;

    @Test
    void aRenamedUserKeepsItsHistoryAndCustomerSoNothingIsCountedTwice() throws Exception {
        UsageRepository usage = new UsageRepository(dir.resolve("usage.csv"));
        CustomerRepository customers = new CustomerRepository(dir.resolve("customers.csv"));
        LocalDate day = LocalDate.of(2026, 11, 10);
        usage.saveAll(List.of(new UsageRecord("r1", "test", "8M", "", Optional.of(day)),
                new UsageRecord("r2", "test", "8M", "", Optional.of(day)),
                new UsageRecord("r1", "other", "1M", "", Optional.of(day))));
        LocalDateTime linked = LocalDateTime.of(2026, 11, 1, 10, 0);
        customers.save(new Customer("r1", "test", "+970599000000", linked, Optional.of(linked.plusDays(3))));

        assertEquals(2, UserRename.moveRecords(usage, customers, "r1", "test", "ahmad"));

        assertEquals(List.of(new UsageRecord("r1", "ahmad", "8M", "", Optional.of(day)),
                new UsageRecord("r2", "test", "8M", "", Optional.of(day)),
                new UsageRecord("r1", "other", "1M", "", Optional.of(day))), usage.findAll(),
                "same first-use day under the new name; other routers untouched");
        assertEquals(Optional.of(new Customer("r1", "ahmad", "+970599000000", linked,
                Optional.of(linked.plusDays(3)))), customers.find("r1", "ahmad"));
        assertTrue(customers.find("r1", "test").isEmpty());

        assertEquals(0, UserRename.moveRecords(usage, customers, "r1", "ahmad", "ahmad"), "same name");
        assertEquals(0, UserRename.moveRecords(usage, customers, "r1", "nobody", "x"), "nothing to move");
    }
}
