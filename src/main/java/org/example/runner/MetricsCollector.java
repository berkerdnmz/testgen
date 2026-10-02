package org.example.runner;

import org.example.analyzer.TargetSelector;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class MetricsCollector {

    private static final String JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.12";

    private final Path root;
    private final List<TargetSelector> targets;

    /**
     * Olcum de test uretimi kadar JDK surumune duyarli: JaCoCo ve PIT belirli
     * class file surumlerini desteklemiyor. null ise ust surecin JAVA_HOME'u.
     */
    private final String javaHome;

    public MetricsCollector(Path projectRoot, String targetPackage, String javaHome) {
        this(projectRoot, List.of(TargetSelector.ofPackage(targetPackage)), javaHome);
    }

    public MetricsCollector(Path projectRoot, List<TargetSelector> targets, String javaHome) {
        this.root     = projectRoot;
        this.targets  = List.copyOf(targets);
        this.javaHome = javaHome;
    }

    /**
     * JaCoCo ve PIT'i BIR KEZ kosar, sonra her hedef icin ayri ayri ayristirir.
     *
     * Hedef basina ayri kosu yapilmiyor: PIT tek kosuda dakikalar suruyor ve
     * pom konfigurasyonu zaten tum hedefleri kapsiyor.
     */
    public String collect() throws Exception {
        System.out.println("\nJaCoCo kosuluyor...");
        mvn("clean", JACOCO + ":prepare-agent", "test", JACOCO + ":report",
                "-Djacoco.destFile=" + root.resolve("target/jacoco.exec"),
                "-Djacoco.dataFile=" + root.resolve("target/jacoco.exec"),
                "-Dtest=*GenTest", "-Dsurefire.failIfNoSpecifiedTests=false");

        System.out.println("PIT kosuluyor (birkac dakika surebilir)...");
        mvn("test-compile", "org.pitest:pitest-maven:mutationCoverage");

        StringBuilder out = new StringBuilder();
        for (TargetSelector t : targets) {
            out.append("Hedef: ").append(t.label()).append("\n\n");
            out.append(jacoco(t));
            out.append(pit(t));
            out.append("---------------------------------------\n");
        }

        out.append("\nHTML raporlar:\n")
                .append("  ").append(url("target/site/jacoco/index.html")).append("\n")
                .append("  ").append(url("target/pit-reports/index.html")).append("\n");

        Path file = root.resolve("metrics.txt");
        Files.writeString(file, out.toString());

        System.out.println("\n=============== METRIKLER ===============");
        System.out.print(out);
        System.out.println("Kaydedildi: " + file.toAbsolutePath());

        return out.toString();
    }

    private String jacoco(TargetSelector target) throws Exception {
        Path csv = root.resolve("target/site/jacoco/jacoco.csv");
        if (!Files.exists(csv)) return "JaCoCo raporu bulunamadi.\n";

        int im = 0, ic = 0, bm = 0, bc = 0, lm = 0, lc = 0, mm = 0, mc = 0, classes = 0;

        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",");
            if (c.length < 13) continue;
            // c[1] paket, c[2] sinif adi
            if (!target.matches(c[1], c[2])) continue;
            classes++;
            im += n(c[3]);  ic += n(c[4]);
            bm += n(c[5]);  bc += n(c[6]);
            lm += n(c[7]);  lc += n(c[8]);
            mm += n(c[11]); mc += n(c[12]);
        }

        if (classes == 0) return "JaCoCo: " + target.label() + " icin sinif bulunamadi.\n";

        return "JACOCO (" + classes + " sinif)\n"
                + row("  Satir      ", lc, lm)
                + row("  Dal        ", bc, bm)
                + row("  Komut      ", ic, im)
                + row("  Metot      ", mc, mm)
                + "\n";
    }

    private String pit(TargetSelector target) throws Exception {
        Path xml = root.resolve("target/pit-reports/mutations.xml");
        if (!Files.exists(xml)) return "PIT raporu bulunamadi.\n";

        String content = Files.readString(xml);
        Matcher m = Pattern.compile("<mutation[^>]*status='([A-Z_]+)'[^>]*>(.*?)</mutation>",
                Pattern.DOTALL).matcher(content);

        int total = 0, killed = 0, noCoverage = 0;
        Map<String, int[]> byMutator = new TreeMap<>();
        List<String> survived = new ArrayList<>();

        while (m.find()) {
            String status = m.group(1);
            String block  = m.group(2);

            String mutatedClass = tag(block, "mutatedClass");
            if (!target.matches(mutatedClass)) continue;

            String mutator = tag(block, "mutator");
            mutator = mutator.substring(mutator.lastIndexOf('.') + 1);

            total++;
            int[] counts = byMutator.computeIfAbsent(mutator, k -> new int[2]);
            counts[1]++;

            if (status.equals("KILLED") || status.equals("TIMED_OUT")) {
                killed++;
                counts[0]++;
            } else {
                if (status.equals("NO_COVERAGE")) noCoverage++;
                survived.add("  " + mutatedClass + "."
                        + tag(block, "mutatedMethod") + " satir "
                        + tag(block, "lineNumber") + "  " + mutator + "  [" + status + "]");
            }
        }

        if (total == 0) return "PIT: " + target.label() + " icin mutant uretilmedi.\n";

        StringBuilder sb = new StringBuilder("PIT\n");
        sb.append(row("  Mutation   ", killed, total - killed));
        sb.append("  Kapsanmayan: ").append(noCoverage).append("\n");
        sb.append("  Mutatorler:\n");
        byMutator.forEach((k, v) ->
                sb.append("    ").append(String.format("%-28s %d/%d%n", k, v[0], v[1])));

        if (!survived.isEmpty()) {
            sb.append("  Hayatta kalanlar:\n");
            survived.forEach(s -> sb.append("  ").append(s).append("\n"));
        }
        return sb.append("\n").toString();
    }

    private String row(String label, int covered, int missed) {
        int total = covered + missed;
        int pct = total == 0 ? 100 : covered * 100 / total;
        return label + covered + "/" + total + "  (%" + pct + ")\n";
    }

    private String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">").matcher(block);
        return m.find() ? m.group(1) : "?";
    }

    private String url(String relative) {
        return root.resolve(relative).toUri().toString();
    }

    private int n(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** Windows'ta mvn.cmd, diger sistemlerde mvn. */
    private static String mvnCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "mvn.cmd" : "mvn";
    }

    /**
     * Tek bir mvn alt sureci kosar.
     *
     * Cikti ONCEDEN TAMAMEN ATILIYORDU ve cikis kodu hic kontrol edilmiyordu.
     * PIT hata verse bile konsolda iz kalmiyor, biz de "mutant uretilmedi"
     * yaziyorduk - pom'daki yabanci pitest yapilandirmasini bulmak bu yuzden
     * zor oldu. Artik hata durumunda son satirlar basiliyor.
     *
     * Not: kirik bir test varsa JaCoCo adimi da sifirdan farkli kodla biter ve
     * bu uyari cikar. Yanlis alarm degil - o durumda da bilmek istenir.
     */
    private void mvn(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(mvnCommand());
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);

        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome);
        }

        Process p = pb.start();

        List<String> output = new ArrayList<>();
        // Cikti okunmazsa isletim sistemi tamponu doluyor ve alt surec askida
        // kaliyor; bu dongu yalnizca veri toplamiyor.
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) output.add(line);
        }

        if (p.waitFor() != 0) {
            System.out.println("UYARI: ölçüm komutu hata verdi: mvn " + String.join(" ", args));
            System.out.println("Son satırlar:");
            output.stream()
                    .filter(l -> !l.isBlank())
                    .skip(Math.max(0, output.stream().filter(l -> !l.isBlank()).count() - 15))
                    .forEach(l -> System.out.println("  " + l));
        }
    }
}