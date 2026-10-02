package org.example.pipeline;

import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;
import org.example.context.TypeIndex;
import org.example.llm.LlmClient;
import org.example.llm.OutputCleaner;
import org.example.postprocess.ImportFixer;
import org.example.postprocess.TestPatcher;
import org.example.prompt.PromptBuilder;
import org.example.prompt.RepairPromptBuilder;
import org.example.prompt.TestRepairPromptBuilder;
import java.nio.file.Path;

/**
 * Kod ureten taraf. DONGU KURMAZ, sonuc degerlendirmez, dosya yazmaz.
 * Her metodu tek bir soru sorup tek bir cevap dondurur.
 *
 * Bu asamanin bilmedigi seyler bilincli: kacinci turda oldugumuz, onceki
 * denemelerin ne oldugu, hangi surumun "en iyi" oldugu. Hepsi MethodWorkflow'da.
 */
public class GenerationStage {

    private final LlmClient llm;

    private final PromptBuilder promptBuilder       = new PromptBuilder();
    private final RepairPromptBuilder repairer      = new RepairPromptBuilder();
    private final TestRepairPromptBuilder testRepair = new TestRepairPromptBuilder();
    private final OutputCleaner cleaner             = new OutputCleaner();
    private final TestPatcher patcher               = new TestPatcher();

    /** ImportFixer proje tiplerini bilmek zorunda; prepare() ile kurulur. */
    private ImportFixer fixer;

    public GenerationStage(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * ContextStage kendi TypeIndex'ini kurup atiyor, burada ikinci kez
     * kuruluyor. Ayni kaynak agacinin iki kez ayristirilmasi demek; bilinen
     * maliyet ve zaten "metin yerine nesne modeli" maddesinin kapsaminda.
     * RunContext'e AST koymamak icin bilincli olarak bu tercih edildi.
     */
    public void prepare(Path sourceRoot) throws Exception {
        fixer = new ImportFixer(new TypeIndex(sourceRoot));
    }

    public String buildPrompt(ServiceInfo service, MethodInfo method, MethodContext c) {
        return promptBuilder.build(service, method,
                c.types(), c.voidMethods(), c.calledBodies(), c.constants());
    }

    /** Ilk uretim. */
    public String generate(String prompt, String pkg, String test) {
        return postProcess(llm.complete(prompt), pkg, test);
    }

    /** Derleme hatasi onarimi. */
    public String repairCompile(String prompt, String code, String errors, String pkg, String test) {
        return postProcess(llm.complete(repairer.build(prompt, code, errors)), pkg, test);
    }

    /**
     * Kirik test onarimi.
     *
     * Yama tutmazsa tam uretime dusulur ve modele sozlesme hatirlatilir. O
     * hatirlatma geri bildirim metnini DEGISTIRDIGI icin sonuc adjustedFeedback
     * olarak geri dondurulur - seenFeedback kumesine yazma isini MethodWorkflow
     * yapar. Uretim asamasi dongu durumuna dokunmaz.
     */
    public RepairResult repairTests(String prompt, String code, String failures, String pkg, String test) {
        var raw     = cleaner.clean(llm.complete(testRepair.build(prompt, code, failures)));
        var patched = patcher.apply(code, raw);

        if (patched != null) {
            return new RepairResult(fixer.fix(patched, pkg), failures, true);
        }

        System.out.println("  Yama uygulanamadı, tam üretime düşüldü.");
        String full = fixer.fix(cleaner.normalize(raw, pkg, test), pkg);
        String adjusted = failures + "\n\nNOT: Yalnizca duzeltilmis test metotlarini dondur. "
                + "Tam sinif, aciklama veya yeni test metodu ekleme.";
        return new RepairResult(full, adjusted, false);
    }

    public record RepairResult(String code, String adjustedFeedback, boolean patched) { }

    private String postProcess(String raw, String pkg, String test) {
        return fixer.fix(cleaner.normalize(cleaner.clean(raw), pkg, test), pkg);
    }
}