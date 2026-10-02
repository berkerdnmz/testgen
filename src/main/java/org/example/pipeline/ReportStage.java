package org.example.pipeline;

import org.example.llm.TokenUsage;
import org.example.runner.MethodResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * Konsol sonuc tablosu ve failures.txt.
 *
 * Saf raporlama: hicbir sey uretmez, hicbir sey degistirmez. Proje yolunu
 * bile bilmiyor - yalnizca sonuclari ve sayaci aliyor.
 */
public class ReportStage {

    public void run(List<MethodResult> results, String failureLog,
                    TokenUsage.Snapshot tokens) throws Exception {

        System.out.println("\n================ SONUÇ ================");
        System.out.printf("%-28s %-22s %-9s %-6s %s%n",
                "SERVİS", "METOT", "DERLENDİ", "ONARIM", "TEST");

        for (var r : results) {
            System.out.printf("%-28s %-22s %-9s %-6d %d/%d%n",
                    r.service(), r.method(),
                    r.compiled() ? "evet" : "hayır",
                    r.repairRounds(), r.testsPassed(), r.testsRun());
        }

        long attempted  = results.stream().filter(MethodResult::attempted).count();
        long compiled   = results.stream().filter(MethodResult::compiled).count();
        long zeroRepair = results.stream()
                .filter(r -> r.compiled() && r.repairRounds() == 0).count();
        int totalTests  = results.stream().mapToInt(MethodResult::testsRun).sum();
        int totalPassed = results.stream().mapToInt(MethodResult::testsPassed).sum();

        var longest = results.stream()
                .filter(r -> r.promptLength() > 0)
                .max(Comparator.comparingInt(MethodResult::promptLength));

        System.out.println("---------------------------------------");
        System.out.printf("Metot sayısı      : %d%n", results.size());
        System.out.printf("Denenen           : %d/%d%n", attempted, results.size());
        System.out.printf("Compile rate      : %d/%d%n", compiled, attempted);
        System.out.printf("Onarımsız derlenen: %d/%d%n", zeroRepair, attempted);
        System.out.printf("Pass rate         : %d/%d%n", totalPassed, totalTests);
        longest.ifPresent(r -> System.out.printf(
                "En uzun prompt    : %d karakter (~%d token) - %s.%s%n",
                r.promptLength(), r.promptLength() / 4, r.service(), r.method()));
        System.out.printf("LLM çağrısı      : %d%n", tokens.calls());
        System.out.printf("Token            : %d giriş + %d çıkış = %d%n",
                tokens.promptTokens(), tokens.completionTokens(),
                tokens.promptTokens() + tokens.completionTokens());
        if (tokens.callsWithoutUsage() > 0) {
            System.out.printf("UYARI: %d çağrıda usage gelmedi, toplam eksik%n",
                    tokens.callsWithoutUsage());
        }

        Files.writeString(Paths.get("failures.txt"), failureLog);
        System.out.println("\nHata detayları: failures.txt");
    }
}