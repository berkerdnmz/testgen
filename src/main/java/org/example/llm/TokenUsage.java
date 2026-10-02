package org.example.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kosu boyunca harcanan token'lari biriktirir.
 *
 * Atomik sayaclar: uretim paralellestirildiginde birden fazla is parcacigi
 * ayni anda add() cagiriyor. Duz long ile artirma atomik degildir - kayip
 * guncelleme olur ve rapordaki toplam sessizce eksik cikar. Sessiz olmasi
 * tehlikeli: hicbir hata gorunmez, yalnizca sayilar yanlis olur.
 */
public class TokenUsage {

    private final AtomicLong promptTokens     = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicInteger calls         = new AtomicInteger();
    private final AtomicInteger callsWithoutUsage = new AtomicInteger();

    public void add(long prompt, long completion) {
        promptTokens.addAndGet(prompt);
        completionTokens.addAndGet(completion);
        calls.incrementAndGet();
        if (prompt == 0 && completion == 0) callsWithoutUsage.incrementAndGet();
    }

    public long promptTokens()     { return promptTokens.get(); }
    public long completionTokens() { return completionTokens.get(); }
    public long totalTokens()      { return promptTokens.get() + completionTokens.get(); }
    public int  calls()            { return calls.get(); }
    public int  callsWithoutUsage(){ return callsWithoutUsage.get(); }

    /** Diske yazilabilir donmus kopya; rapor bunu aliyor. */
    public record Snapshot(long promptTokens, long completionTokens,
                           int calls, int callsWithoutUsage) { }

    public Snapshot snapshot() {
        return new Snapshot(promptTokens.get(), completionTokens.get(),
                calls.get(), callsWithoutUsage.get());
    }
}