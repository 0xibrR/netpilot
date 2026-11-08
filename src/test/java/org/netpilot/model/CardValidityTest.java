package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CardValidityTest {

    private static final TimeLimit SEVEN_DAYS = new TimeLimit(7, TimeLimit.Unit.DAYS);

    @Test
    void addsReadsAndRemovesTheValidity() {
        String script = CardValidity.apply("", Optional.of(SEVEN_DAYS));

        assertTrue(script.startsWith(CardValidity.BEGIN));
        assertTrue(script.endsWith(CardValidity.END));
        assertTrue(script.contains(":local npValidity 7d"));
        assertEquals(Optional.of(new TimeLimit(1, TimeLimit.Unit.WEEKS)), CardValidity.read(script), "7d = 1 week");
        assertEquals("", CardValidity.apply(script, Optional.empty()));
        assertEquals(Optional.empty(), CardValidity.read(""));
        assertEquals(Optional.empty(), CardValidity.read(null));
    }

    @Test
    void keepsTheUsersOwnScriptAndReplacesOnlyItsOwnPart() {
        String own = ":log info (\"login \" . $user)";
        String withWeek = CardValidity.apply(own, Optional.of(SEVEN_DAYS));
        String withHours = CardValidity.apply(withWeek, Optional.of(new TimeLimit(12, TimeLimit.Unit.HOURS)));

        assertTrue(withWeek.startsWith(own + "\n" + CardValidity.BEGIN));
        assertEquals(1, withHours.split(CardValidity.BEGIN, -1).length - 1, "never two NetPilot parts");
        assertEquals(Optional.of(new TimeLimit(12, TimeLimit.Unit.HOURS)), CardValidity.read(withHours));
        assertEquals(own, CardValidity.apply(withHours, Optional.empty()), "the user's script is left as it was");
    }

    @Test
    void scriptClosesTheCardOnceAndStartsInThePast() {
        String script = CardValidity.script(new TimeLimit(30, TimeLimit.Unit.MINUTES));

        assertTrue(script.contains("[:len [/system scheduler find name=$npTask]] = 0"), "only on first login");
        assertTrue(script.contains(":set npStart ($npStart - 1m)"), "never fires at once");
        assertTrue(script.contains("interval=$npValidity"));
        assertTrue(script.contains("limit-uptime=\\$t disabled=yes"), "shows as expired and cannot log in");
        assertTrue(script.contains("/system scheduler remove [find name=\\\"\" . $npTask"), "removes itself");
        assertTrue(script.contains("\"" + CardValidity.TASK_PREFIX + "\" . $user"));
    }

    @Test
    void profileKeepsItsScriptWhenTheValidityChanges() {
        HotspotProfile plain = new HotspotProfile("*1", "1M", "1M/1M", 1, ":log info hi");

        HotspotProfile timed = plain.withValidity(Optional.of(SEVEN_DAYS));

        assertEquals(Optional.empty(), plain.validity());
        assertEquals(Optional.of(new TimeLimit(1, TimeLimit.Unit.WEEKS)), timed.validity());
        assertTrue(timed.onLogin().startsWith(":log info hi"));
        assertEquals(plain, timed.withValidity(Optional.empty()));
    }
}
