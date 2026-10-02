package org.example.runner;

/**
 * promptLength: rapor "en uzun prompt"u bundan hesapliyor. Onceden
 * MethodWorkflow calisirken RunContext'teki bir alani guncelliyordu -
 * uretim asamasinin rapor alanina yazmasi demekti. Artik sonucun bir
 * parcasi; kim hesaplarsa hesaplasin ayni veriden turetiyor.
 */
public record MethodResult(
        String service,
        String method,
        boolean attempted,
        boolean compiled,
        int repairRounds,
        int testsRun,
        int testsPassed,
        int promptLength
) {}