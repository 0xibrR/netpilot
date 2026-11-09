package org.netpilot.platform;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The operating system and processor NetPilot runs on: the one place that looks at
 * {@code os.name} and {@code os.arch}. Everything platform-specific asks this instead of checking
 * the system itself.
 *
 * <p>Note on Apple Silicon: a Java made for Intel Macs, run through Rosetta, reports
 * {@code x86_64}. NetPilot then sees an Intel Mac, which is what that Java really is; the macOS
 * package should therefore bring an Apple Silicon (aarch64) Java.
 *
 * @param os        the operating system family
 * @param arch      the processor architecture of the running Java
 * @param osVersion the system's version text, e.g. {@code 10.0} or {@code 14.5}
 */
public record PlatformInfo(OperatingSystem os, Architecture arch, String osVersion) {

    /** Operating system families NetPilot tells apart. */
    public enum OperatingSystem {

        WINDOWS,
        MACOS,
        LINUX,
        OTHER;

        static OperatingSystem from(String osName) {
            String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (name.startsWith("windows")) {
                return WINDOWS;
            }
            if (name.startsWith("mac") || name.startsWith("darwin")) {
                return MACOS;
            }
            if (name.startsWith("linux")) {
                return LINUX;
            }
            return OTHER;
        }
    }

    /** Processor architectures, by what the running Java reports. */
    public enum Architecture {

        /** Intel and AMD 64-bit ({@code amd64}, {@code x86_64}). */
        X64,
        /** 64-bit ARM, including Apple Silicon ({@code aarch64}, {@code arm64}). */
        ARM64,
        /** 32-bit Intel. */
        X86,
        OTHER;

        static Architecture from(String osArch) {
            return switch (osArch == null ? "" : osArch.toLowerCase(Locale.ROOT)) {
                case "amd64", "x86_64", "x64" -> X64;
                case "aarch64", "arm64" -> ARM64;
                case "x86", "i386", "i486", "i586", "i686" -> X86;
                default -> OTHER;
            };
        }
    }

    private static final PlatformInfo CURRENT = of(Map.of(
            "os.name", System.getProperty("os.name", ""),
            "os.arch", System.getProperty("os.arch", ""),
            "os.version", System.getProperty("os.version", "")));

    public PlatformInfo {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(arch, "arch");
        osVersion = osVersion == null ? "" : osVersion;
    }

    /** The computer NetPilot is running on now. */
    public static PlatformInfo current() {
        return CURRENT;
    }

    /** From system properties ({@code os.name}, {@code os.arch}, {@code os.version}). */
    public static PlatformInfo of(Map<String, String> properties) {
        return new PlatformInfo(OperatingSystem.from(properties.get("os.name")),
                Architecture.from(properties.get("os.arch")), properties.get("os.version"));
    }

    public boolean windows() {
        return os == OperatingSystem.WINDOWS;
    }

    public boolean macos() {
        return os == OperatingSystem.MACOS;
    }

    /** A Mac with an Apple processor (M1 and later), running a native Java. */
    public boolean appleSilicon() {
        return os == OperatingSystem.MACOS && arch == Architecture.ARM64;
    }

    /** E.g. {@code Windows x64 10.0} or {@code macOS ARM64 (Apple Silicon) 14.5}, for logs. */
    public String describe() {
        String system = switch (os) {
            case WINDOWS -> "Windows";
            case MACOS -> "macOS";
            case LINUX -> "Linux";
            case OTHER -> "Other";
        };
        String cpu = appleSilicon()
                ? "ARM64 (Apple Silicon)"
                : os == OperatingSystem.MACOS && arch == Architecture.X64 ? "x64 (Intel)" : arch.name();
        return (system + " " + cpu + " " + osVersion).strip();
    }
}
