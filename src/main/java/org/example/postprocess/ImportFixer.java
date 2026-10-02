package org.example.postprocess;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import org.example.context.TypeIndex;
import org.example.parse.Parsers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * Uretilen test kodundaki import satirlarini duzeltir.
 *
 * Uc is yapar:
 *   1) fixWrongImports - sinif adi dogru ama paket yolu yanlis olan importlari duzeltir
 *   2) eksik tip importlarini ekler
 *   3) eksik STATIK importlari ekler (when, verify, assertEquals, any...)
 *
 * Tip cozumleme onceligi - ILK ESLESEN KAZANIR:
 *   a) hedef projenin kendi tipleri (TypeIndex)
 *   b) JDK tipleri (Class.forName; elle tablo tutulmaz, boylece UUID/Duration
 *      gibi tipler kendiliginden cozulur)
 *   c) test kutuphanesi tipleri (asagidaki tablo)
 *
 * Tip adlari AST'den okunur; yorum satirlari ve string sabitleri icindeki
 * kelimeler bu sayede yanlislikla tip sanilmaz. Kod ayristirilamazsa
 * (onarim turunda bozuk kod gelebilir) regex'e geri dusulur.
 */
public class ImportFixer {

    /** AST ayristirilamadiginda kullanilan yedek desen. */
    private static final Pattern TYPE_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\b");

    private static final Pattern IMPORT_LINE = Pattern.compile("import\\s+([\\w.]+);");

    private static final String NOT_FOUND = "";

    private final Map<String, String> jdkCache = new ConcurrentHashMap<>();

    /**
     * JDK tipi aranan paketler. Sira onemli: java.lang once gelmeli.
     * java.lang tipleri sonucta elenir (otomatik import edilirler).
     */
    private static final String[] JDK_PACKAGES = {
            "java.lang.",
            "java.util.",
            "java.time.",
            "java.math.",
            "java.util.function.",
            "java.util.stream.",
            "java.util.concurrent."
    };

    /**
     * Test kutuphanesi tipleri. Bunlar ne TypeIndex'te ne java.* altinda
     * oldugu icin Class.forName ile bulunamaz - testgen'in kendi classpath'inde
     * JUnit/Mockito yok.
     */
    private static final Map<String, String> TEST_LIBRARY_TYPES = Map.ofEntries(
            // JUnit 5
            Map.entry("Test",             "org.junit.jupiter.api.Test"),
            Map.entry("BeforeEach",       "org.junit.jupiter.api.BeforeEach"),
            Map.entry("AfterEach",        "org.junit.jupiter.api.AfterEach"),
            Map.entry("BeforeAll",        "org.junit.jupiter.api.BeforeAll"),
            Map.entry("AfterAll",         "org.junit.jupiter.api.AfterAll"),
            Map.entry("DisplayName",      "org.junit.jupiter.api.DisplayName"),
            Map.entry("Disabled",         "org.junit.jupiter.api.Disabled"),
            Map.entry("Nested",           "org.junit.jupiter.api.Nested"),
            Map.entry("Assertions",       "org.junit.jupiter.api.Assertions"),
            Map.entry("ExtendWith",       "org.junit.jupiter.api.extension.ExtendWith"),
            Map.entry("ParameterizedTest","org.junit.jupiter.params.ParameterizedTest"),
            Map.entry("ValueSource",      "org.junit.jupiter.params.provider.ValueSource"),
            Map.entry("CsvSource",        "org.junit.jupiter.params.provider.CsvSource"),
            // Mockito
            Map.entry("Mock",             "org.mockito.Mock"),
            Map.entry("InjectMocks",      "org.mockito.InjectMocks"),
            Map.entry("Spy",              "org.mockito.Spy"),
            Map.entry("Captor",           "org.mockito.Captor"),
            Map.entry("Mockito",          "org.mockito.Mockito"),
            Map.entry("ArgumentCaptor",   "org.mockito.ArgumentCaptor"),
            Map.entry("ArgumentMatchers", "org.mockito.ArgumentMatchers"),
            Map.entry("MockitoExtension", "org.mockito.junit.jupiter.MockitoExtension"),
            Map.entry("MockitoAnnotations","org.mockito.MockitoAnnotations")
    );

    /**
     * Nitelenmemis statik metot cagrisi -> gerekli statik import.
     * "verify(repo)" gibi bir cagri tip adi olmadigi icin yukaridaki tabloya
     * girmez, ayri ele alinir.
     */
    private static final Map<String, String> STATIC_IMPORTS = new LinkedHashMap<>();
    static {
        String mockito  = "org.mockito.Mockito.*";
        String matchers = "org.mockito.ArgumentMatchers.*";
        String asserts   = "org.junit.jupiter.api.Assertions.*";

        for (String m : new String[]{"when", "verify", "mock", "spy", "doThrow", "doNothing",
                "doReturn", "doAnswer", "doCallRealMethod", "times", "never", "atLeastOnce",
                "atLeast", "atMost", "inOrder", "verifyNoMoreInteractions",
                "verifyNoInteractions", "withSettings", "clearInvocations"}) {
            STATIC_IMPORTS.put(m, mockito);
        }
        for (String m : new String[]{"any", "anyInt", "anyLong", "anyDouble", "anyBoolean",
                "anyString", "anyList", "anySet", "anyMap", "anyCollection", "eq", "argThat",
                "isNull", "isNotNull", "nullable"}) {
            STATIC_IMPORTS.put(m, matchers);
        }
        for (String m : new String[]{"assertEquals", "assertNotEquals", "assertTrue",
                "assertFalse", "assertNull", "assertNotNull", "assertThrows",
                "assertDoesNotThrow", "assertSame", "assertNotSame", "assertAll",
                "assertArrayEquals", "assertIterableEquals", "fail"}) {
            STATIC_IMPORTS.put(m, asserts);
        }
    }

    /**
     * Bu koklerden gelen importlar ASLA yeniden yazilmaz.
     * Aksi halde hedef projede "Test" adli bir sinif varsa
     * import org.junit.jupiter.api.Test satiri ezilir ve her sey coker.
     */
    private static final String[] PROTECTED_IMPORT_ROOTS = {
            "java.", "javax.", "jakarta.",
            "org.junit.", "org.mockito.", "org.hamcrest.", "org.assertj.",
            "org.springframework.", "lombok.", "com.fasterxml.", "org.slf4j."
    };

    private final TypeIndex index;

    public ImportFixer(TypeIndex index) {
        this.index = index;
    }

    // ------------------------------------------------------------------
    // ana giris
    // ------------------------------------------------------------------

    public String fix(String rawCode, String testPackage) {
        final String code = fixWrongImports(rawCode);

        Symbols symbols = symbols(code);

        Set<String> imports = new TreeSet<>();
        for (String n : symbols.types()) {
            resolve(n, testPackage).ifPresent(imports::add);
        }

        imports.removeIf(i -> code.contains("import " + i + ";"));

        // AYNI BASIT ADLA IKINCI IMPORT EKLEME.
        // Hedef projede "Test" adli bir sinif varsa TypeIndex onu bulur ve
        // "import com.x.Test;" eklenirdi - oysa dosyada zaten
        // "import org.junit.jupiter.api.Test;" var. Iki ayni adli import
        // derleme hatasidir. Dosyada o basit ad zaten import edilmisse dokunma.
        Set<String> alreadyImported = importedSimpleNames(code);
        imports.removeIf(i -> alreadyImported.contains(simpleName(i)));

        Set<String> statics = new TreeSet<>();
        for (String call : symbols.unqualifiedCalls()) {
            String target = STATIC_IMPORTS.get(call);
            if (target != null) statics.add(target);
        }
        statics.removeIf(s -> code.contains("import static " + s.substring(0, s.length() - 1)));

        if (imports.isEmpty() && statics.isEmpty()) return code;

        int at = importInsertionPoint(code);
        if (at < 0) return code;   // package satiri yok, dokunma

        StringBuilder block = new StringBuilder("\n");
        imports.forEach(i -> block.append("import ").append(i).append(";\n"));
        statics.forEach(s -> block.append("import static ").append(s).append(";\n"));

        return new StringBuilder(code).insert(at, block).toString();
    }

    /** Dosyada halihazirda import edilen tiplerin basit adlari. */
    private Set<String> importedSimpleNames(String code) {
        Set<String> names = new HashSet<>();
        Matcher m = IMPORT_LINE.matcher(code);
        while (m.find()) names.add(simpleName(m.group(1)));
        return names;
    }

    /**
     * Tek bir tip adini coz. ILK ESLESEN KAZANIR - proje tipi JDK tipini,
     * JDK tipi test kutuphanesi tipini ezer.
     */
    private Optional<String> resolve(String name, String testPackage) {
        var own = index.fqn(name);
        if (own.isPresent()) {
            String fqn = own.get();
            int dot = fqn.lastIndexOf('.');
            String pkg = dot < 0 ? "" : fqn.substring(0, dot);
            return pkg.equals(testPackage) ? Optional.empty() : Optional.of(fqn);
        }

        var jdk = jdkFqn(name);
        if (jdk.isPresent()) {
            return jdk.get().startsWith("java.lang.") ? Optional.empty() : jdk;
        }

        return Optional.ofNullable(TEST_LIBRARY_TYPES.get(name));
    }

    // ------------------------------------------------------------------
    // yanlis import duzeltme
    // ------------------------------------------------------------------

    private String fixWrongImports(String code) {
        Matcher m = IMPORT_LINE.matcher(code);
        Map<String, String> wrong = new LinkedHashMap<>();

        while (m.find()) {
            String imported = m.group(1);
            if (isProtected(imported)) continue;

            String simple = simpleName(imported);
            index.fqn(simple)
                    .filter(correct -> !correct.equals(imported))
                    .ifPresent(correct -> wrong.put(imported, correct));
        }

        String out = code;
        for (var e : wrong.entrySet()) {
            out = out.replace("import " + e.getKey() + ";", "import " + e.getValue() + ";");
        }
        return out;
    }

    private boolean isProtected(String fqn) {
        for (String root : PROTECTED_IMPORT_ROOTS) {
            if (fqn.startsWith(root)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // sembol toplama (AST, gerekirse regex)
    // ------------------------------------------------------------------

    /** Koddan toplanan tip adlari ve nitelenmemis metot cagrilari. */
    private record Symbols(Set<String> types, Set<String> unqualifiedCalls) { }

    private Symbols symbols(String code) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> calls = new LinkedHashSet<>();

        try {
            CompilationUnit cu = Parsers.parse(code);

            // tip pozisyonundakiler: Booking b, mock(Booking.class), List<Loan>
            cu.findAll(ClassOrInterfaceType.class)
                    .forEach(t -> names.add(t.getNameAsString()));

            // ANOTASYONLAR: @InjectMocks, @ExtendWith, @Test...
            // AnnotationExpr, ClassOrInterfaceType degildir; toplanmadigi icin
            // "cannot find symbol: class InjectMocks" hatasi cikmisti. Simdiye
            // kadar gorulmemesinin sebebi modelin importu genelde kendi yazmasi.
            cu.findAll(AnnotationExpr.class)
                    .forEach(a -> names.add(simpleName(a.getNameAsString())));

            // statik erisimler: BookingStatus.ACTIVE, Collections.emptyList()
            cu.findAll(NameExpr.class).stream()
                    .map(NameExpr::getNameAsString)
                    .filter(n -> !n.isEmpty() && Character.isUpperCase(n.charAt(0)))
                    .forEach(names::add);

            // nitelenmemis cagrilar: when(...), verify(...), assertEquals(...)
            // Scope'u olan cagrilar (repo.findAll()) haric tutulur.
            cu.findAll(MethodCallExpr.class).stream()
                    .filter(c -> c.getScope().isEmpty())
                    .map(MethodCallExpr::getNameAsString)
                    .forEach(calls::add);

        } catch (Exception e) {
            // bozuk kod: yedek desene dus
            Matcher m = TYPE_NAME.matcher(code);
            while (m.find()) names.add(m.group(1));

            for (String known : STATIC_IMPORTS.keySet()) {
                if (Pattern.compile("(?<![.\\w])" + known + "\\s*\\(").matcher(code).find()) {
                    calls.add(known);
                }
            }
        }
        return new Symbols(names, calls);
    }

    /** "org.mockito.Mock" -> "Mock" */
    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    // ------------------------------------------------------------------
    // JDK tipi cozumleme
    // ------------------------------------------------------------------

    private Optional<String> jdkFqn(String simpleName) {
        String cached = jdkCache.get(simpleName);
        if (cached != null) {
            return cached.equals(NOT_FOUND) ? Optional.empty() : Optional.of(cached);
        }

        for (String pkg : JDK_PACKAGES) {
            String candidate = pkg + simpleName;
            try {
                Class.forName(candidate);
                jdkCache.put(simpleName, candidate);
                return Optional.of(candidate);
            } catch (Throwable ignored) { }
        }

        jdkCache.put(simpleName, NOT_FOUND);
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // yardimci
    // ------------------------------------------------------------------

    /** package satirinin noktali virgulunden hemen sonrasi. Bulunamazsa -1. */
    private int importInsertionPoint(String code) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;").matcher(code);
        return m.find() ? m.end() : -1;
    }
}