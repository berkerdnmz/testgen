package org.example.analyzer;

import java.util.List;

/**
 * accessors: basit getter/setter'lar. Test HEDEFI degiller (test edilecek
 * davranislari yok) ama prompt'ta GORUNUR olmalilar - duz nesne testinde
 * senaryonun durumunu kurmanin ve sonucu okumanin tek yolu bunlar.
 *
 * Ayrimin sebebi olculdu: getter'lar prompt'tan tamamen cikarilinca model
 * onlarin varligini bilemedi ve "student.birthDate" gibi dogrudan alan
 * erisimi yazdi - "birthDate has private access" hatasi yirmi kez tekrarladi.
 */
public record ServiceInfo(
        String packageName,
        String className,
        List<String> dependencies,
        List<String> constants,
        List<String> constructors,
        List<MethodInfo> methods,
        List<MethodInfo> accessors,
        List<MethodInfo> privateMethods
) {}