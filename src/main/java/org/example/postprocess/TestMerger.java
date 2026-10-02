package org.example.postprocess;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.example.parse.Parsers;

import java.util.*;

public class TestMerger {

    /**
     * Son merge() cagrisinin neden basarisiz oldugu. Basarili ise null.
     * Cagiran bunu konsola yazdirir; "birlestirme yapilamadi" mesaji tek basina
     * bes farkli durumu ayirt edemiyordu.
     */
    private String lastFailureReason;

    public String lastFailureReason() {
        return lastFailureReason;
    }

    private String fail(String reason) {
        this.lastFailureReason = reason;
        return null;
    }

    /** Birlestirilemezse null doner; cagiran metot bazli dosyalarda kalir. */
    public String merge(List<String> sources, String packageName, String mergedClassName) {
        lastFailureReason = null;

        if (sources.isEmpty()) return fail("kaynak dosya yok");

        try {
            Set<String> imports = new LinkedHashSet<>();
            Map<String, FieldDeclaration> fields = new LinkedHashMap<>();
            Map<String, String> typeToField = new LinkedHashMap<>();   // tip -> ortak alan adi
            Map<String, MethodDeclaration> methods = new LinkedHashMap<>();
            Map<String, String> lifecycle = new LinkedHashMap<>();     // anotasyon|govde -> ad
            ClassOrInterfaceDeclaration first = null;

            for (String src : sources) {
                CompilationUnit cu = Parsers.parse(src);
                var clazz = cu.findAll(ClassOrInterfaceDeclaration.class)
                        .stream().findFirst().orElse(null);
                if (clazz == null) return fail("sinif ayristirilamadi");
                if (first == null) first = clazz;

                cu.getImports().forEach(i -> imports.add(i.toString().trim()));
                String origin = originOf(clazz.getNameAsString());

                // --- ayni tipe farkli ad verilmisse ortak ada cevir ---
                String renameError = normalizeFieldNames(clazz, typeToField);
                if (renameError != null) return fail(renameError);

                for (FieldDeclaration f : clazz.getFields()) {
                    var v = f.getVariable(0);
                    String name = v.getNameAsString();
                    String type = v.getTypeAsString();

                    if (fields.containsKey(name) && !type.equals(typeOf(fields.get(name))))
                        return fail("ayni alan adi farkli tip: " + name
                                + " (" + type + " / " + typeOf(fields.get(name)) + ")");

                    typeToField.putIfAbsent(type, name);
                    fields.putIfAbsent(name, f);
                }

                for (MethodDeclaration m : clazz.getMethods()) {
                    String hook = lifecycleAnnotation(m);

                    if (hook != null) {
                        String body = m.getBody().map(Object::toString).orElse("");
                        String key = hook + "|" + body;
                        if (lifecycle.containsKey(key)) continue;          // ayni kurulum
                        boolean sameHookExists = lifecycle.keySet().stream()
                                .anyMatch(k -> k.startsWith(hook + "|"));
                        if (sameHookExists)
                            return fail("farkli govdeli @" + hook + " kancalari");
                        lifecycle.put(key, m.getNameAsString());
                        methods.put(m.getNameAsString(), m);
                        continue;
                    }

                    String name = m.getNameAsString();
                    if (methods.containsKey(name)) {
                        if (methods.get(name).toString().equals(m.toString())) continue;
                        name = name + "_" + origin;
                        m.setName(name);
                    }
                    methods.put(name, m);
                }
            }

            CompilationUnit out = new CompilationUnit();
            out.setPackageDeclaration(packageName);
            imports.forEach(i -> out.addImport(Parsers.parseImport(i)));

            var merged = out.addClass(mergedClassName);
            merged.setPublic(true);
            first.getAnnotations().forEach(a -> merged.addAnnotation(a.clone()));

            fields.values().forEach(f -> merged.addMember(f.clone()));
            methods.values().forEach(m -> merged.addMember(m.clone()));

            return out.toString();

        } catch (Exception e) {
            return fail("istisna: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // alan adi normalizasyonu
    // ------------------------------------------------------------------

    /**
     * Bu siniftaki alan adlarini, daha once gorulmus ortak adlara cevirir.
     * Ornek: baska dosyada LoanCalculation icin "loanCalculation" kullanilmissa,
     * bu dosyadaki "loanCalculationImpl" alani ve tum referanslari yeniden adlandirilir.
     *
     * @return hata mesaji, ya da basariliysa null
     */
    private String normalizeFieldNames(ClassOrInterfaceDeclaration clazz,
                                       Map<String, String> typeToField) {

        Map<String, String> rename = new LinkedHashMap<>();   // eski ad -> yeni ad
        Map<String, String> localNames = new LinkedHashMap<>(); // bu siniftaki ad -> tip

        for (FieldDeclaration f : clazz.getFields()) {
            var v = f.getVariable(0);
            localNames.put(v.getNameAsString(), v.getTypeAsString());
        }

        for (FieldDeclaration f : clazz.getFields()) {
            var v = f.getVariable(0);
            String name = v.getNameAsString();
            String type = v.getTypeAsString();

            String canonical = typeToField.get(type);
            if (canonical == null || canonical.equals(name)) continue;

            // Hedef ad bu sinifta baska bir tip icin kullaniliyorsa cakisir
            String occupiedBy = localNames.get(canonical);
            if (occupiedBy != null && !occupiedBy.equals(type)) {
                return "alan adi cakismasi: " + name + " -> " + canonical
                        + " ama " + canonical + " zaten " + occupiedBy + " icin kullaniliyor";
            }

            rename.put(name, canonical);
        }

        if (rename.isEmpty()) return null;

        // Zincirleme/takas yeniden adlandirma riskli: A->B ve B->C gibi durumlarda vazgec
        for (String target : rename.values()) {
            if (rename.containsKey(target)) {
                return "zincirleme alan adi degisimi (" + target + ")";
            }
        }

        for (var e : rename.entrySet()) {
            String oldName = e.getKey();
            String newName = e.getValue();

            clazz.findAll(NameExpr.class).stream()
                    .filter(n -> n.getNameAsString().equals(oldName))
                    .forEach(n -> n.setName(newName));

            clazz.findAll(FieldAccessExpr.class).stream()
                    .filter(fa -> fa.getNameAsString().equals(oldName))
                    .forEach(fa -> fa.setName(newName));

            clazz.getFields().forEach(f -> {
                var v = f.getVariable(0);
                if (v.getNameAsString().equals(oldName)) v.setName(newName);
            });
        }

        return null;
    }

    // ------------------------------------------------------------------
    // yardimcilar
    // ------------------------------------------------------------------

    private String typeOf(FieldDeclaration f) {
        return f.getVariable(0).getTypeAsString();
    }

    private String lifecycleAnnotation(MethodDeclaration m) {
        for (String a : List.of("BeforeEach", "AfterEach", "BeforeAll", "AfterAll")) {
            if (m.isAnnotationPresent(a)) return a;
        }
        return null;
    }

    /** ProductServices_updateproduct_GenTest -> updateproduct */
    private String originOf(String testClassName) {
        String s = testClassName.replaceAll("GenTest$", "");
        int i = s.indexOf('_');
        return (i < 0 ? s : s.substring(i + 1)).replaceAll("_+$", "");
    }
}