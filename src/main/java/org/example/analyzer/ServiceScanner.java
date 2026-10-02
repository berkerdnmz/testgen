package org.example.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.example.parse.Parsers;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Kaynak agacini BIR KEZ tarar, seciciye uyan siniflari dondurur.
 *
 * Test uretilemeyecek siniflar burada elenir ve sebebi yazdirilir:
 * arayuzler, soyut siniflar, yalnizca private constructor'i olanlar ve
 * hic public metodu olmayanlar. Elenmezlerse @InjectMocks onlari
 * orneklendiremez ve tum test sinifi tek test kosmadan coker.
 *
 * Ayrica basit getter/setter'lar hedef listesinden cikarilir (eleme
 * ServiceAnalyzer'da yapilir); burada yalnizca sayisi raporlanir.
 *
 * AYRISTIRMA KORUMASI: ayristirilamayan dosya artik tum kosuyu oldurmez,
 * "atlanan siniflar" listesine sebebiyle birlikte girer.
 */
public class ServiceScanner {

    private final ServiceAnalyzer analyzer = new ServiceAnalyzer();
    private final List<TargetSelector> selectors;
    private final List<String> skipped = new ArrayList<>();

    public ServiceScanner(String targetPackage) {
        this(List.of(TargetSelector.ofPackage(targetPackage)));
    }

    public ServiceScanner(List<TargetSelector> selectors) {
        this.selectors = List.copyOf(selectors);
    }

    /** Elenen siniflar ve sebepleri; scan() sonrasi okunur. */
    public List<String> skipped() {
        return skipped;
    }

    public List<ServiceInfo> scan(Path root) throws Exception {
        List<ServiceInfo> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {

                CompilationUnit cu;
                try {
                    cu = Parsers.parse(p.toFile());
                } catch (Exception e) {
                    skipped.add(p.getFileName() + " (ayrıştırılamadı: " + firstLine(e) + ")");
                    continue;
                }

                String pkg = cu.getPackageDeclaration()
                        .map(d -> d.getNameAsString())
                        .orElse("");

                for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!c.isTopLevelType()) continue;
                    if (!matches(pkg, c.getNameAsString())) continue;

                    String fqn = pkg.isEmpty() ? c.getNameAsString() : pkg + "." + c.getNameAsString();
                    String reason = untestableReason(c);

                    if (reason != null) {
                        skipped.add(fqn + " (" + reason + ")");
                        continue;
                    }

                    long accessors = c.getMethods().stream()
                            .filter(MethodDeclaration::isPublic)
                            .filter(m -> ServiceAnalyzer.isTrivialAccessor(c, m))
                            .count();
                    if (accessors > 0) {
                        skipped.add(fqn + " (" + accessors + " getter/setter atlandı)");
                    }

                    if (seen.add(fqn)) {
                        var info = analyzer.analyze(cu, c);
                        if (info.methods().isEmpty()) {
                            skipped.add(fqn + " (geriye test edilecek metot kalmadı)");
                        } else {
                            found.add(info);
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * ParseProblemException mesaji cok uzun olabiliyor; ilk satir teshis icin
     * yeterli ("...supported at JAVA_14 language level" gibi).
     */
    private static String firstLine(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.lines().findFirst().orElse(msg).trim();
    }

    private boolean matches(String pkg, String simpleName) {
        return selectors.stream().anyMatch(s -> s.matches(pkg, simpleName));
    }

    /** Test uretilemeyecek bir sinif mi? Sebep dondurur, uygunsa null. */
    private String untestableReason(ClassOrInterfaceDeclaration c) {
        if (c.isInterface()) return "arayüz";
        if (c.isAbstract())  return "soyut sınıf";

        boolean hasPublicMethod = c.getMethods().stream().anyMatch(m -> m.isPublic() && !m.isStatic());
        boolean hasPublicStatic = c.getMethods().stream().anyMatch(m -> m.isPublic() && m.isStatic());

        if (!hasPublicMethod && !hasPublicStatic) return "public metodu yok";

        boolean allCtorsPrivate = !c.getConstructors().isEmpty()
                && c.getConstructors().stream().allMatch(k -> k.isPrivate());
        if (allCtorsPrivate && !hasPublicStatic) return "yalnızca private constructor";

        return null;
    }
}