package org.example;

import org.example.analyzer.TargetSelector;
import org.example.llm.OpenRouterClient;
import org.example.llm.RetryingLlmClient;
import org.example.llm.TokenUsage;
import org.example.pipeline.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Argüman ayrıştırma ve aşamaların bağlanması (composition root).
 *
 * Aşamalar birbirini tanımıyor: her biri girdisini parametre olarak alıyor,
 * çıktısını döndürüyor. Sırayı ve veri akışını yalnızca burası biliyor.
 *
 * --stage= ile aşamalar tek tek koşulabiliyor. Çalıştırılmayan bir aşamanın
 * çıktısı RunStore'dan okunuyor; o da yoksa ne yapılması gerektiği söyleniyor.
 */
public class Main {

    private static final String MODEL = "openai/gpt-oss-120b";

    private static final List<String> ALL_STAGES =
            List.of("analyze", "context", "generate", "merge", "report", "measure");

    public static void main(String[] args) throws Exception {
        boolean force = false;
        String projectArg = null;
        String targetArg  = null;
        var stages = new LinkedHashSet<String>();

        for (String a : args) {
            if (a.equals("--force")) {
                force = true;
            } else if (a.startsWith("--stage=")) {
                for (String s : a.substring("--stage=".length()).split(",")) {
                    String name = s.trim().toLowerCase();
                    if (name.isEmpty()) continue;
                    if (!ALL_STAGES.contains(name)) {
                        System.out.println("Bilinmeyen aşama: " + name);
                        System.out.println("Geçerli aşamalar: " + String.join(", ", ALL_STAGES));
                        return;
                    }
                    stages.add(name);
                }
            } else if (a.startsWith("--")) {
                System.out.println("Bilinmeyen argüman: " + a);
                usage();
                return;
            } else if (projectArg == null) {
                projectArg = a;
            } else if (targetArg == null) {
                targetArg = a;
            } else {
                System.out.println("Fazladan argüman: " + a);
                usage();
                return;
            }
        }

        if (stages.isEmpty()) stages.addAll(ALL_STAGES);

        Path project = (projectArg == null)
                ? PackagePicker.pickProject()
                : Paths.get(projectArg);

        if (project == null) {
            System.out.println("Proje seçilmedi, çıkılıyor.");
            System.exit(0);
        }

        List<TargetSelector> targets = (targetArg == null)
                ? PackagePicker.pickTargets(project)
                : parseTargets(targetArg);

        if (targets.isEmpty()) {
            System.out.println("Hedef seçilmedi, çıkılıyor.");
            System.exit(0);
        }

        System.out.println("Proje  : " + project);
        targets.forEach(t -> System.out.println("Hedef  : " + t.label()));
        if (stages.size() < ALL_STAGES.size()) {
            System.out.println("Aşama  : " + String.join(", ", stages));
        }

        long started = System.currentTimeMillis();
        run(RunConfig.of(project, targets, force), stages);
        elapsed("TOPLAM", started);

        // JFileChooser acildiysa AWT is parcacigi JVM'i ayakta tutabiliyor.
        System.exit(0);
    }

    /**
     * Aşama zinciri. Her aşama ya bu koşuda üretiliyor ya diskten okunuyor.
     * rapor proje yolunu bilmiyor, ölçüm sonuçları bilmiyor, baglam LLM'i bilmiyor.
     */
    private static void run(RunConfig config, Set<String> stages) throws Exception {
        var store = new RunStore(config.projectPath());

        // ---------- analiz ----------
        AnalysisStage.Analysis analysis;
        if (stages.contains("analyze")) {
            long t0 = System.currentTimeMillis();
            analysis = new AnalysisStage().run(config);
            if (analysis == null) return;              // sebep konsola yazildi
            store.saveAnalysis(analysis);
            elapsed("analiz", t0);
        } else {
            analysis = store.loadAnalysis();
            if (analysis == null) { missing("analyze"); return; }
            System.out.println("Analiz diskten okundu: "
                    + analysis.services().size() + " sınıf");
        }

        // ---------- bağlam ----------
        Map<MethodKey, MethodContext> contexts = null;
        if (stages.contains("context")) {
            long t0 = System.currentTimeMillis();
            contexts = new ContextStage().extract(config, analysis.services());
            store.saveContexts(contexts);
            elapsed("bağlam", t0);
        }

        // ---------- üretim + doğrulama ----------
        TestGenerationStage.Outcome outcome = null;
        if (stages.contains("generate")) {
            if (contexts == null) {
                contexts = store.loadContexts();
                if (contexts == null) { missing("context"); return; }
                System.out.println("Bağlam diskten okundu: " + contexts.size() + " metot");
            }

            // LLM istemcisi YALNIZCA burada kuruluyor: rapor ya da ölçüm tek
            // başına koşulduğunda OR_KEY tanımlı olması gerekmiyor.
            var tokens     = new TokenUsage();
            var llm        = new RetryingLlmClient(new OpenRouterClient(MODEL, tokens));
            var generation = new GenerationStage(llm);

            // Doğrulama SINIF bazlı: bir tur = bir mvn çağrısı, metot başına
            // değil sınıf başına. MethodWorkflow'un yerini ClassWorkflow aldı.
            var workflow   = new ClassWorkflow(generation, new ValidationStage());

            long t0 = System.currentTimeMillis();
            outcome = new TestGenerationStage(generation, workflow)
                    .run(config, analysis.jdkHome(), analysis.services(), contexts);
            elapsed("üretim + doğrulama", t0);

            store.saveOutcome(outcome);
            store.saveTokens(tokens.snapshot());
        }

        // ---------- birleştirme ----------
        if (stages.contains("merge")) {
            long t0 = System.currentTimeMillis();
            new MergeStage().run(config, analysis.jdkHome(), analysis.services());
            elapsed("birleştirme", t0);
        }

        // ---------- rapor ----------
        if (stages.contains("report")) {
            if (outcome == null) {
                outcome = store.loadOutcome();
                if (outcome == null) { missing("generate"); return; }
            }
            new ReportStage().run(outcome.results(), outcome.failureLog(),
                    store.loadTokens());
        }

        // ---------- ölçüm ----------
        if (stages.contains("measure")) {
            long t0 = System.currentTimeMillis();
            new MeasurementStage().run(config, analysis.jdkHome());
            elapsed("ölçüm", t0);
        }
    }

    /**
     Aşama süresini yazdır
     */
    private static void elapsed(String stage, long startMillis) {
        long sn = (System.currentTimeMillis() - startMillis) / 1000;
        System.out.printf("%n[süre] %-20s %d dk %02d sn%n", stage, sn / 60, sn % 60);
    }

    /** Girdi ne bellekte ne diskte: ne yapılması gerektiğini söyle. */
    private static void missing(String stage) {
        System.out.println("\nDURDURULDU: '" + stage
                + "' aşamasının çıktısı bulunamadı. Önce şunu koşun:");
        System.out.println("  --stage=" + stage);
    }

    /**
     * Virgülle ayrılmış hedefler. Son parçası büyük harfle başlıyorsa sınıf,
     * değilse paket sayılır: com.x.service (paket), com.x.service.Foo (sınıf).
     */
    private static List<TargetSelector> parseTargets(String arg) {
        List<TargetSelector> out = new ArrayList<>();
        for (String raw : arg.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;

            String last = t.substring(t.lastIndexOf('.') + 1);
            boolean looksLikeClass = !last.isEmpty() && Character.isUpperCase(last.charAt(0));

            out.add(looksLikeClass ? TargetSelector.ofClass(t) : TargetSelector.ofPackage(t));
        }
        return out;
    }

    private static void usage() {
        System.out.println("Kullanım: [hedef-proje-yolu] [paket|sınıf[,...]] [--force] [--stage=...]");
        System.out.println();
        System.out.println("Argüman verilmezse proje ve hedefler dosya gezgininden seçilir.");
        System.out.println("Klasör seçilirse paketin tamamı, .java dosyası seçilirse yalnızca o sınıf.");
        System.out.println();
        System.out.println("Aşamalar: " + String.join(", ", ALL_STAGES));
        System.out.println("Verilmezse hepsi koşar. Örnek: --stage=analyze,context");
    }
}