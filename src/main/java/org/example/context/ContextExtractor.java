package org.example.context;

import com.github.javaparser.ast.body.TypeDeclaration;
import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;
import org.example.parse.Parsers;

import java.util.*;
import java.util.regex.*;

/**
 * Bir metot icin prompt'a girecek tip baglamini cikarir.
 *
 * IKI KAYNAK (A3):
 *   1) Kaynak kod (TypeIndex) - hedef modulun src/main/java agaci
 *   2) Bytecode (CompiledTypeIndex) - kaynagi OLMAYAN tipler icin
 *
 * Ikincisi neden gerekli: TypeIndex yalnizca secilen modulun kaynak agacini
 * tariyor. Kardes moduldeki ya da kurumsal ortak kutuphanedeki bir tip orada
 * yok, dolayisiyla prompt'a HIC girmiyordu. Derleme geciyor, kosu calisiyor,
 * ama model o tipin uyelerini bilmeden test yaziyor - uydurma uye, yanlis
 * import, "cannot find symbol". Gurultulu bir hata degil, sessiz kalite kaybi.
 *
 * Loan'daki "LoanRequest.BorrowerDetails" ic sinif uydurma vakasinin muhtemel
 * sebebi buydu: LoanRequest common-dto modulunde yasiyor.
 */
public class ContextExtractor {

    private static final Pattern TYPE_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\b");
    private static final Pattern CONSTANT_NAME = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,})\\b");

    private final TypeIndex index;
    private final CompiledTypeIndex compiled;   // null olabilir
    private final TypeSkeleton skeleton;

    /** Kac tipin yalnizca bytecode'dan gelebildigi; konsolda raporlanir. */
    private int compiledOnlyTypes;

    public ContextExtractor(TypeIndex index) {
        this(index, null);
    }

    public ContextExtractor(TypeIndex index, CompiledTypeIndex compiled) {
        this.index    = index;
        this.compiled = compiled;
        this.skeleton = new TypeSkeleton(compiled);
    }

    public List<String> extract(ServiceInfo service, MethodInfo method) {
        Set<String> names = new LinkedHashSet<>();

        collect(names, method.body());
        collect(names, method.returnType());
        method.parameters().forEach(p -> collect(names, p));
        service.dependencies().forEach(d -> collect(names, d));

        names.remove(service.className());

        Set<String> nested = new LinkedHashSet<>();
        names.stream().map(index::find).flatMap(Optional::stream)
                .forEach(t -> collect(nested, skeleton.render(t)));

        nested.stream()
                .filter(n -> index.find(n).map(TypeDeclaration::isEnumDeclaration).orElse(false))
                .forEach(names::add);

        // ONCELIK: kaynak kod. Kaynagi yoksa bytecode'a dus.
        // Kaynak once geliyor cunku orada yorumlar, throw ifadeleri ve
        // thrownTypes bilgisi var; bytecode yalnizca imza tasiyor.
        return names.stream()
                .map(n -> index.find(n)
                        .map(t -> renderWithImport(n, t))
                        .or(() -> compiledOnly(n)))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Kaynagi olmayan tip: kardes modul ya da kurumsal ortak kutuphane.
     * Import satiri Class.getName()'den gelir, yani tam nitelikli ve dogrudur.
     */
    private Optional<String> compiledOnly(String name) {
        if (compiled == null) return Optional.empty();

        return compiled.find(name).map(c -> {
            compiledOnlyTypes++;
            return "import " + c.getName() + ";\n" + skeleton.renderCompiled(c);
        });
    }

    /** Yalnizca bytecode'dan gelebilen tip sayisi (kumulatif, tum metotlar icin). */
    public int compiledOnlyTypes() {
        return compiledOnlyTypes;
    }

    private String renderWithImport(String name, TypeDeclaration<?> t) {
        // Cakisan basit adda import satiri yazmiyoruz: eski hal "import Status;"
        // gibi derlenmeyen bir satir basardi (orElse(name)).
        return index.fqn(name)
                .map(fqn -> "import " + fqn + ";\n")
                .orElse("")
                + skeleton.render(t);
    }

    private void collect(Set<String> target, String text) {
        Matcher m = TYPE_NAME.matcher(text);
        while (m.find()) target.add(m.group(1));
    }

    public List<String> voidMethods(ServiceInfo service) {
        List<String> out = new ArrayList<>();
        for (String dep : service.dependencies()) {
            String[] parts = dep.split("\\s+");
            if (parts.length < 2) continue;
            String type = parts[0], field = parts[1];
            index.find(type).ifPresent(t -> t.getMethods().stream()
                    .filter(m -> m.getTypeAsString().equals("void"))
                    .forEach(m -> out.add(field + "." + m.getNameAsString() + "()")));
        }
        return out;
    }

    public List<String> calledMethodBodies(ServiceInfo service, MethodInfo method) {
        Set<String> called = new LinkedHashSet<>();
        try {
            Parsers.parseBlock(method.body())
                    .findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)
                    .forEach(c -> called.add(c.getNameAsString()));
        } catch (Exception e) {
            return List.of();
        }

        Set<String> out = new LinkedHashSet<>();

        // aynı sınıftaki public metotlar
        service.methods().stream()
                .filter(m -> !m.name().equals(method.name()))
                .filter(m -> called.contains(m.name()))
                .forEach(m -> out.add(service.className() + "." + m.name() + " " + m.body()));

        // bağlamdaki diğer tiplerin metotları
        Set<String> typeNames = new LinkedHashSet<>();
        collect(typeNames, method.body());
        method.parameters().forEach(p -> collect(typeNames, p));
        service.dependencies().forEach(d -> collect(typeNames, d));
        typeNames.remove(service.className());

        for (String n : typeNames) {
            index.find(n).ifPresent(t -> t.getMethods().stream()
                    .filter(m -> called.contains(m.getNameAsString()))
                    .filter(m -> m.getBody().isPresent())
                    .forEach(m -> out.add(n + "." + m.getNameAsString()
                            + " " + m.getBody().get())));
        }

        return List.copyOf(out);
    }

    /** Metodun, private yardımcıların ve constructor'ın kullandığı sabitler (değerleriyle). */
    public List<String> usedConstants(ServiceInfo service, MethodInfo method) {
        Set<String> names = new LinkedHashSet<>();

        collectConstants(names, method.body());
        service.privateMethods().forEach(p -> collectConstants(names, p.body()));
        service.constructors().forEach(c -> collectConstants(names, c));

        return names.stream()
                .map(index::constant)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private void collectConstants(Set<String> target, String text) {
        Matcher m = CONSTANT_NAME.matcher(text);
        while (m.find()) target.add(m.group(1));
    }
}