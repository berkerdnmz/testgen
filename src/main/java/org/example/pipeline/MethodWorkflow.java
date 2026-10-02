package org.example.pipeline;

import org.example.runner.MethodResult;
import org.example.runner.TestRunner;

import java.util.HashSet;
import java.util.Set;

/**
 * TEK metodun DOGRULAMA <-> onarim dongusu.
 *
 * ILK URETIMI ARTIK YAPMIYOR: hazir bir Draft aliyor. Sebep paralellik -
 * ilk uretim tum metotlar icin toplu ve paralel kosuluyor, dogrulama ise
 * tek seritli olmak zorunda (paylasilan src/test/java ve target/).
 * Onarim turlarindaki uretim burada kaliyor: onlar zaten dogrulamayla
 * ic ice, ayrilamaz.
 *
 * bestCode / bestPassed / seenFeedback yalnizca burada yasiyor.
 */
public class MethodWorkflow {

    private static final int MAX_ATTEMPTS = 4;

    private final GenerationStage generation;
    private final ValidationStage validation;

    public MethodWorkflow(GenerationStage generation, ValidationStage validation) {
        this.generation = generation;
        this.validation = validation;
    }

    public MethodResult run(TestRunner runner, Draft draft, StringBuilder failureLog) {

        MethodKey key = draft.key();
        String pkg   = draft.service().packageName();
        String test  = key.testFileName();
        String label = key.label();
        int promptLength = draft.promptLength();

        // Uretim asamasinda dusmus: dogrulanacak bir sey yok.
        if (!draft.generated()) {
            System.out.println("  üretilemedi: " + draft.error());
            failureLog.append("\n=== ").append(label)
                    .append("  URETILEMEDI\n").append(draft.error()).append("\n");
            return new MethodResult(key.className(), key.display(),
                    false, false, 0, 0, 0, promptLength);
        }

        String prompt = draft.prompt();
        String code   = draft.code();

        try {
            String bestCode = null;
            int bestPassed = -1, bestRun = 0, bestRound = 0;
            Set<String> seenFeedback = new HashSet<>();

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                var outcome = validation.validate(runner, pkg, test, code);

                if (!outcome.compiled()) {
                    System.out.println("  tur " + attempt + ": derlenmedi");

                    if (bestCode != null && bestPassed > 0) {
                        System.out.println("  Onarım çalışan sürümü bozdu, geri alındı");
                        break;
                    }

                    failureLog.append("\n=== ").append(label)
                            .append("  DERLENMEDI (tur ").append(attempt).append(")\n")
                            .append(outcome.feedback());
                    System.out.println(outcome.feedback());

                    if (!seenFeedback.add(outcome.feedback()) || attempt == MAX_ATTEMPTS) break;

                    code = generation.repairCompile(prompt, code, outcome.feedback(), pkg, test);
                    continue;
                }

                if (outcome.passed() > bestPassed) {
                    bestPassed = outcome.passed();
                    bestRun    = outcome.run();
                    bestCode   = code;
                    bestRound  = attempt - 1;
                }

                if (outcome.run() > 0 && outcome.passed() == outcome.run()) {
                    System.out.println("  Tur " + attempt + ": "
                            + outcome.passed() + "/" + outcome.run() + " ✓");
                    validation.writeFinal(runner, pkg, test, code);
                    return new MethodResult(key.className(), key.display(),
                            true, true, attempt - 1, outcome.run(), outcome.passed(),
                            promptLength);
                }

                System.out.println("  Tur " + attempt + ": "
                        + outcome.passed() + "/" + outcome.run());

                if (!seenFeedback.add(outcome.feedback()) || attempt == MAX_ATTEMPTS) break;

                var repair = generation.repairTests(prompt, code, outcome.feedback(), pkg, test);
                code = repair.code();
                if (!repair.patched()) {
                    // Tam uretime dusuldu: modele eklenen sozlesme hatirlatmasi
                    // geri bildirim metnini degistirdi, ayni metni bir daha
                    // gondermeyelim.
                    seenFeedback.add(repair.adjustedFeedback());
                }
            }

            if (bestCode != null) {
                bestCode = validation.writeFinal(runner, pkg, test, bestCode);

                if (bestPassed < bestRun) {
                    int quarantined = validation.quarantine(
                            runner, pkg, test, bestCode, label, bestPassed, bestRun,
                            failureLog);
                    System.out.println("  " + quarantined + " test karantinaya alındı");
                }

                return new MethodResult(key.className(), key.display(),
                        true, true, bestRound, bestRun, bestPassed, promptLength);
            }

            runner.deleteTest(pkg, test);
            return new MethodResult(key.className(), key.display(),
                    true, false, MAX_ATTEMPTS, 0, 0, promptLength);

        } catch (Exception e) {
            System.out.println("  Altyapı hatası: " + e.getMessage());
            try { runner.deleteTest(pkg, test); } catch (Exception ignored) { }
            return new MethodResult(key.className(), key.display(),
                    false, false, 0, 0, 0, promptLength);
        }
    }
}