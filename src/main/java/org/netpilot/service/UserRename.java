package org.netpilot.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.netpilot.model.Customer;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.CustomerRepository;
import org.netpilot.repository.UsageRepository;

/**
 * After a user is renamed on the router, its records on this computer follow the new name: its
 * usage history (so the earnings report does not count it as a new sale) and its customer number.
 * Point-of-sale and counter records are a ledger that is never rewritten, so users held there are
 * not renamed at all.
 */
public final class UserRename {

    private static final System.Logger LOG = System.getLogger(UserRename.class.getName());

    private UserRename() {
    }

    /** @return how many records moved */
    public static int moveRecords(UsageRepository usage, CustomerRepository customers, String router, String from,
            String to) throws IOException {
        if (from.equals(to)) {
            return 0;
        }
        int moved = 0;
        List<UsageRecord> all = new ArrayList<>();
        for (UsageRecord r : usage.findAll()) {
            if (r.router().equals(router) && r.username().equals(from)) {
                all.add(new UsageRecord(r.router(), to, r.profile(), r.batch(), r.firstUsed()));
                moved++;
            } else {
                all.add(r);
            }
        }
        if (moved > 0) {
            usage.saveAll(all);
        }
        Optional<Customer> customer = customers.find(router, from);
        if (customer.isPresent()) {
            Customer c = customer.get();
            customers.save(new Customer(router, to, c.phone(), c.linked(), c.reminded()));
            customers.remove(router, from);
            moved++;
        }
        LOG.log(System.Logger.Level.INFO, "Records of a renamed user moved: {0}", moved);
        return moved;
    }
}
