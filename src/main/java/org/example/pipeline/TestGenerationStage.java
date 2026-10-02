package org.example.pipeline;

import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;
import org.example.runner.MethodResult;
import org.example.runner.TestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Üretim aşaması iki dalga halinde koşuyor:
 *
 *   1) ÜRETİM  - tüm metotların ilk taslağı PARALEL üretiliyor. Metotlar
 *      arasında hiçbir bilgi akışı yok (model her metoda sıfırdan başlıyor),
 *      dolayısıyla sıra önemsiz. Darboğaz ağ beklemesi olduğu için çekirdek
 *      sayısı sınır değil; sınır sağlayıcının hız limiti, o yüzden Semaphore.
 *
 *   2) DOĞRULAMA - tek şeritli ve SINIF BAZLI. İki mvn süreci aynı proje
 *      dizininde çalışamaz: ikisi de target/ üzerine yazar, surefire
 *      raporları birbirini ezer. Bu yüzden doğrulama sırayla koşuyor - ama
 *      artık metot başına değil sınıf başına bir mvn çağrısıyla
 *      (bkz. ClassWorkflow).
 */
public class TestGenerationStage {

    /**
     * Ayni anda kac LLM cagrisi. Cekirdek sayisiyla ilgisi yok - hepsi ag
     * bekliyor. Ust sinir saglayicinin hiz limiti; 8 gozlemlenen limitlerin
     * altinda kaliyor. 429 alinmaya baslanirsa dusurulmeli.
     */
    private static final int PARALLELISM = 8;

    private final GenerationStage generation;
    private final ClassWorkflow workflow;

    public TestGenerationStage(GenerationStage generation, ClassWorkflow workflow) {
        this.generation = generation;
        this.workflow   = workflow;
    }

    /** Uretim asamasinin urettigi her sey. */
    public record Outcome(List<MethodResult> results, String failureLog) { }

    /** Uretilecek tek bir hedef. */
    private record Target(ServiceInfo service, MethodInfo method, MethodKey key) { }

    public Outcome run(RunConfig config,
                       String jdkHome,
                       List<ServiceInfo> services,
                       Map<MethodKey, MethodContext> contexts) throws Exception {

        generation.prepare(config.sourceRoot());

        var runner     = new TestRunner(config.projectPath(), jdkHome);
        var results    = new ArrayList<MethodResult>();
        var failureLog = new StringBuilder();

        List<Target> targets = new ArrayList<>();
        for (ServiceInfo service : services) {
            for (MethodInfo method : service.methods()) {
                targets.add(new Target(service, method, MethodKey.of(service, method)));
            }
        }

        // ---------- 1. DALGA: paralel uretim ----------
        List<Draft> drafts = generateAll(targets, contexts);

        // ---------- 2. DALGA: sinif bazli dogrulama ----------
        System.out.println("\n########## DOĞRULAMA ##########");
        for (var e : byClass(drafts).entrySet()) {
            System.out.println("\n########## " + e.getKey()
                    + " (" + e.getValue().size() + " metot) ##########");
            results.addAll(workflow.run(runner, e.getKey(), e.getValue(), failureLog));
        }

        secondPass(runner, targets, contexts, failureLog, results);

        return new Outcome(results, failureLog.toString());
    }

    /**
     * Draft'lari sinifa gore gruplar; sira KORUNUYOR.
     *
     * targets zaten servis servis kuruldugu icin ayni sinifin metotlari
     * bitisik geliyor. Bu sayede donen results listesi targets ile birebir
     * hizali kaliyor - secondPass'in indeks eslestirmesi buna dayaniyor.
     */
    private LinkedHashMap<String, List<Draft>> byClass(List<Draft> drafts) {
        var out = new LinkedHashMap<String, List<Draft>>();
        for (Draft d : drafts) {
            out.computeIfAbsent(d.key().className(), k -> new ArrayList<>()).add(d);
        }
        return out;
    }

    /**
     * Tum taslaklari paralel uretir. Sira korunur: donen liste targets ile
     * birebir ayni sirada, cunku Future'lar sirayla toplaniyor.
     *
     * Tek bir metodun dusmesi digerlerini iptal etmiyor - hata Draft'in
     * icinde tasiniyor ve dogrulama asamasinda raporlaniyor.
     */
    private List<Draft> generateAll(List<Target> targets,
                                    Map<MethodKey, MethodContext> contexts) throws Exception {

        System.out.println("\n########## ÜRETİM (" + targets.size() + " metot, en fazla "
                + PARALLELISM + " eşzamanlı) ##########");

        var limit = new Semaphore(PARALLELISM);
        var done  = new AtomicInteger();
        int total = targets.size();

        List<Draft> drafts = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Draft>> futures = new ArrayList<>();

            for (Target t : targets) {
                futures.add(pool.submit(() -> {
                    limit.acquire();
                    try {
                        return draft(t, contexts.get(t.key()), done, total);
                    } finally {
                        limit.release();
                    }
                }));
            }

            for (Future<Draft> f : futures) drafts.add(f.get());
        }

        long ok = drafts.stream().filter(Draft::generated).count();
        System.out.println("Üretilen taslak: " + ok + "/" + total);

        return drafts;
    }

    /**
     * Tek bir taslak. Paralel kosuyor, bu yuzden konsola TEK bir satir
     * basiyor - cok satirli cikti ic ice girer ve okunmaz hale gelir.
     */
    private Draft draft(Target t, MethodContext context, AtomicInteger done, int total) {
        String pkg  = t.service().packageName();
        String test = t.key().testFileName();

        if (context == null) {
            System.out.printf("  [%d/%d] %s — bağlam bulunamadı%n",
                    done.incrementAndGet(), total, t.key().label());
            return Draft.failed(t.service(), t.method(), t.key(), "baglam bulunamadi");
        }

        try {
            String prompt = generation.buildPrompt(t.service(), t.method(), context);
            String code   = generation.generate(prompt, pkg, test);

            System.out.printf("  [%d/%d] %s — %d karakter (~%d token)%n",
                    done.incrementAndGet(), total, t.key().label(),
                    prompt.length(), prompt.length() / 4);

            return Draft.ok(t.service(), t.method(), t.key(), prompt, code);

        } catch (Exception e) {
            System.out.printf("  [%d/%d] %s — ÜRETİLEMEDİ: %s%n",
                    done.incrementAndGet(), total, t.key().label(), e.getMessage());
            return Draft.failed(t.service(), t.method(), t.key(), String.valueOf(e.getMessage()));
        }
    }

    /**
     * Ilk gecisi hic gecemeyen metotlari bir kez daha dener.
     *
     * Dusme sebebi cogu zaman kalici degil - gecici bir API hatasi ya da
     * modelin o seferlik kotu bir cevabi. Yalnizca DERLENEMEYENLER tekrar
     * denenir; derlenip bazi testleri kirik kalanlar zaten karantinaya
     * alinmis ve kismi degeri var.
     *
     * Dusen metotlar birden fazla sinifa dagilmis olabilir, bu yuzden geri
     * yazma INDEKSE degil ANAHTARA gore yapiliyor: her Draft'in MethodKey'i
     * results icindeki satirini bulmaya yetiyor.
     *
     * Tekrar uretim de PARALEL, dogrulama yine sinif bazli ve sirali.
     */
    private void secondPass(TestRunner runner,
                            List<Target> targets,
                            Map<MethodKey, MethodContext> contexts,
                            StringBuilder failureLog,
                            List<MethodResult> results) throws Exception {

        List<Integer> failed = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).compiled()) failed.add(i);
        }
        if (failed.isEmpty()) return;

        System.out.println("\n########## İKİNCİ GEÇİŞ (" + failed.size() + " düşen metot) ##########");

        List<Target> retryTargets = failed.stream().map(targets::get).toList();
        List<Draft> retryDrafts = generateAll(retryTargets, contexts);

        // anahtar -> results icindeki satir
        var indexOf = new HashMap<MethodKey, Integer>();
        for (int j = 0; j < failed.size(); j++) {
            indexOf.put(retryDrafts.get(j).key(), failed.get(j));
        }

        for (var e : byClass(retryDrafts).entrySet()) {
            System.out.println("\n########## tekrar: " + e.getKey()
                    + " (" + e.getValue().size() + " metot) ##########");

            List<Draft> classDrafts = e.getValue();
            List<MethodResult> retryResults =
                    workflow.run(runner, e.getKey(), classDrafts, failureLog);

            for (int j = 0; j < classDrafts.size(); j++) {
                MethodResult r = retryResults.get(j);
                Integer at = indexOf.get(classDrafts.get(j).key());
                if (at == null) continue;             // olmamali; sessizce atla

                if (r.compiled()) {
                    // Yerinde degistir: sona eklenirse tabloda ayni metot iki kez cikar.
                    results.set(at, r);
                    System.out.println("  " + r.method() + ": ikinci geçişte kurtarıldı");
                } else {
                    System.out.println("  " + r.method() + ": ikinci geçişte de düştü");
                }
            }
        }
    }
}