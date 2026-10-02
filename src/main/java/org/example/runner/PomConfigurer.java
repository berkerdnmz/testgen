package org.example.runner;

import org.example.analyzer.TargetSelector;

import java.nio.file.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PomConfigurer {

    private static final Pattern TARGET_CLASSES =
            Pattern.compile("<targetClasses>.*?</targetClasses>", Pattern.DOTALL);
    private static final Pattern TARGET_TESTS =
            Pattern.compile("<targetTests>.*?</targetTests>", Pattern.DOTALL);

    private final Path pom;
    private String original;

    public PomConfigurer(Path projectRoot) {
        this.pom = projectRoot.resolve("pom.xml");
    }

    public boolean configure(String targetPackage) throws Exception {
        return configure(List.of(TargetSelector.ofPackage(targetPackage)));
    }

    /**
     * PIT'i bizim hedeflerimizle calisacak sekilde ayarlar.
     *
     * IKI DURUM VAR:
     *
     * 1) pom'da pitest yok  -> eklentiyi bizim hedeflerimizle ekleriz.
     *
     * 2) pom'da pitest VAR  -> eklentiye dokunmadan yalnizca targetClasses ve
     *    targetTests degerlerini bizimkilerle degistiririz.
     *
     * (2) numarali durum olculdu ve sessiz bir yanlis olcume yol aciyordu:
     * yatay-gecis'in pom'unda elle yazilmis bir pitest blogu var ve hedefi
     * "com.university.transfer.service.*". Onceden "pitest zaten var" deyip
     * dokunmuyorduk; tek bir model sinifi secildiginde PIT yine service
     * siniflarini mutasyona ugratiyor, bizim filtre hicbirini tutmuyor ve
     * "mutant uretilmedi" yaziyorduk. Daha kotusu: yatay-gecis'in TUM PIT
     * sayilari bizim konfigurasyonumuzdan degil o bloktan geliyordu.
     *
     * restore() zaten orijinal pom'u geri yaziyor, dolayisiyla hedef projenin
     * kendi ayari kalici olarak bozulmuyor.
     */
    public boolean configure(List<TargetSelector> targets) throws Exception {
        if (!Files.exists(pom)) return false;
        if (targets.isEmpty()) return false;

        original = Files.readString(pom);

        StringBuilder classes = new StringBuilder();
        StringBuilder tests   = new StringBuilder();
        for (TargetSelector t : targets) {
            classes.append("<param>").append(t.pitClasses()).append("</param>");
            tests.append("<param>").append(t.pitTests()).append("</param>");
        }

        String updated = original.contains("pitest-maven")
                ? overrideTargets(original, classes.toString(), tests.toString())
                : insertPlugin(original, classes.toString(), tests.toString());

        if (updated == null) return false;

        Files.writeString(pom, updated);
        return true;
    }

    /** Mevcut pitest blogundaki hedefleri bizimkilerle degistirir. */
    private String overrideTargets(String pom, String classes, String tests) {
        Matcher mc = TARGET_CLASSES.matcher(pom);
        Matcher mt = TARGET_TESTS.matcher(pom);

        if (!mc.find() || !mt.find()) {
            System.out.println("UYARI: pom.xml'de pitest var ama targetClasses/targetTests "
                    + "bulunamadı. Ölçüm hedef projenin kendi ayarıyla yapılacak.");
            return pom;
        }

        System.out.println("pom.xml'de mevcut pitest yapılandırması bulundu, "
                + "hedefleri bu koşu için geçici olarak değiştirildi.");

        String out = TARGET_CLASSES.matcher(pom)
                .replaceFirst(Matcher.quoteReplacement("<targetClasses>" + classes + "</targetClasses>"));
        out = TARGET_TESTS.matcher(out)
                .replaceFirst(Matcher.quoteReplacement("<targetTests>" + tests + "</targetTests>"));
        return out;
    }

    /** Eklenti hic yoksa bizimkini ekler. */
    private String insertPlugin(String pom, String classes, String tests) {
        String plugin = """
                    <plugin>
                      <groupId>org.pitest</groupId>
                      <artifactId>pitest-maven</artifactId>
                      <version>1.17.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>org.pitest</groupId>
                          <artifactId>pitest-junit5-plugin</artifactId>
                          <version>1.2.1</version>
                        </dependency>
                      </dependencies>
                      <configuration>
                        <targetClasses>%s</targetClasses>
                        <targetTests>%s</targetTests>
                        <skipFailingTests>true</skipFailingTests>
                        <timestampedReports>false</timestampedReports>
                        <outputFormats><param>XML</param><param>HTML</param></outputFormats>
                      </configuration>
                    </plugin>
                """.formatted(classes, tests);

        int at = pom.lastIndexOf("</plugins>");
        if (at >= 0) {
            return new StringBuilder(pom).insert(at, plugin).toString();
        }

        int end = pom.lastIndexOf("</project>");
        if (end < 0) return null;

        return new StringBuilder(pom)
                .insert(end, "  <build>\n    <plugins>\n" + plugin + "    </plugins>\n  </build>\n")
                .toString();
    }

    /** Orijinal pom.xml'i geri yazar. */
    public void restore() throws Exception {
        if (original != null) {
            Files.writeString(pom, original);
            original = null;
        }
    }
}