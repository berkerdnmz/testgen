package org.example.runner;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class TestRunner {

    private final Path projectRoot;   // pom.xml'in bulundugu klasor

    /** JdkLocator'in sectigi JDK; null ise ust surecin JAVA_HOME'u kullanilir. */
    private final String javaHome;

    /**
     * Iki degeri de constructor alir - yarim kurulmus nesne kalmaz.
     * Onceden javaHome setter ile sonradan veriliyordu ve "kim ne zaman
     * ayarladi" sorusu ancak cagri sirasi okunarak cevaplanabiliyordu.
     */
    public TestRunner(Path projectRoot, String javaHome) {
        this.projectRoot = projectRoot;
        this.javaHome    = javaHome;
    }

    /** Windows'ta mvn.cmd, diger sistemlerde mvn. */
    private static String mvn() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "mvn.cmd" : "mvn";
    }

    public Path writeTest(String packageName, String className, String code) throws IOException {
        Path dir = projectRoot.resolve("src/test/java")
                .resolve(packageName.replace('.', '/'));
        Files.createDirectories(dir);

        Path file = dir.resolve(className + ".java");
        Files.writeString(file, code);
        return file;
    }

    // ------------------------------------------------------------------
    // maven calistirma
    // ------------------------------------------------------------------

    /**
     * Tek bir mvn alt sureci calistirir ve tum ciktiyi toplar.
     * compile() ve runTests() bu metodu paylasir; daha once govde ikisinde de kopyaydi.
     */
    private CompileResult run(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(mvn());
        for (String a : args) command.add(a);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);   // stderr'i de yakala

        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome);
        }

        Process p = pb.start();

        StringBuilder sb = new StringBuilder();
        // Bu dongu yalnizca veri toplamiyor: cikti okunmazsa isletim sistemi
        // tamponu doluyor ve alt surec askida kaliyor.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }

        return new CompileResult(p.waitFor() == 0, sb.toString());
    }

    public CompileResult compile() throws Exception {
        return run("-q", "test-compile");
    }

    public CompileResult runTests(String testClass) throws Exception {
        return run("test", "-Dtest=" + testClass, "-Dsurefire.failIfNoSpecifiedTests=false");
    }

    // ------------------------------------------------------------------
    // cikti ayristirma
    // ------------------------------------------------------------------

    /**
     * Derleme + test TEK mvn cagrisinda.
     *
     * "mvn test" yasam dongusu zaten test-compile'i iceriyor; ayri bir
     * test-compile cagrisi ayni derlemeyi IKI KEZ yaptiriyordu. Tek cagriya
     * inmek Maven surec sayisini yariya indiriyor ve davranisi degistirmiyor:
     * derleme duserse surefire zaten hic kosmuyor.
     *
     * -q KULLANILMIYOR: surefire'in "Tests run:" satirlari INFO seviyesinde
     * basiliyor, -q onlari susturur ve parseCounts sifir okur.
     */
    public CompileResult compileAndTest(String testClass) throws Exception {
        return run("test", "-Dtest=" + testClass, "-Dsurefire.failIfNoSpecifiedTests=false");
    }

    /**
     * Cikti derleme hatasi mi tasiyor, test hatasi mi?
     *
     * maven-compiler-plugin derleme hatasinda "COMPILATION ERROR" basligini
     * basiyor. Ikinci kosul emniyet kemeri: build dustu ama hic test
     * kosmadiysa sebep derlemedir.
     */
    public static boolean isCompileFailure(String output) {
        return output.contains("COMPILATION ERROR")
                || (output.contains("BUILD FAILURE") && !output.contains("Tests run:"));
    }

    public static String errorsOnly(String output) {
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\n")) {
            if (line.startsWith("[ERROR] Failed to execute goal")) break;
            if (line.startsWith("[ERROR]")) {
                sb.append(line.substring(7).trim()).append("\n");
            } else if (!line.isBlank() && Character.isWhitespace(line.charAt(0))) {
                sb.append("  ").append(line.trim()).append("\n");
            }
        }

        // Maven javac hatasi degil de eklenti duzeyinde hata verdiginde
        // ("Failed to execute goal ... Fatal error compiling") ilk satirda break
        // ediliyor ve geriye hicbir sey kalmiyordu. Bos donmek yerine ham
        // ciktinin son satirlarini ver, yoksa konsolda sebep hic gorunmuyor.
        if (sb.toString().isBlank()) {
            return tail(output, 25);
        }

        return sb.toString();
    }

    /** Ham ciktinin son n bos olmayan satiri. */
    private static String tail(String output, int n) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) lines.add(line);
        }
        int from = Math.max(0, lines.size() - n);

        StringBuilder sb = new StringBuilder("(ayristirilamayan cikti - son ")
                .append(lines.size() - from).append(" satir)\n");
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    public static String failuresOnly(String output) {
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : output.split("\n")) {
            if (line.startsWith("[ERROR] Failures:") || line.startsWith("[ERROR] Errors:")) in = true;
            if (!in) continue;
            if (line.startsWith("[ERROR] Tests run:")) break;
            sb.append(line.replaceFirst("^\\[ERROR\\]\\s?", "")).append("\n");
        }
        return sb.toString();
    }

    public static int[] parseCounts(String output) {
        var m = java.util.regex.Pattern
                .compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)")
                .matcher(output);
        int run = 0, passed = 0;
        while (m.find()) {
            run = Integer.parseInt(m.group(1));
            passed = run - Integer.parseInt(m.group(2))
                    - Integer.parseInt(m.group(3))
                    - Integer.parseInt(m.group(4));
        }
        return new int[]{run, passed};
    }

    /**
     * Derleyici hatalarini DOSYA ADINA gore ayirir.
     *
     * javac ilk hatada durmuyor, tum bozuk dosyalari adiyla raporluyor.
     * Sinif bazli dogrulamada suclulari bulup kenara almanin tek yolu bu.
     *
     * Anahtar: dosya adinin uzantisiz hali (Foo_bar_GenTest).
     * Deger: o dosyaya ait hata satirlari.
     */
    public static java.util.Map<String, String> errorsByFile(String output) {
        var map = new java.util.LinkedHashMap<String, StringBuilder>();

        // [ERROR] /yol/Foo_bar_GenTest.java:[12,5] cannot find symbol
        var head = java.util.regex.Pattern.compile(
                "\\[ERROR\\]\\s+.*?([\\w$]+)\\.java:\\[(\\d+),(\\d+)\\]\\s*(.*)");

        StringBuilder current = null;

        for (String line : output.split("\n")) {
            // Eklenti duzeyinde hata: bundan sonrasi javac ciktisi degil.
            if (line.startsWith("[ERROR] Failed to execute goal")) break;

            var m = head.matcher(line);
            if (m.matches()) {
                current = map.computeIfAbsent(m.group(1), k -> new StringBuilder());
                current.append(m.group(1)).append(".java:[").append(m.group(2))
                        .append(",").append(m.group(3)).append("] ")
                        .append(m.group(4)).append("\n");
                continue;
            }

            // "  symbol: class Baz" gibi devam satirlari bir onceki dosyaya ait.
            if (current != null && line.startsWith("[ERROR]")) {
                String rest = line.substring(7).trim();
                if (!rest.isEmpty()) current.append("  ").append(rest).append("\n");
            }
        }

        var out = new java.util.LinkedHashMap<String, String>();
        map.forEach((k, v) -> out.put(k, v.toString()));
        return out;
    }

    /**
     * Surefire sonuclarini TEST SINIFINA gore ayirir.
     *
     * Surefire her sinif icin ayri bir ozet satiri basiyor ve satirin sonunda
     * "-- in com.x.FooGenTest" ile hangi sinif oldugunu soyluyor. Tek mvn
     * cagrisinin ciktisini metotlara dagitmanin dayanagi bu.
     *
     * Deger: {kosan, gecen}.
     */
    public static java.util.Map<String, int[]> countsByClass(String output) {
        var out = new java.util.LinkedHashMap<String, int[]>();

        var p = java.util.regex.Pattern.compile(
                "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)"
                        + "[^\\n]*?-+\\s*in\\s+([\\w.$]+)");

        var m = p.matcher(output);
        while (m.find()) {
            int run      = Integer.parseInt(m.group(1));
            int failures = Integer.parseInt(m.group(2));
            int errors   = Integer.parseInt(m.group(3));
            int skipped  = Integer.parseInt(m.group(4));
            int passed   = run - failures - errors - skipped;

            String fqn = m.group(5);
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            out.put(simple, new int[]{run, passed, skipped});
        }
        return out;
    }

    public void deleteTest(String packageName, String className) throws IOException {
        Files.deleteIfExists(projectRoot.resolve("src/test/java")
                .resolve(packageName.replace('.', '/'))
                .resolve(className + ".java"));
    }

    public static java.util.Set<String> failingMethods(String output) {
        var names = new java.util.LinkedHashSet<String>();
        var m = java.util.regex.Pattern.compile("GenTest\\.(\\w+)")
                .matcher(failuresOnly(output));
        while (m.find()) names.add(m.group(1));
        return names;
    }
}