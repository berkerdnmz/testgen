package org.example.analyzer;

import java.util.List;

public record MethodInfo(
        String name,
        String returnType,
        List<String> parameters,   // "Long studentId"
        List<String> thrownTypes,
        String body
) {}