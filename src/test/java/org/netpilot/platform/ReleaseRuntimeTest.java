package org.netpilot.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The installed NetPilot has no developer tools: its small Java runtime leaves out the debugger,
 * agents and remote management, so nothing can attach to a running copy to get around app
 * protection. This keeps it that way.
 */
class ReleaseRuntimeTest {

    @Test
    void theReleaseRuntimeHasNoDebuggerAgentsOrRemoteManagement() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher modules = Pattern.compile("<installer.jdk.modules>([^<]+)</installer.jdk.modules>").matcher(pom);
        assertTrue(modules.find(), "the runtime's module list");
        List<String> included = List.of(modules.group(1).split(","));
        for (String tool : List.of("jdk.jdwp.agent", "jdk.attach", "java.instrument", "java.management",
                "jdk.management", "jdk.management.agent", "jdk.jdi", "jdk.jconsole", "jdk.jshell")) {
            assertFalse(included.contains(tool), tool + " must not be in the installed runtime");
        }
        assertFalse(pom.contains("--win-console"), "no console window in the installed app");
        assertFalse(pom.contains("agentlib") || pom.contains("javaagent"), "no agent started with the app");
    }
}
