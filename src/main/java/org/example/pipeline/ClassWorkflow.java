package org.example.pipeline;

import org.example.runner.MethodResult;
import org.example.runner.TestRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BIR SINIFIN tum metotlarinin dogrulama <-> onarim dongusu.
 *
 * MethodWorkflow'dan farki: bir tur = bir mvn cagrisi, metot basina degil
 * SINIF basina. 15 metotluk bir sinif eskiden en az 15 cagri harciyordu,
 * simdi tur basina 1.
 *
 * NEDEN CALISIYOR: her metodun testi AYRI bir dosya (Sinif_metot_GenTest),
 * yani javac hatalari dosya adiyla, surefire sonuclari test sinifi adiyla
 * geliyor. Tek cagrinin ciktisi metotlara birebir dagitilabiliyor.
 *
 * HATA IZOLASYONU:
 *   - TEST hatasi izole: surefire sinif basina ayri sayi veriyor.
 *   - DERLEME hatasi bulasici: bir dosya derlenmezse hicbiri kosamaz.
 *     javac tum suclulari adiyla raporluyor -> hepsini birden kenara alip
 *     kalanlar icin TEK ek tur kosuyoruz.
 *
 * Metot basina defter (bestCode, seenFeedback) korunuyor; degisen yalnizca
 * tur sayacinin sinif turunu surmesi.
 */
public class ClassWorkflow {

    private static final int MAX_ROUNDS = 4;

    /** Onarim cagrilarinda es zamanlilik. TestGenerationStage ile ayni mantik. */
    private static final int PARALLELISM = 8;

    private final GenerationStage generation;
    private final ValidationStage validation;

    public ClassWorkflow(GenerationStage generation, ValidationStage validation) {
        this.generation = generation;
        this.validation = validation;
    }

    /** Tek bir metodun tur boyunca tasidigi durum. */
    private static final class State {
        final Draft draft;
        String code;
        String feedback;              // son turdan gelen hata metni
        boolean compileError;         // feedback derleyici hatasi mi
        String bestCode;
        int bestPassed = -1, bestRun = 0, bestRound = 0;
        final Set<String> seen = new HashSet<>();
        boolean done;                 // yesil, writeFinal yapildi
        MethodResult result;

        State(Draft draft) {
            this.draft = draft;
            this.code  = draft.code();
        }

        String pkg()  { return draft.service().packageName(); }
        String test() { return draft.key().testFileName(); }
        String label(){ return draft.key().label(); }
    }

    /**
     * @param className hedef sinif adi; -Dtest deseni bundan uretiliyor
     * @return metot basina sonuc, drafts ile ayni sirada
     */
    public List<MethodResult> run(TestRunner runner,
                                  String className,
                                  List<Draft> drafts,
                                  StringBuilder failureLog) throws Exception {

        var states = new ArrayList<State>();
        for (Draft d : drafts) states.add(new State(d));

        // Uretim asamasinda dusenler dogrulamaya hic girmiyor.
        for (State s : states) {
            if (!s.draft.generated()) {
                System.out.println("  " + s.label() + ": üretilemedi - " + s.draft.error());
                failureLog.append("\n=== ").append(s.label())
                        .append("  URETILEMEDI\n").append(s.draft.error()).append("\n");
                s.result = fail(s, false);
                s.done   = true;
            }
        }

        String pattern = className + "_*_GenTest";

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            List<State> pending = states.stream().filter(s -> !s.done).toList();
            if (pending.isEmpty()) break;

            System.out.println("\n--- tur " + round + " (" + pending.size() + " metot)");

            for (State s : pending) runner.writeTest(s.pkg(), s.test(), s.code);

            var outcome = runner.compileAndTest(pattern);
            List<State> alive = pending;

            if (TestRunner.isCompileFailure(outcome.output())) {
                var errors = TestRunner.errorsByFile(outcome.output());

                var broken = pending.stream()
                        .filter(s -> errors.containsKey(s.test()))
                        .toList();

                if (broken.isEmpty()) {
                    // Suclu bulunamadi: hepsini derlenmemis say, tur bitti.
                    System.out.println("  derleme kırıldı, suçlu tespit edilemedi");
                    for (State s : pending) {
                        s.compileError = true;
                        s.feedback = TestRunner.errorsOnly(outcome.output());
                    }
                    alive = List.of();
                } else {
                    for (State s : broken) {
                        s.compileError = true;
                        s.feedback = errors.get(s.test());
                        System.out.println("  " + s.label() + ": derlenmedi");
                        failureLog.append("\n=== ").append(s.label())
                                .append("  DERLENMEDI (tur ").append(round).append(")\n")
                                .append(s.feedback);
                        // Kenara al: kalanlar derlenebilsin.
                        runner.deleteTest(s.pkg(), s.test());
                    }

                    alive = pending.stream().filter(s -> !broken.contains(s)).toList();

                    // Kalanlar icin TEK ek tur.
                    if (!alive.isEmpty()) {
                        outcome = runner.compileAndTest(pattern);
                        if (TestRunner.isCompileFailure(outcome.output())) {
                            System.out.println("  ikinci derleme de kırıldı, tur atlandı");
                            alive = List.of();
                        }
                    }
                }
            }

            if (!alive.isEmpty()) {
                distribute(runner, TestRunner.countsByClass(outcome.output()),
                        alive, round, failureLog);
            }

            // Hala biten yoksa ve tur hakki kaldiysa onarim (paralel).
            List<State> toRepair = states.stream()
                    .filter(s -> !s.done && s.feedback != null)
                    .filter(s -> s.seen.add(s.feedback))
                    .toList();

            if (round == MAX_ROUNDS || toRepair.isEmpty()) break;
            repairAll(toRepair);
        }

        // ---------- kapanis ----------
        for (State s : states) {
            if (s.done) continue;

            if (s.bestCode != null) {
                String finalCode = validation.writeFinal(runner, s.pkg(), s.test(), s.bestCode);

                if (s.bestPassed < s.bestRun) {
                    int q = validation.quarantine(runner, s.pkg(), s.test(), finalCode,
                            s.label(), s.bestPassed, s.bestRun, failureLog);
                    System.out.println("  " + s.label() + ": " + q + " test karantinaya alındı");
                }
                s.result = new MethodResult(s.draft.key().className(), s.draft.key().display(),
                        true, true, s.bestRound, s.bestRun, s.bestPassed, s.draft.promptLength());
            } else {
                runner.deleteTest(s.pkg(), s.test());
                s.result = fail(s, true);
            }
        }

        var out = new ArrayList<MethodResult>();
        for (State s : states) out.add(s.result);
        return out;
    }

    /** Tek kosunun sinif basina sonuclarini metotlara dagitir. */
    private void distribute(TestRunner runner,
                            Map<String, int[]> counts,
                            List<State> alive,
                            int round,
                            StringBuilder failureLog) throws Exception {

        for (State s : alive) {
            int[] c = counts.get(s.test());

            if (c == null) {
                // Derlendi ama surefire bu sinifi hic kosmadi.
                System.out.println("  " + s.label() + ": test koşulmadı");
                s.feedback = "Test sinifi hic kosmadi; @Test isaretli metot yok olabilir.";
                s.compileError = false;
                continue;
            }

            int run = c[0], passed = c[1];

            if (passed > s.bestPassed) {
                s.bestPassed = passed;
                s.bestRun    = run;
                s.bestCode   = s.code;
                s.bestRound  = round - 1;
            }

            if (run > 0 && passed == run) {
                System.out.println("  " + s.label() + ": " + passed + "/" + run + " ✓");
                validation.writeFinal(runner, s.pkg(), s.test(), s.code);
                s.result = new MethodResult(s.draft.key().className(), s.draft.key().display(),
                        true, true, round - 1, run, passed, s.draft.promptLength());
                s.done = true;
                continue;
            }

            System.out.println("  " + s.label() + ": " + passed + "/" + run);
            s.feedback = "Tests run: " + run + ", passed: " + passed;
            s.compileError = false;
            failureLog.append("\n=== ").append(s.label())
                    .append("  (").append(passed).append("/").append(run).append(")\n");
        }
    }

    /**
     * Onarim cagrilari paralel: her metot bagimsiz, aralarinda bilgi akisi yok.
     * Dogrulama tek seritli kalmaya devam ediyor.
     */
    private void repairAll(List<State> states) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            var futures = new LinkedHashMap<State, Future<String>>();

            for (State s : states) {
                futures.put(s, pool.submit(() -> {
                    if (s.compileError) {
                        return generation.repairCompile(
                                s.draft.prompt(), s.code, s.feedback, s.pkg(), s.test());
                    }
                    var r = generation.repairTests(
                            s.draft.prompt(), s.code, s.feedback, s.pkg(), s.test());
                    if (!r.patched()) s.seen.add(r.adjustedFeedback());
                    return r.code();
                }));
            }

            for (var e : futures.entrySet()) {
                try {
                    e.getKey().code = e.getValue().get();
                } catch (Exception ex) {
                    System.out.println("  " + e.getKey().label()
                            + ": onarım çağrısı düştü - " + ex.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private MethodResult fail(State s, boolean attempted) {
        return new MethodResult(s.draft.key().className(), s.draft.key().display(),
                attempted, false, attempted ? MAX_ROUNDS : 0, 0, 0, s.draft.promptLength());
    }
}