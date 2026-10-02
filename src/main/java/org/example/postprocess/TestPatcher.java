package org.example.postprocess;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.example.parse.Parsers;

public class TestPatcher {

    /**
     * Mevcut test sinifina modelin urettigi metotlari uygular.
     * Ayni adli metot varsa DEGISTIRIR.
     *
     * Onarimin semantigi "kirik testi duzelt"tir, "yeni test yaz" degil:
     * yeni @Test metotlari eklenirse tur icinde toplam test sayisi artiyor
     * (3/6 -> 3/7), bu hem gecen test oranini kotu gosteriyor hem bestPassed
     * karsilastirmasini bozuyordu. O yuzden YENI @Test metotlari yok sayilir.
     *
     * ANCAK yeni YARDIMCI metotlar (annotation'siz) eklenir. Sebep olculdu:
     * model onarimda "createApplication(status)" gibi bir yardimci cikarip
     * testlerden cagirdi; yardimci yok sayilinca kod
     * "cannot find symbol: method createApplication" ile derlenmedi. Yasak
     * test SAYISINI korumak icindi, yardimci metotlar o sayiyi degistirmiyor.
     *
     * Model sozlesmeye uymayip tam dosya dondurduyse null doner.
     */
    public String apply(String existingCode, String newMethods) {
        try {
            var cu = Parsers.parse(existingCode);
            var clazz = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .findFirst().orElse(null);
            if (clazz == null) return null;

            var patch = Parsers.parse("class Patch {" + newMethods + "}")
                    .findAll(MethodDeclaration.class);

            if (patch.isEmpty()) return null;

            int applied = 0;
            int ignored = 0;
            int helpers = 0;

            for (MethodDeclaration m : patch) {
                var existing = clazz.getMethodsByName(m.getNameAsString());

                if (existing.isEmpty()) {
                    if (isTestMethod(m)) {
                        // mevcut kodda olmayan test: onarim degil, yeni test
                        ignored++;
                    } else {
                        // yardimci metot: testler onu cagiriyor olabilir, ekle
                        clazz.addMember(m);
                        helpers++;
                    }
                    continue;
                }

                existing.forEach(Node::remove);
                clazz.addMember(m);
                applied++;
            }

            // Yalnizca yardimci eklendiyse de kod degismistir; ama hicbir test
            // duzeltilmediyse onarim anlamsizdir.
            if (applied == 0) return null;

            if (ignored > 0) {
                System.out.println("  yamada " + ignored + " yeni test metodu yok sayildi");
            }
            if (helpers > 0) {
                System.out.println("  yamada " + helpers + " yardımcı metot eklendi");
            }

            return cu.toString();

        } catch (Exception e) {
            return null;
        }
    }

    /** @Test / @ParameterizedTest / @RepeatedTest tasiyan metot. */
    private boolean isTestMethod(MethodDeclaration m) {
        return m.isAnnotationPresent("Test")
                || m.isAnnotationPresent("ParameterizedTest")
                || m.isAnnotationPresent("RepeatedTest");
    }
}