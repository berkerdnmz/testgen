package org.example.pipeline;

import org.example.analyzer.ServiceInfo;
import org.example.postprocess.TestMerger;
import org.example.postprocess.UnusedCleaner;
import org.example.runner.TestRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Metot bazli test dosyalarini sinif bazli tek dosyada birlestirir.
 *
 * TOPLU CALISIYOR: once tum siniflar bellekte birlestiriliyor, sonra TEK
 * dogrulama turu kosuyor. Onceden sinif basina ayri derleme+test vardi ve
 * olculdu: sinif basina ~45-50 saniye, 8 sinif icin ~6 dakika.
 *
 * NEDEN PARALEL DEGIL: iki mvn sureci ayni proje dizininde calisamaz -
 * ikisi de target/ uzerine yazar, surefire raporlari birbirini ezer. Toplu
 * yapmak zaten paralelden iyi sonuc veriyor: 8 cagri yerine 1.
 *
 * HATA IZOLASYONU:
 *   - TEST hatasi izole. Surefire sinif basina ayri sayi veriyor, yalnizca
 *     duseni geri aliyoruz, ek tur gerekmiyor.
 *   - DERLEME hatasi bulasici. Bir birlesik dosya derlenmezse hicbiri
 *     kosamaz. javac tum suclulari adiyla raporluyor; hepsini birden geri
 *     alip TEK ek tur kosuyoruz.
 */
public class MergeStage {

    /** Derleme hatasi turu; sonsuz donguye karsi ust sinir. */
    private static final int MAX_ROUNDS = 3;

    /** Birlestirilmis ama henuz dogrulanmamis bir sinif. */
    private record Merged(ServiceInfo service,
                          String name,
                          List<Path> methodFiles,
                          List<String> methodSources) { }

    public void run(RunConfig config, String jdkHome, List<ServiceInfo> services) throws Exception {
        var runner = new TestRunner(config.projectPath(), jdkHome);

        // ---------- 1. hepsini bellekte birlestir ----------
        List<Merged> merged = new ArrayList<>();
        for (ServiceInfo service : services) {
            Merged m = prepare(config, runner, service);
            if (m != null) merged.add(m);
        }

        if (merged.isEmpty()) {
            System.out.println("\nBirleştirilecek sınıf yok.");
            return;
        }

        // ---------- 2. tek dogrulama turu (gerekirse tekrar) ----------
        System.out.println("\n" + merged.size() + " sınıf birleştirildi, doğrulanıyor...");

        for (int round = 1; round <= MAX_ROUNDS && !merged.isEmpty(); round++) {
            var result = runner.compileAndTest("*GenTest");

            if (TestRunner.isCompileFailure(result.output())) {
                Set<String> broken = brokenClasses(result.output(), merged);

                if (broken.isEmpty()) {
                    // Suclu tespit edilemedi: guvenli taraf, hepsini geri al.
                    System.out.println("  Derleme kırıldı, suçlu tespit edilemedi - tümü geri alındı");
                    for (Merged m : merged) rollback(runner, m);
                    return;
                }

                for (Merged m : List.copyOf(merged)) {
                    if (broken.contains(m.name())) {
                        System.out.println("  " + m.name() + " derlenmedi, metot bazlı dosyalara dönüldü");
                        rollback(runner, m);
                        merged.remove(m);
                    }
                }
                continue;   // kalanlari yeniden dogrula
            }

            // Derleme temiz: test sonuclarini sinif basina dagit.
            var counts = TestRunner.countsByClass(result.output());

            for (Merged m : merged) {
                int[] c = counts.get(m.name());

                if (c == null) {
                    System.out.println("  " + m.name() + " sonuç bulunamadı, metot bazlı dosyalara dönüldü");
                    rollback(runner, m);
                    continue;
                }

                int run = c[0], passed = c[1], skipped = c[2];
                int broken = run - passed - skipped;

                if (broken == 0 && run > 0) {
                    String note = skipped > 0 ? " (" + passed + "/" + run + ", " + skipped + " karantinalı)"
                            : " (" + passed + "/" + run + ")";
                    System.out.println("  birleştirildi: " + m.name() + note);
                } else {
                    System.out.println("  " + m.name() + " kırdı (" + broken + " kırık test"
                            + "), metot bazlı dosyalara dönüldü");
                    rollback(runner, m);
                }
            }
            return;
        }

        System.out.println("  " + MAX_ROUNDS + " turda derleme temizlenemedi, kalanlar geri alındı");
        for (Merged m : merged) rollback(runner, m);
    }

    /**
     * Bir sinifin metot dosyalarini birlestirir, birlesigi diske yazar ve
     * metot dosyalarini siler. Dogrulama YAPMAZ - o toplu turda.
     *
     * @return birlestirilemezse null (metot bazli dosyalar korunur)
     */
    private Merged prepare(RunConfig config, TestRunner runner, ServiceInfo service) throws Exception {
        var dir = config.projectPath().resolve("src").resolve("test").resolve("java")
                .resolve(service.packageName().replace('.', '/'));
        if (!Files.exists(dir)) return null;

        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString()
                            .matches(service.className() + "_.*_GenTest\\.java"))
                    .sorted().toList();
        }
        if (files.size() < 2) return null;

        var sources = new ArrayList<String>();
        for (Path f : files) sources.add(Files.readString(f));

        String mergedName = service.className() + "GenTest";

        var merger = new TestMerger();
        String code = merger.merge(sources, service.packageName(), mergedName);

        if (code == null) {
            System.out.println("  " + service.className() + ": birleştirme atlandı ("
                    + merger.lastFailureReason() + "), metot bazlı dosyalar korundu");
            return null;
        }

        // Mock ayiklama yalnizca burada guvenli: sonuc kosuluyor ve
        // basarisizlikta metot bazli dosyalara geri donuluyor.
        var cleaner = new UnusedCleaner();
        code = cleaner.clean(code, true);
        if (cleaner.removedMocks() > 0 || cleaner.removedImports() > 0) {
            System.out.println("  " + mergedName + ": " + cleaner.removedMocks()
                    + " mock, " + cleaner.removedImports() + " import silindi");
        }

        for (Path f : files) Files.delete(f);
        runner.writeTest(service.packageName(), mergedName, code);

        return new Merged(service, mergedName, files, sources);
    }

    /** Birlesigi sil, metot bazli dosyalari geri yaz. */
    private void rollback(TestRunner runner, Merged m) throws Exception {
        runner.deleteTest(m.service().packageName(), m.name());
        for (int i = 0; i < m.methodFiles().size(); i++) {
            Files.writeString(m.methodFiles().get(i), m.methodSources().get(i));
        }
    }

    /** Derleyici ciktisindaki dosya adlarindan hangi birlesik siniflar suclu. */
    private Set<String> brokenClasses(String output, List<Merged> merged) {
        var names = new LinkedHashSet<String>();
        var errors = TestRunner.errorsByFile(output);

        for (Merged m : merged) {
            if (errors.containsKey(m.name())) names.add(m.name());
        }
        return names;
    }
}