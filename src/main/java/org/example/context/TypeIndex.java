package org.example.context;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.example.parse.Parsers;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Hedef modulun kaynak agacindaki tipleri indeksler.
 *
 * AYRISTIRMA KORUMASI: StaticJavaParser.parse onceden try-catch'siz cagriliyordu.
 * Tek bir dosya ayristirilamayinca exception Main'e kadar cikiyor ve JVM oluyordu.
 * Ilk dis makine denemesinde tam olarak bu yasandi. Artik o dosya baglamdan
 * duser, kosu devam eder ve sebebi konsola yazilir.
 *
 * BASIT AD CAKISMASI (B1): indeks basit ada gore calisiyor ve buyuk projelerde
 * Status, User, Response, Result gibi adlar onlarca pakette tekrarlaniyor.
 * Onceden fqns.put kullaniliyordu, yani SON okunan dosya kazaniyordu - ve
 * bunun en tehlikeli sonucu ImportFixer'daydi: model DOGRU importu yazsa bile
 * ("com.x.order.Status") fixWrongImports onu indeksteki baska bir adaya
 * ("com.y.payment.Status") ceviriyordu. Yani arac calisan kodu bozuyordu.
 * Kucuk benchmark'larda hic cakisma olmadigi icin gorunmedi.
 *
 * Cozum: bir basit ad birden fazla tam ada esliyorsa BELIRSIZ sayilir.
 * fqn() bos doner ve cagiranlar o tipe dokunmaz - yanlis tahmin etmektense
 * hic mudahale etmemek dogru davranis.
 */
public class TypeIndex {

    private final Map<String, TypeDeclaration<?>> types = new HashMap<>();

    /** basit ad -> tum tam nitelikli adaylar (cakisma varsa birden fazla). */
    private final Map<String, List<String>> fqns = new HashMap<>();

    private final Map<String, String> constants = new HashMap<>();

    /** Ayristirilamayan dosya sayisi; baglam sessizce eksilmesin diye raporlanir. */
    private int unparsed;

    public TypeIndex(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {

                CompilationUnit cu;
                try {
                    cu = Parsers.parse(p.toFile());
                } catch (Exception e) {
                    unparsed++;
                    System.out.println("  ayrıştırılamadı: " + p.getFileName()
                            + " — " + firstLine(e));
                    continue;
                }

                String pkg = cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
                cu.getTypes().forEach(t -> {
                    String simple = t.getNameAsString();
                    String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;

                    // putIfAbsent: ILK okunan kazanir. Cakisma zaten fqns'te
                    // isaretlendigi icin bu secim yalnizca iskelet render'i
                    // etkiliyor, import kararini etkilemiyor.
                    types.putIfAbsent(simple, t);

                    List<String> candidates =
                            fqns.computeIfAbsent(simple, k -> new ArrayList<>());
                    if (!candidates.contains(fqn)) candidates.add(fqn);

                    t.getFields().stream()
                            .filter(f -> f.isStatic() && f.isFinal())
                            .forEach(f -> f.getVariables().forEach(v ->
                                    v.getInitializer().ifPresent(init ->
                                            constants.put(v.getNameAsString(),
                                                    simple + "." + v.getNameAsString() + " = " + init))));
                });
            }
        }
    }

    /**
     * ParseProblemException mesaji birkac yuz karakter olabiliyor ve konsolu
     * boguyor; ilk satir teshis icin yeterli.
     */
    static String firstLine(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.lines().findFirst().orElse(msg).trim();
    }

    /**
     * TEK adayi olan tam nitelikli ad. Cakisma varsa BOS doner.
     *
     * Cagiranlar (ImportFixer, ContextExtractor) bos gorunce o tipe hic
     * dokunmuyor. Yanlis paketi yazmaktansa hic yazmamak dogru: model zaten
     * cogu zaman importu kendisi dogru yaziyor.
     */
    public Optional<String> fqn(String simpleName) {
        List<String> candidates = fqns.get(simpleName);
        if (candidates == null || candidates.size() != 1) return Optional.empty();
        return Optional.of(candidates.get(0));
    }

    /** Bu basit ada birden fazla tip mi karsilik geliyor? */
    public boolean isAmbiguous(String simpleName) {
        List<String> candidates = fqns.get(simpleName);
        return candidates != null && candidates.size() > 1;
    }

    /** Tum adaylar; teshis ve raporlama icin. */
    public List<String> candidates(String simpleName) {
        return List.copyOf(fqns.getOrDefault(simpleName, List.of()));
    }

    /** Cakisan basit adlarin sayisi. */
    public int ambiguousCount() {
        return (int) fqns.values().stream().filter(v -> v.size() > 1).count();
    }

    public Optional<TypeDeclaration<?>> find(String simpleName) {
        return Optional.ofNullable(types.get(simpleName));
    }

    public Optional<String> constant(String name) {
        return Optional.ofNullable(constants.get(name));
    }

    public int size() {
        return types.size();
    }

    /** Ayristirilamayan dosya sayisi. 0'dan buyukse baglam eksik demektir. */
    public int unparsed() {
        return unparsed;
    }
}