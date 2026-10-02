package org.example.pipeline;

import org.example.postprocess.TestQuarantiner;
import org.example.postprocess.UnusedCleaner;
import org.example.runner.TestRunner;

/**
 * Dogrulayan taraf. DONGU KURMAZ, model cagirmaz, onarim istemez.
 * Kod alir; derler, kosar, ne oldugunu soyler.
 *
 * Uretim ile arasindaki tek bag Outcome.feedback: derlenmediyse derleyici
 * hatalari, derlendiyse kirik test ciktisi.
 *
 * RunContext BILMIYOR: ihtiyaci olan her sey parametre olarak geliyor
 * (TestRunner ve karantinada failureLog). Boylece bu sinif kosu durumundan
 * tamamen bagimsiz - tek basina cagrilabilir ve tek basina test edilebilir.
 */
public class ValidationStage {

    private final TestQuarantiner quarantiner = new TestQuarantiner();
    private final UnusedCleaner unusedCleaner = new UnusedCleaner();

    /** Tek bir denemenin sonucu. */
    public record Outcome(boolean compiled, String feedback, int run, int passed) { }

    public Outcome validate(TestRunner runner, String pkg, String test, String code) throws Exception {
        runner.writeTest(pkg, test, code);

        // Tek cagri: derleme ve test bir arada.
        var result = runner.compileAndTest(test);

        if (TestRunner.isCompileFailure(result.output())) {
            return new Outcome(false, TestRunner.errorsOnly(result.output()), 0, 0);
        }

        int[] counts = TestRunner.parseCounts(result.output());
        return new Outcome(true, TestRunner.failuresOnly(result.output()), counts[0], counts[1]);
    }

    /**
     * Nihai dosyayi yazarken kullanilmayan importlari ayikla.
     * Mock ayiklama burada YAPILMAZ: @InjectMocks constructor enjeksiyonu
     * kullaniyorsa testte referans edilmeyen bir mock yine de servise gecirilen
     * argumandir. O temizlik yalnizca birlestirmede yapilir, cunku orada sonuc
     * tekrar kosuluyor ve basarisizlikta geri aliniyor.
     */
    public String writeFinal(TestRunner runner, String pkg, String test, String code) throws Exception {
        String cleaned = unusedCleaner.clean(code);
        if (unusedCleaner.removedImports() > 0) {
            System.out.println("  " + unusedCleaner.removedImports() + " kullanılmayan import silindi");
        }
        runner.writeTest(pkg, test, cleaned);
        return cleaned;
    }

    /**
     * Kirik testleri @Disabled ile isaretle.
     *
     * Karantina, kirik testlerin TAMAMINI yakalamazsa "kirik paket teslim
     * edilmesin" garantisi bozulur. Sessizce gecmesin.
     *
     * failureLog artik parametre: bu sinifin RunContext'e uzanan son bagiydi.
     *
     * @return karantinaya alinan test sayisi
     */
    public int quarantine(TestRunner runner, String pkg, String test, String code,
                          String label, int passed, int run,
                          StringBuilder failureLog) throws Exception {

        var finalResult = runner.compileAndTest(test);
        var failing = TestRunner.failingMethods(finalResult.output());

        int expected = run - passed;
        if (failing.size() != expected) {
            System.out.println("  UYARI: " + expected + " kırık test bekleniyordu, "
                    + failing.size() + " tespit edildi");
        }

        runner.writeTest(pkg, test,
                quarantiner.disable(code, failing,
                        "Otomatik üretildi, doğrulanamadı - gözden geçirilmeli"));

        failureLog.append("\n=== ").append(label)
                .append("  (").append(passed).append("/").append(run).append(")\n")
                .append(TestRunner.failuresOnly(finalResult.output()));

        return failing.size();
    }
}