package org.example.prompt;

public class TestRepairPromptBuilder {

    public String build(String originalPrompt, String code, String failures) {
        return """
                %s

                ---
                You previously produced the test class below. It compiles, but some tests fail.
                Fix ONLY the failing tests. Do not rename or delete passing tests.

                Test failures:
                %s

                Current code:
                %s

                Output rules:
                - Output ONLY the corrected test methods, each as a complete @Test method.
                - Do NOT output the class declaration, imports, @Mock fields or @InjectMocks fields.
                - Do NOT output methods you did not change.
                - Keep the original method name for every test you fix.
                - No explanation, no markdown fences.

                Repair rules:
                - UnnecessaryStubbingException means a stub is never reached at runtime: delete exactly those stub lines. Do NOT switch to lenient() and do NOT add @MockitoSettings.
                - Never weaken an assertion to make a test pass. If an expectation is wrong, correct it to match what the code under test actually does.
                - Never delete a failing test instead of fixing it.
                - Use only types that appear in the code under test or in the context above. Do not introduce framework classes that may not be on the test classpath.
                - Never use reflection (Class.forName, getConstructor, newInstance) to work around a compile error. If a type cannot be imported, choose a different exception or scenario using only types shown in the context.
                """.formatted(originalPrompt, failures, code);
    }
}