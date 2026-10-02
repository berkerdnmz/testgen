package org.example.analyzer;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ServiceAnalyzer {

    public ServiceInfo analyze(CompilationUnit cu, ClassOrInterfaceDeclaration clazz) {

        String pkg = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");

        List<String> deps = clazz.getFields().stream()
                .filter(f -> !f.isStatic())
                .map(this::describeField)
                .toList();

        // Basit getter/setter'lar hedef DEGIL: test edilecek davranislari yok.
        // Ama ATILMIYORLAR - accessors listesinde tutuluyorlar, cunku duz nesne
        // testinde durumu kurmanin ve okumanin tek yolu onlar.
        List<MethodInfo> methods = clazz.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .filter(m -> !isTrivialAccessor(clazz, m))
                .map(this::describeMethod)
                .toList();

        List<MethodInfo> accessors = clazz.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .filter(m -> isTrivialAccessor(clazz, m))
                .map(this::describeMethod)
                .toList();

        List<String> constants = clazz.getFields().stream()
                .filter(FieldDeclaration::isStatic)
                .map(f -> f.getVariable(0).toString())
                .toList();

        List<MethodInfo> privateMethods = clazz.getMethods().stream()
                .filter(m -> !m.isPublic())
                .map(this::describeMethod)
                .toList();

        List<String> constructors = clazz.getConstructors().stream()
                .map(c -> c.getDeclarationAsString(false, false, true) + " " + c.getBody())
                .toList();

        return new ServiceInfo(pkg, clazz.getNameAsString(), deps, constants,
                constructors, methods, accessors, privateMethods);
    }

    /**
     * Govdesi yalnizca bir alani okuyan ya da yazan metot mu?
     *
     * Kasitli olarak DAR tanimli: tek ifadeli govde, adi bir alan adiyla
     * eslesen okuma/yazma. "return repository.findAll();" gibi bir servis
     * metodu bu tanima girmez - orada bir cagri var, alan okumasi degil.
     */
    public static boolean isTrivialAccessor(ClassOrInterfaceDeclaration clazz, MethodDeclaration m) {
        var body = m.getBody().orElse(null);
        if (body == null || body.getStatements().size() != 1) return false;

        Set<String> fields = clazz.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .collect(Collectors.toSet());

        var stmt = body.getStatement(0);

        // getter: parametresiz, tek satir "return alan;" ya da "return this.alan;"
        if (m.getParameters().isEmpty() && stmt instanceof ReturnStmt ret) {
            var expr = ret.getExpression().orElse(null);
            if (expr == null) return false;
            if (expr instanceof NameExpr n) return fields.contains(n.getNameAsString());
            if (expr instanceof FieldAccessExpr fa && fa.getScope().isThisExpr()) {
                return fields.contains(fa.getNameAsString());
            }
            return false;
        }

        // setter: tek parametre, void, tek satir "this.alan = deger;"
        if (m.getParameters().size() == 1
                && m.getTypeAsString().equals("void")
                && stmt instanceof ExpressionStmt es
                && es.getExpression() instanceof AssignExpr assign) {

            var target = assign.getTarget();
            String name = (target instanceof FieldAccessExpr fa) ? fa.getNameAsString()
                    : (target instanceof NameExpr n) ? n.getNameAsString()
                    : null;

            return name != null && fields.contains(name);
        }

        return false;
    }

    private MethodInfo describeMethod(MethodDeclaration m) {
        List<String> params = m.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .toList();

        List<String> thrown = m.getThrownExceptions().stream()
                .map(Object::toString)
                .toList();

        String body = m.getBody().map(Object::toString).orElse("");

        return new MethodInfo(m.getNameAsString(), m.getTypeAsString(), params, thrown, body);
    }

    private String describeField(FieldDeclaration f) {
        var v = f.getVariable(0);
        return v.getTypeAsString() + " " + v.getNameAsString();
    }
}