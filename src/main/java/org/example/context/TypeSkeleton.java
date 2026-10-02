package org.example.context;

import com.github.javaparser.ast.body.*;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.stream.Collectors;

public class TypeSkeleton {

    private final CompiledTypeIndex compiled;   // null olabilir

    public TypeSkeleton() {
        this(null);
    }

    public TypeSkeleton(CompiledTypeIndex compiled) {
        this.compiled = compiled;
    }

    public String render(TypeDeclaration<?> type) {
        if (compiled != null && compiled.isAvailable()) {
            var c = compiled.find(type.getNameAsString()).orElse(null);
            if (c != null && addsMembers(c, type)) {
                return renderCompiled(c);
            }
        }
        return renderSource(type);
    }

    /**
     * Derlenmis surum kaynakta gorunmeyen public uye iceriyorsa true.
     * Boylece Lombok kullanmayan projelerde davranis hic degismez.
     */
    private boolean addsMembers(Class<?> c, TypeDeclaration<?> type) {
        long fromSource = type.getMethods().stream().filter(MethodDeclaration::isPublic).count();
        long fromBytecode = Arrays.stream(c.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .count();
        return fromBytecode > fromSource;
    }

    // ---------- derlenmis siniftan ----------

    /**
     * PUBLIC (A3): ContextExtractor kaynagi OLMAYAN tipler icin bunu dogrudan
     * cagiriyor. Kardes moduldeki ya da kurumsal ortak kutuphanedeki bir tipin
     * AST'si yok, dolayisiyla render(TypeDeclaration) yolu kullanilamiyor.
     */
    public String renderCompiled(Class<?> c) {
        StringBuilder sb = new StringBuilder();

        String kind = c.isEnum() ? "enum " : c.isInterface() ? "interface " : "class ";
        sb.append(kind).append(c.getSimpleName()).append(" {\n");

        if (c.isEnum()) {
            String entries = Arrays.stream(c.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            if (!entries.isEmpty()) sb.append("  ").append(entries).append(";\n");
        }

        Arrays.stream(c.getConstructors())
                .filter(k -> !k.isSynthetic())
                .forEach(k -> sb.append("  ").append(c.getSimpleName())
                        .append("(").append(params(k.getGenericParameterTypes())).append(");\n"));

        Arrays.stream(c.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .sorted(java.util.Comparator.comparing(Method::getName))
                .forEach(m -> {
                    if (Modifier.isStatic(m.getModifiers())) sb.append("  static ");
                    else sb.append("  ");
                    sb.append(simple(m.getGenericReturnType().getTypeName())).append(" ")
                            .append(m.getName())
                            .append("(").append(params(m.getGenericParameterTypes())).append(");\n");
                });

        // ic builder sinifi varsa imzalarini da goster
        Arrays.stream(c.getDeclaredClasses())
                .filter(n -> Modifier.isPublic(n.getModifiers()) && Modifier.isStatic(n.getModifiers()))
                .forEach(n -> sb.append(indent(renderCompiled(n))));

        return sb.append("}").toString();
    }

    private String params(Type[] types) {
        return Arrays.stream(types).map(t -> simple(t.getTypeName()))
                .collect(Collectors.joining(", "));
    }

    /** java.util.List<com.x.Booking> -> List<Booking> */
    private String simple(String typeName) {
        return typeName.replaceAll("(\\w+\\.)+", "");
    }

    private String indent(String block) {
        return block.lines().map(l -> "  " + l + "\n").collect(Collectors.joining());
    }

    // ---------- kaynak koddan (onceki davranis) ----------

    private String renderSource(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();

        String kind = type.isEnumDeclaration() ? "enum "
                : (type instanceof ClassOrInterfaceDeclaration c && c.isInterface()) ? "interface "
                : "class ";
        sb.append(kind).append(type.getNameAsString()).append(" {\n");

        if (type instanceof EnumDeclaration e) {
            String entries = e.getEntries().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            sb.append("  ").append(entries).append(";\n");
        }

        type.getConstructors().forEach(k ->
                sb.append("  ").append(k.getDeclarationAsString(false, false, true)).append(";\n"));

        type.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .forEach(m -> {
                    sb.append("  ").append(m.getDeclarationAsString(false, false, true)).append(";");
                    String thrown = thrownTypes(m);
                    if (!thrown.isEmpty()) sb.append("  // throws ").append(thrown);
                    sb.append("\n");
                });

        sb.append("}");
        return sb.toString();
    }

    private String thrownTypes(MethodDeclaration m) {
        return m.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).stream()
                .map(t -> t.getExpression())
                .filter(e -> e.isObjectCreationExpr())
                .map(e -> e.asObjectCreationExpr().getTypeAsString())
                .distinct()
                .collect(Collectors.joining(", "));
    }
}