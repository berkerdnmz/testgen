package org.example.llm;

import com.github.javaparser.ast.CompilationUnit;
import org.example.parse.Parsers;

public class OutputCleaner {

    public String clean(String raw) {
        String s = raw.trim();

        int start = s.indexOf("```");
        if (start >= 0) {
            int nl = s.indexOf('\n', start);
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end);
            }
        }
        return s.trim();
    }

    public String normalize(String code, String packageName, String className) {
        try {
            CompilationUnit cu = Parsers.parse(code);

            // ilk üst düzey tipin adını dosya adıyla eşitle
            cu.getTypes().stream().findFirst()
                    .ifPresent(t -> t.setName(className));

            // package satırı yoksa ekle
            if (cu.getPackageDeclaration().isEmpty()) {
                cu.setPackageDeclaration(packageName);
            }

            return cu.toString();
        } catch (Exception e) {
            return code;   // ayrıştırılamıyorsa dokunma, derleme hatası onarım turuna kalsın
        }
    }
}