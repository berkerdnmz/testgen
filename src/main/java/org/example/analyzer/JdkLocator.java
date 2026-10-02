package org.example.analyzer;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Makinedeki JDK kurulumlarini bulur ve "bu proje icin hangisi" sorusunu cevaplar.
 *
 * Gerekce: hedef projenin pom'u Java 11 isterken calisan JVM 23 olabilir
 * (ya da tersi). Bu uyusmazlik uc kez sessizce yanlis sonuca yol acti;
 * en carpicisi yatay-gecis'in JDK 17 ile kosulmasi sonucu "gerileme" sanilan
 * derleme hatasiydi ("release version 21 not supported").
 *
 * Secim kurali: gereken surumden BUYUK VEYA ESIT olan EN DUSUK JDK.
 * Loan (Java 11) -> JDK 17, yatay-gecis (Java 21) -> JDK 23.
 */
public class JdkLocator {

    /** major surum -> JDK kok dizini */
    private final NavigableMap<Integer, Path> installed = new TreeMap<>();

    public JdkLocator() {
        for (Path dir : candidateDirs()) {
            scan(dir);
        }
        // Calisan JVM de bir adaydir
        Path current = Paths.get(System.getProperty("java.home"));
        register(current);
    }

    /** JDK aranan klasorler. Bulunamayanlar sessizce atlanir. */
    private static List<Path> candidateDirs() {
        List<Path> dirs = new ArrayList<>();
        String home = System.getProperty("user.home", "");

        dirs.add(Paths.get("C:", "Program Files", "Java"));
        dirs.add(Paths.get("C:", "Program Files (x86)", "Java"));
        dirs.add(Paths.get("C:", "Program Files", "Eclipse Adoptium"));
        dirs.add(Paths.get("C:", "Program Files", "Microsoft"));
        dirs.add(Paths.get(home, "Desktop"));
        dirs.add(Paths.get(home, ".jdks"));           // IntelliJ'in indirdigi JDK'lar
        dirs.add(Paths.get("/usr/lib/jvm"));
        dirs.add(Paths.get("/Library/Java/JavaVirtualMachines"));

        return dirs;
    }

    private void scan(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory).forEach(this::register);
        } catch (Exception ignored) { }
    }

    /** Bir klasor gercekten JDK mi: release dosyasi + javac ikilisi. */
    private void register(Path candidate) {
        Integer major = majorVersion(candidate);
        if (major == null) return;
        if (javacPath(candidate) == null) return;      // JRE olabilir, derleyemez

        // Ayni surumden birden fazla varsa ilk bulunan kalir
        installed.putIfAbsent(major, candidate);
    }

    /** release dosyasindaki JAVA_VERSION="21.0.2" -> 21 */
    private Integer majorVersion(Path jdkHome) {
        Path release = jdkHome.resolve("release");
        if (!Files.isRegularFile(release)) return null;

        try {
            for (String line : Files.readAllLines(release)) {
                if (!line.startsWith("JAVA_VERSION=")) continue;
                String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                return parseMajor(v);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** "21.0.2" -> 21, "1.8.0_392" -> 8 */
    static Integer parseMajor(String version) {
        try {
            String[] parts = version.split("[._-]");
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) return Integer.parseInt(parts[1]);
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    /** JDK icindeki javac yolu; yoksa null. */
    public static Path javacPath(Path jdkHome) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javac = jdkHome.resolve("bin").resolve(windows ? "javac.exe" : "javac");
        return Files.isRegularFile(javac) ? javac : null;
    }

    /**
     * Gereken surum icin uygun JDK kokunu dondurur.
     * Kural: >= required olan en dusuk surum. Yoksa Optional.empty().
     */
    public Optional<Path> forVersion(int required) {
        var entry = installed.ceilingEntry(required);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    public Optional<Integer> versionOf(Path jdkHome) {
        for (var e : installed.entrySet()) {
            if (e.getValue().equals(jdkHome)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    public NavigableMap<Integer, Path> installed() {
        return Collections.unmodifiableNavigableMap(installed);
    }

    public String describe() {
        if (installed.isEmpty()) return "hicbir JDK bulunamadi";
        StringBuilder sb = new StringBuilder();
        installed.forEach((v, p) -> sb.append(v).append(" (").append(p).append("), "));
        return sb.substring(0, sb.length() - 2);
    }
}