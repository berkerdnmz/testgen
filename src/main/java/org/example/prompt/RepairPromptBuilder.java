package org.example.prompt;

public class RepairPromptBuilder {

    public String build(String originalPrompt, String code, String errors) {
        return """
            %s

            ---
            You previously produced the test class below and it does NOT compile.
            Fix ONLY the compilation errors. Do not add, remove or rename tests.

            Compiler errors:
            %s

            Current code:
            %s

            Additional rules:
            - Output only the complete corrected Java file, no explanation, no markdown fences.
            - Private methods are NOT accessible from the test class; never call or stub them.
            - A void method can never be stubbed with when(...).thenReturn(...); use doNothing() or doThrow() instead.
            - Only mocks may be stubbed; never stub an object created with new.
            - Never use reflection (Class.forName, getConstructor, newInstance) to work around a compile error. If a type cannot be imported, choose a different exception or scenario using only types shown in the context.
            """.formatted(originalPrompt, errors, code);
    }
}