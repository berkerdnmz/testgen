package org.example.postprocess;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.example.parse.Parsers;

import java.util.Set;

public class TestQuarantiner {

    public String disable(String code, Set<String> failingMethods, String reason) {
        if (failingMethods.isEmpty()) return code;
        try {
            var cu = Parsers.parse(code);
            cu.addImport("org.junit.jupiter.api.Disabled");

            cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> failingMethods.contains(m.getNameAsString()))
                    .filter(m -> !m.isAnnotationPresent("Disabled"))
                    .forEach(m -> m.addSingleMemberAnnotation("Disabled", "\"" + reason + "\""));

            return cu.toString();
        } catch (Exception e) {
            return code;
        }
    }
}