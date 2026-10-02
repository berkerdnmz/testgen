package org.example.prompt;

import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;

import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iki farkli iskelet uretir. Secim YAPISAL bir olcute gore yapilir:
 * sinifin mock'lanacak bir bagimliligi var mi?
 *
 * VAR  -> Mockito iskeleti. Service ve controller siniflari boyle.
 * YOK  -> duz nesne iskeleti. Domain model siniflari boyle.
 *
 * BAGIMLILIK vs DURUM AYRIMI (olculdu):
 * Bir alanin proje tipinde olmasi onu bagimlilik yapmaz. Application entity'si
 * "Student student" ve "Authority evaluatedBy" alanlarini tasiyor; bunlar
 * enjekte edilen bagimlilik degil, entity'nin kendi verisi. Onceden bu ayrim
 * yapilmadigi icin Application Mockito yolundan gidiyor ve "@Spy Long id" gibi
 * saplantilarla tum testleri dusuruyordu.
 *
 * Ayirt edici: ALANIN GETTER'I VAR MI. Bir servisin repository alaninin
 * getter'i olmaz - disariya acilmaz, enjekte edilir. Bir entity'nin alaninin
 * getter'i olur - veri odur. Alan duzeyinde calisan, tamamen deterministik
 * bir olcut.
 *
 * DIKKAT - KURAL METINLERI KASITLI OLARAK KOPYA:
 * Iki iskeletin kural bloklari ortak bir yardimci metoda cikarilmadi. Mock
 * yolundaki kurallarin her biri olculmus bir iyilestirmeye karsilik geliyor;
 * ortak metotta yapilacak kucuk bir duzenleme service yolunu sessizce
 * degistirebilir.
 */
public class PromptBuilder {

    /** Mockito'nun ASLA mock/spy edemedigi tipler - surumden bagimsiz. */
    private static final Set<String> NEVER_MOCKABLE = Set.of(
            "String", "Class",
            "Integer", "Long", "Double", "Float",
            "Boolean", "Character", "Byte", "Short");

    private static final Set<String> PRIMITIVES = Set.of(
            "int", "long", "double", "float", "boolean", "char", "byte", "short", "void");

    public String build(ServiceInfo service, MethodInfo method,
                        List<String> typeContext, List<String> voidMethods,
                        List<String> calledBodies, List<String> importedConstants) {

        // Getter/setter'i olan alanlar DURUM'dur, bagimlilik degil.
        Set<String> exposed = exposedFieldNames(service.accessors());

        var candidates = service.dependencies().stream()
                .filter(d -> !exposed.contains(fieldName(d)))
                .toList();

        var mockable = candidates.stream()
                .filter(d -> isMockCandidate(d) || isConstructorParameter(service, d))
                .toList();

        var jdkInitialised = candidates.stream()
                .filter(d -> !isMockCandidate(d))
                .filter(d -> !isConstructorParameter(service, d))
                .filter(d -> initialisedInConstructor(service, d))
                .toList();

        var jdkSpyable = candidates.stream()
                .filter(d -> !isMockCandidate(d))
                .filter(d -> !isConstructorParameter(service, d))
                .filter(d -> !initialisedInConstructor(service, d))
                .filter(PromptBuilder::isSpyable)
                .toList();

        var plainState = candidates.stream()
                .filter(d -> !isMockCandidate(d))
                .filter(d -> !isConstructorParameter(service, d))
                .filter(d -> !initialisedInConstructor(service, d))
                .filter(d -> !isSpyable(d))
                .toList();

        boolean plainObject = mockable.isEmpty() && jdkSpyable.isEmpty();

        return plainObject
                ? buildPlainObject(service, method, typeContext, calledBodies, importedConstants)
                : buildWithMocks(service, method, typeContext, voidMethods, calledBodies,
                importedConstants, mockable, jdkInitialised, jdkSpyable, plainState);
    }

    /**
     * Getter/setter'i olan alan adlari.
     * getStudent -> student, setScore -> score, isActive -> active,
     * student() -> student (record bicimli erisim).
     */
    private static Set<String> exposedFieldNames(List<MethodInfo> accessors) {
        Set<String> names = new LinkedHashSet<>();
        for (MethodInfo m : accessors) {
            String n = m.name();
            if ((n.startsWith("get") || n.startsWith("set")) && n.length() > 3) {
                names.add(decapitalise(n.substring(3)));
            } else if (n.startsWith("is") && n.length() > 2) {
                names.add(decapitalise(n.substring(2)));
            } else {
                names.add(n);
            }
        }
        return names;
    }

    private static String decapitalise(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ==================================================================
    // 1) Bagimliligi olan siniflar
    //
    //    11. gunde uc kural degisti; bu metodun ciktisi artik onceki
    //    surumlerle birebir ayni DEGIL:
    //      (a) gercek gecersiz deger kurali eklendi
    //      (b) sinir kurali yalnizca SIRALAMA operatorlerine daraltildi,
    //          esitlik icin ayri bir cumle eklendi
    //      (c) gecis metotlarinda tautolojik assertion yasagi eklendi
    // ==================================================================

    private String buildWithMocks(ServiceInfo service, MethodInfo method,
                                  List<String> typeContext, List<String> voidMethods,
                                  List<String> calledBodies, List<String> importedConstants,
                                  List<String> mockable, List<String> jdkInitialised,
                                  List<String> jdkSpyable, List<String> plainState) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a senior Java test engineer.\n");
        sb.append("Write a JUnit 5 test class for ONE method of a Spring service.\n\n");

        sb.append("Package: ").append(service.packageName()).append("\n");
        sb.append("Class under test: ").append(service.className()).append("\n\n");

        sb.append("Dependencies (each must be declared with @Mock):\n");
        if (mockable.isEmpty()) {
            sb.append("  (none - do not create any mocks)\n");
        } else {
            mockable.forEach(d -> sb.append("  ").append(d).append("\n"));
        }
        sb.append("\n");

        if (!jdkInitialised.isEmpty()) {
            sb.append("These fields are internal state, not dependencies. The constructor below already fills them with real values, and @InjectMocks runs that constructor. Do NOT mock them, do NOT declare them with @Spy, and do NOT overwrite them with reflection:\n");
            jdkInitialised.forEach(d -> sb.append("  ").append(d).append("\n"));
            sb.append("\n");
        }

        if (!jdkSpyable.isEmpty()) {
            sb.append("These dependencies are JDK container types and must NEVER be mocked. Declare each one with @Spy and a real instance, then populate it inside the test with the values your scenario needs:\n");
            jdkSpyable.forEach(d -> sb.append("  @Spy ").append(d).append(" = new ...();\n"));
            sb.append("\n");
        }

        if (!plainState.isEmpty()) {
            sb.append("These fields are plain internal state. Mockito cannot mock or spy them (primitives, String, wrapper types and final JDK classes). Do NOT declare them with @Mock or @Spy, do NOT set them by reflection, and do NOT declare them in the test class at all. They hold their default or constructed value during the test:\n");
            plainState.forEach(d -> sb.append("  ").append(d).append("\n"));
            sb.append("\n");
        }

        appendSharedContext(sb, service, method, typeContext, calledBodies, importedConstants);

        if (!voidMethods.isEmpty()) {
            sb.append("These methods return void. Stub them ONLY with doNothing()/doThrow(), never with when(...).thenReturn(...):\n");
            voidMethods.forEach(v -> sb.append("  ").append(v).append("\n"));
            sb.append("\n");
        }

        sb.append("Rules:\n");
        sb.append("- Use @ExtendWith(MockitoExtension.class), @Mock for every dependency listed above, @InjectMocks for the class under test.\n");
        sb.append("- Never stub a real object; only mocks may be stubbed.\n");
        sb.append("- Cover every branch: one test for each thrown exception and one for the success path.\n");
        sb.append("- When a test covers a rejected or invalid input, supply a REAL value that violates the rule and let the method's own code reject it. Derive the value from the validation logic shown above: for a length limit pass a string of the wrong length, for a character or format rule pass a string containing the forbidden characters (for example \"a1!_\"), for a null or blank check pass null or \"   \", for a numeric range pass a number outside it. Never force the rejection by stubbing a collaborator to return false or to throw - that asserts what you told the stub to do and proves nothing about the method under test.\n");
        sb.append("- Stub a collaborator only for data the method needs to proceed. If the method's own body performs the check, do not stub anything to make that check fail.\n");
        sb.append("- Do NOT write a test whose whole content is stubbing a collaborator to throw and then asserting that the same exception escapes. If the body of the method under test contains no try/catch, no fallback path and no rollback for that exception, such a test proves only that Java propagates exceptions - it says nothing about this method. Stub a collaborator to throw ONLY when the method actually reacts to the failure, and then assert the reaction (the fallback value, the cleanup call, the translated exception type), not the throw itself.\n");
        sb.append("- Never create a mock of a project type that has getters and setters. Those objects are data: build a real instance and set the values your scenario needs. Mocking them bypasses the class's own logic and hides real defects.\n");
        sb.append("- Only stub values the production code could actually produce. A repository method that returns a list never returns null, and a method declared to return Optional never returns null either. A test built on an impossible stub value tests a situation that cannot happen.\n");
        sb.append("- When the method under test only passes a collaborator's result straight through, assert that it is the same object and stop: assertSame(expected, result). Do NOT then re-assert the fields of an object you built yourself and handed to the stub - the mock returned exactly what you gave it, so those assertions test your own test data, not the method. Spend assertions on what the method itself computes, transforms, filters, chooses or rejects.\n");
        sb.append("- Boundary tests apply ONLY when the body of the method under test literally contains an ORDERING comparison (<, <=, >, >=, compareTo, isBefore, isAfter). If it does, write three tests around that comparison: exactly at the boundary, one unit below, one unit above, and name them after the real threshold value.\n");
        sb.append("- An equality check (==, !=, equals) is NOT a boundary. It has exactly two outcomes, equal and not equal, so write one test for each and stop. Never write \"one below\" and \"one above\" tests for an equality check: both are the not-equal case written twice, and the second one adds nothing.\n");
        sb.append("- If the method contains no ordering comparison, write NO boundary tests. Do not invent a boundary by stubbing a mock to throw for particular argument values: a test that only asserts what you told the mock to do proves nothing about the method under test.\n");
        sb.append("- The ordering comparison may also live in a private helper shown above. If a private helper the method calls contains one, the boundary rule applies to that comparison too.\n");
        sb.append("- Output only Java code, no explanation, no markdown fences.\n");
        sb.append("- Use only the constructors and methods shown above; do not invent any.\n");
        sb.append("- Every type shown in the context is a separate top-level class. Never write Outer.Inner (for example Request.Details) unless the nested class is explicitly shown inside another class above.\n");
        sb.append("- Copy the import line shown above each type you use into your test class.\n");
        sb.append("- Do NOT put stubbings in @BeforeEach. Each test method sets up only the stubs it needs, otherwise Mockito fails with UnnecessaryStubbingException.\n");
        sb.append("- If the method under test returns the result of a mocked call such as repository.save(x), stub it to return its argument: when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));\n");
        sb.append("- If the method under test calls a void method on ANY mock - a @Mock field, an object you created with mock(...), or an object returned by a stubbed call - assert that call with verify(mock).method(...). This includes setters called on a mocked entity before it is saved. On paths where the call must not happen, assert verify(mock, never()).method(...).\n");
        sb.append("- Never call a method on a mock inside an argument matcher. Assign the value to a local variable first and then use eq(localVariable). Writing eq(someMock.getX()) causes InvalidUseOfMatchers.\n");
        sb.append("- If the method under test modifies an object it received as a parameter (for example by calling a setter on it), assert that object's state after the call. verify() cannot detect this, because a parameter is not a mock.\n");
        sb.append("- Never declare a field of a primitive type, String, a wrapper type or Class with @Mock or @Spy. Mockito cannot create them and the whole test class fails to initialise before a single test runs.\n");
        sb.append("- Name every test method as methodUnderTest_scenario_expectedOutcome in lowerCamelCase, for example updateProduct_idNotFound_throwsNoSuchElementException. Never prefix a name with \"test\" and never use Should/When phrasing. The scenario part must describe the actual input condition, not the template you followed.\n");
        sb.append("- Use only JUnit assertion methods (assertEquals, assertTrue, assertThrows and so on). Never use the Java assert keyword: it is disabled unless the JVM runs with -ea, so such a test silently passes.\n");

        return sb.toString();
    }

    // ==================================================================
    // 2) Bagimliligi olmayan siniflar
    //
    //    Tautoloji kurali buraya EKLENMEDI: duz nesne yolunda stub yok,
    //    dolayisiyla "stub'in dondurdugunu geri okuma" durumu olusamaz.
    // ==================================================================

    private String buildPlainObject(ServiceInfo service, MethodInfo method,
                                    List<String> typeContext, List<String> calledBodies,
                                    List<String> importedConstants) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a senior Java test engineer.\n");
        sb.append("Write a JUnit 5 test class for ONE method of a plain Java class.\n");
        sb.append("This class has NO injected dependencies - it is a domain object, not a service.\n\n");

        sb.append("Package: ").append(service.packageName()).append("\n");
        sb.append("Class under test: ").append(service.className()).append("\n\n");

        sb.append("There is nothing to mock. Do NOT use @ExtendWith(MockitoExtension.class), @Mock, @Spy, @InjectMocks or mock(...) anywhere in the test class. Create real objects only.\n\n");

        if (!service.dependencies().isEmpty()) {
            sb.append("Fields of the class (private internal state - reachable ONLY through the public methods below, never directly and never by reflection):\n");
            service.dependencies().forEach(d -> sb.append("  ").append(d).append("\n"));
            sb.append("\n");
        }

        // GETTER/SETTER'LAR DA BURADA LISTELENIR: test hedefi degiller ama duz
        // nesnede durumu kurmanin ve okumanin tek yolu onlar. Listelenmedigi bir
        // kosuda model varliklarini bilemeyip "student.birthDate" yazdi.
        sb.append("Public API of the class under test (use these to reach the state your scenario needs):\n");
        service.methods().forEach(m -> appendSignature(sb, m));
        service.accessors().forEach(m -> appendSignature(sb, m));
        sb.append("\n");

        appendSharedContext(sb, service, method, typeContext, calledBodies, importedConstants);

        sb.append("Rules:\n");
        sb.append("- Create the object with a constructor shown above. No annotations on the test class are needed.\n");
        sb.append("- The fields are private. NEVER assign them directly, NEVER use reflection (Field.setAccessible, getDeclaredField) and NEVER call setters that do not exist. Reach the state your scenario needs by calling the public methods listed above, in the order the class allows.\n");
        sb.append("- Every field you need to read or write has a getter or a setter in the list above. Use them. Writing object.fieldName is a compilation error because the fields are private.\n");
        sb.append("- If a scenario's precondition cannot be reached through the public API, do NOT write that test. A test that cannot set up its own precondition is worse than a missing test.\n");
        sb.append("- Assert the return value, the thrown exception, or the observable state through the getters listed above. Do not assert on fields you cannot read.\n");
        sb.append("- Every helper method you call must be defined inside the test class you output. Never call a helper you did not write.\n");
        sb.append("- Cover every branch: one test for each thrown exception and one for the success path.\n");
        sb.append("- When a test covers a rejected or invalid input, supply a REAL value that violates the rule and let the method's own code reject it. Derive the value from the validation logic shown above: for a length limit pass a string of the wrong length, for a character or format rule pass a string containing the forbidden characters (for example \"a1!_\"), for a null or blank check pass null or \"   \", for a numeric range pass a number outside it. Never force the rejection by stubbing a collaborator to return false or to throw - that asserts what you told the stub to do and proves nothing about the method under test.\n");
        sb.append("- Boundary tests apply ONLY when the body of the method under test literally contains an ORDERING comparison (<, <=, >, >=, compareTo, isBefore, isAfter). If it does, write three tests around that comparison: exactly at the boundary, one unit below, one unit above, and name them after the real threshold value.\n");
        sb.append("- An equality check (==, !=, equals) is NOT a boundary. It has exactly two outcomes, equal and not equal, so write one test for each and stop. Never write \"one below\" and \"one above\" tests for an equality check: both are the not-equal case written twice, and the second one adds nothing.\n");
        sb.append("- If the method contains no ordering comparison, write NO boundary tests. Do not invent a boundary that the code does not contain.\n");
        sb.append("- The ordering comparison may also live in a private helper shown above. If a private helper the method calls contains one, the boundary rule applies to that comparison too.\n");
        sb.append("- Output only Java code, no explanation, no markdown fences.\n");
        sb.append("- Use only the constructors and methods shown above; do not invent any.\n");
        sb.append("- Every type shown in the context is a separate top-level class. Never write Outer.Inner (for example Request.Details) unless the nested class is explicitly shown inside another class above.\n");
        sb.append("- Copy the import line shown above each type you use into your test class.\n");
        sb.append("- Name every test method as methodUnderTest_scenario_expectedOutcome in lowerCamelCase, for example approve_alreadySettled_throwsIllegalStateException. Never prefix a name with \"test\" and never use Should/When phrasing. The scenario part must describe the actual input condition, not the template you followed.\n");
        sb.append("- Use only JUnit assertion methods (assertEquals, assertTrue, assertThrows and so on). Never use the Java assert keyword: it is disabled unless the JVM runs with -ea, so such a test silently passes.\n");

        return sb.toString();
    }

    private void appendSignature(StringBuilder sb, MethodInfo m) {
        sb.append("  ").append(m.returnType()).append(" ")
                .append(m.name()).append("(")
                .append(String.join(", ", m.parameters())).append(")\n");
    }

    // ==================================================================
    // ortak baglam bolumu
    // ==================================================================

    private void appendSharedContext(StringBuilder sb, ServiceInfo service, MethodInfo method,
                                     List<String> typeContext, List<String> calledBodies,
                                     List<String> importedConstants) {
        if (!service.constructors().isEmpty()) {
            sb.append("Constructor (shows how the fields above are initialised - these values are in effect during the test):\n");
            service.constructors().forEach(c -> sb.append(c).append("\n"));
            sb.append("\n");
        }

        if (!importedConstants.isEmpty()) {
            sb.append("Constants used by this method or its helpers (declared in other classes):\n");
            importedConstants.forEach(c -> sb.append("  ").append(c).append("\n"));
            sb.append("\n");
        }

        if (!service.constants().isEmpty()) {
            sb.append("Constants in the class:\n");
            service.constants().forEach(c -> sb.append("  ").append(c).append("\n"));
            sb.append("\n");
        }

        if (!typeContext.isEmpty()) {
            sb.append("Types available in the project (signatures only - do not invent members):\n");
            typeContext.forEach(t -> sb.append(t).append("\n"));
            sb.append("\n");
        }

        sb.append("Method under test:\n");
        sb.append(method.returnType()).append(" ")
                .append(method.name()).append("(")
                .append(String.join(", ", method.parameters())).append(")\n");
        sb.append(method.body()).append("\n\n");

        if (!calledBodies.isEmpty()) {
            sb.append("Bodies of the methods this method calls (understand their preconditions and the order of their checks):\n");
            calledBodies.forEach(b -> sb.append(b).append("\n\n"));
        }

        if (!service.privateMethods().isEmpty()) {
            sb.append("Private helpers (shown only so you understand behaviour - they are NOT accessible from a test class, never call or stub them):\n");
            service.privateMethods().forEach(p ->
                    sb.append(p.name()).append(": ").append(p.body()).append("\n"));
            sb.append("\n");
        }
    }

    // ==================================================================
    // alan siniflandirma
    // ==================================================================

    private static boolean isMockCandidate(String declaration) {
        return !isPrimitive(declaration) && jdkClass(declaration) == null;
    }

    private static boolean isSpyable(String declaration) {
        if (isPrimitive(declaration)) return false;

        String type = simpleType(declaration);
        if (NEVER_MOCKABLE.contains(type)) return false;

        Class<?> c = jdkClass(declaration);
        if (c == null) return false;
        if (c.isPrimitive()) return false;
        return !Modifier.isFinal(c.getModifiers());
    }

    private static boolean isPrimitive(String declaration) {
        return PRIMITIVES.contains(simpleType(declaration));
    }

    /** "List<Order> orders" -> "List", "int count" -> "int" */
    private static String simpleType(String declaration) {
        return declaration.trim().split("[\\s<\\[]")[0];
    }

    private static Class<?> jdkClass(String declaration) {
        String type = simpleType(declaration);
        for (String pkg : List.of("java.lang.", "java.util.", "java.time.", "java.math.")) {
            try {
                return Class.forName(pkg + type);
            } catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    private static boolean isConstructorParameter(ServiceInfo service, String declaration) {
        String field = fieldName(declaration);
        if (field == null) return false;

        return service.constructors().stream().anyMatch(c -> {
            int open = c.indexOf('(');
            int close = c.indexOf(')');
            if (open < 0 || close <= open) return false;
            String params = c.substring(open + 1, close);
            return params.matches("(?s).*\\b" + Pattern.quote(field) + "\\b.*");
        });
    }

    private static boolean initialisedInConstructor(ServiceInfo service, String declaration) {
        String field = fieldName(declaration);
        if (field == null) return false;
        return service.constructors().stream().anyMatch(c -> c.contains(field));
    }

    private static String fieldName(String declaration) {
        String[] parts = declaration.trim().split("\\s+");
        return parts.length < 2 ? null : parts[parts.length - 1];
    }
}