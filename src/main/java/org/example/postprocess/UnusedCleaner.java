package org.example.postprocess;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.example.parse.Parsers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Uretilen test sinifindan kullanilmayan import ve @Mock alanlarini ayiklar.
 *
 * Kullanim: dosya diske YAZILMADAN once. Ayristirilamayan kod geldiginde
 * girdiyi oldugu gibi geri dondurur - temizlik hicbir zaman kod kaybettirmemeli.
 *
 * DIKKAT (@Mock ayiklama): Mockito constructor enjeksiyonu kullaniyorsa,
 * testte hic referans edilmeyen bir @Mock alani yine de servise gecirilen
 * argumandir. Silinirse o parametreye null gider ve gecen bir test kirilabilir.
 * Bu yuzden mock ayiklama varsayilan olarak KAPALIDIR; yalnizca sonucu tekrar
 * derleyip kosan ve basarisizlikta geri alan bir asamada (ornegin birlestirme)
 * acilmalidir.
 */
public class UnusedCleaner {

    private int removedImports;
    private int removedMocks;

    public int removedImports() { return removedImports; }
    public int removedMocks()   { return removedMocks; }

    /** Yalnizca import temizligi - guvenli, her zaman uygulanabilir. */
    public String clean(String code) {
        return clean(code, false);
    }

    public String clean(String code, boolean removeUnusedMocks) {
        removedImports = 0;
        removedMocks = 0;

        try {
            CompilationUnit cu = Parsers.parse(code);
            var clazz = cu.findAll(ClassOrInterfaceDeclaration.class)
                    .stream().findFirst().orElse(null);
            if (clazz == null) return code;

            if (removeUnusedMocks) stripUnusedMocks(clazz);
            stripUnusedImports(cu, clazz);

            return cu.toString();

        } catch (Exception e) {
            return code;   // ayristirilamadi: dokunma
        }
    }

    // ------------------------------------------------------------------
    // @Mock ayiklama
    // ------------------------------------------------------------------

    private void stripUnusedMocks(ClassOrInterfaceDeclaration clazz) {
        // Alan bildirimleri haric, sinifin geri kalanindaki metin
        String usage = bodyWithoutFields(clazz);

        List<FieldDeclaration> doomed = new ArrayList<>();

        for (FieldDeclaration f : clazz.getFields()) {
            if (!f.isAnnotationPresent("Mock")) continue;
            if (f.isAnnotationPresent("InjectMocks")) continue;   // hedef sinif
            if (f.isAnnotationPresent("Spy")) continue;           // gercek nesne

            String name = f.getVariable(0).getNameAsString();
            if (!containsWord(usage, name)) doomed.add(f);
        }

        for (FieldDeclaration f : doomed) {
            f.remove();
            removedMocks++;
        }
    }

    /** Alanlar disinda kalan her sey: metotlar, anotasyonlar, ic siniflar. */
    private String bodyWithoutFields(ClassOrInterfaceDeclaration clazz) {
        StringBuilder sb = new StringBuilder();
        clazz.getMembers().forEach(m -> {
            if (!(m instanceof FieldDeclaration)) sb.append(m.toString()).append("\n");
        });
        clazz.getAnnotations().forEach(a -> sb.append(a.toString()).append("\n"));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // import ayiklama
    // ------------------------------------------------------------------

    private void stripUnusedImports(CompilationUnit cu, ClassOrInterfaceDeclaration clazz) {
        // Mock ayiklamasi bittikten SONRA hesaplanmali: silinen bir mock'un
        // tipi artik kullanilmiyor olabilir.
        String usage = clazz.toString();

        List<ImportDeclaration> doomed = new ArrayList<>();

        for (ImportDeclaration imp : cu.getImports()) {
            // Joker importlar (import java.util.*, import static ...Mockito.*)
            // hangi uyeleri getirdigi bilinmeden silinemez.
            if (imp.isAsterisk()) continue;

            String simple = imp.getNameAsString();
            int dot = simple.lastIndexOf('.');
            if (dot >= 0) simple = simple.substring(dot + 1);

            if (!containsWord(usage, simple)) doomed.add(imp);
        }

        for (ImportDeclaration imp : doomed) {
            cu.remove(imp);
            removedImports++;
        }
    }

    // ------------------------------------------------------------------

    /** Tam kelime eslesmesi: "Test" araninca "TestCase" eslesmesin. */
    private boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                .matcher(haystack).find();
    }
}