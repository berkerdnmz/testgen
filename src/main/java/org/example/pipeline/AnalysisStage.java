package org.example.pipeline;

import org.example.analyzer.JdkLocator;
import org.example.analyzer.ProjectProfile;
import org.example.analyzer.ServiceInfo;
import org.example.analyzer.ServiceScanner;
import org.example.runner.TestRunner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Proje profili, JDK seçimi, hedef projenin ön derlemesi ve sınıf tarama.
 *
 * Saf okuma aşaması: model çağrılmaz, test dosyası yazılmaz.
 * Çıktısı tek bir Analysis nesnesi; koşu devam edemiyorsa null.
 */
public class AnalysisStage {

    /** Bu sayının üstünde metot bulunursa kullanıcıya onay sorulur. */
    private static final int CONFIRM_THRESHOLD = 30;

    /**
     * Analiz aşamasının ürettiği HER ŞEY.
     * jdkHome null olabilir: pom'da Java sürümü yoksa çalışan JVM kullanılır.
     */
    public record Analysis(ProjectProfile profile,
                           String jdkHome,
                           List<ServiceInfo> services) { }

    /** @return null ise koşu durdurulmalı; sebep zaten konsola yazıldı. */
    public Analysis run(RunConfig config) throws Exception {
        var profile = ProjectProfile.of(config.projectPath());
        System.out.println("Hedef proje profili: " + profile.describe());

        String blocker = profile.blocker();
        if (blocker != null && !config.force()) {
            System.out.println("\nDURDURULDU: " + blocker);
            System.out.println("Tespit yanlış ise --force verip devam edebilirsiniz.");
            return null;
        }
        if (blocker != null) System.out.println("UYARI (--force): " + blocker);
        if (profile.warning() != null) System.out.println("UYARI: " + profile.warning());

        // JDK seçimi: iki farklı "boş" durum olduğu için (sürüm belirtilmemiş/uygun JDK yok)
        var jdks = new JdkLocator();
        System.out.println("Bulunan JDK'lar: " + jdks.describe());

        String jdkHome = null;
        if (profile.javaVersion() != null) {
            var jdk = jdks.forVersion(profile.javaVersion());
            if (jdk.isEmpty()) {
                System.out.println("DURDURULDU: proje Java " + profile.javaVersion()
                        + " istiyor ama uygun JDK bulunamadı. Kurulu: " + jdks.describe());
                return null;
            }
            jdkHome = jdk.get().toString();
            System.out.println("Seçilen JDK: " + jdks.versionOf(jdk.get()).orElse(0)
                    + " (proje Java " + profile.javaVersion() + " istiyor)");
        }

        if (!precompile(config, jdkHome)) return null;

        var services = scanTargets(config);
        if (services == null) return null;

        return new Analysis(profile, jdkHome, services);
    }

    /**
     * Üretime başlamadan önce hedef proje kendi başına derleniyor mu?
     * Derlenmiyorsa her metot için boşuna model çağrılır.
     * Ayrıca bu adım target/classes'ın taze olmasını garanti eder,
     * CompiledTypeIndex bayat imza okumaz.
     */
    private boolean precompile(RunConfig config, String jdkHome) throws Exception {
        System.out.println("Hedef proje derleniyor...");

        var runner = new TestRunner(config.projectPath(), jdkHome);
        var initial = runner.compile();
        if (initial.success()) return true;

        System.out.println("\nDURDURULDU: hedef proje kendi başına derlenmiyor.");
        System.out.println("Önce projeyi derleyip tekrar deneyin. Derleyici çıktısı:\n");
        System.out.println(TestRunner.errorsOnly(initial.output()));
        return false;
    }

    /**
     * Kaynak ağacı bir kez taranır, seçiciye uyanlar alınır.
     * Test üretilemeyecek sınıflar (interface, abstract, public metodu olmayan)
     * elenir ve sebebiyle birlikte yazdirilir.
     *
     * @return null ise durdurulmalı
     */
    private List<ServiceInfo> scanTargets(RunConfig config) throws Exception {
        var scanner = new ServiceScanner(config.targets());
        var services = scanner.scan(config.sourceRoot());

        if (!scanner.skipped().isEmpty()) {
            System.out.println("\nAtlanan sınıflar (" + scanner.skipped().size() + "):");
            scanner.skipped().forEach(s -> System.out.println("  " + s));
        }

        if (services.isEmpty()) {
            System.out.println("\nDURDURULDU: seçilen hedeflerde test üretilebilecek sınıf bulunamadı.");
            config.targets().forEach(t -> System.out.println("  seçilen: " + t.label()));
            return null;
        }

        int methods = services.stream().mapToInt(s -> s.methods().size()).sum();

        System.out.println("\nBulunan sınıf sayısı: " + services.size());
        services.forEach(s ->
                System.out.println("  " + s.className() + " (" + s.methods().size() + " metot)"));

        if (methods == 0) {
            System.out.println("\nDURDURULDU: bulunan sınıflarda public metot yok.");
            return null;
        }

        return confirm(config, methods) ? services : null;
    }

    /**
     * Büyük seçimlerde üretime başlamadan önce onay ister.
     *
     * Gerekçe: hedefler dosya gezgininden seçiliyor ve yanlış klasör seçmek
     * kolay. 300 metotluk bir koşu hem dakikalar hem kredi harcıyor.
     */
    private boolean confirm(RunConfig config, int methods) {
        if (methods <= CONFIRM_THRESHOLD || config.force()) return true;

        System.out.println("\n" + methods + " metot işlenecek (~" + methods + " model çağrısı).");
        System.out.print("Devam edilsin mi? [E/h] ");

        try {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            String answer = reader.readLine();
            if (answer == null) return true;
            answer = answer.trim().toLowerCase();
            if (answer.startsWith("h") || answer.startsWith("n")) {
                System.out.println("İptal edildi.");
                return false;
            }
        } catch (Exception e) {
            // Konsol okunamiyorsa (IDE yapilandirmasi) devam et
            System.out.println("(giriş okunamadı, devam ediliyor)");
        }
        return true;
    }
}