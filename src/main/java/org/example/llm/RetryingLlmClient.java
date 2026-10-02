package org.example.llm;

/**
 * Tek bir LLM cagrisinin tekrarini yonetir.
 *
 * Iki degisiklik:
 *
 * 1) USTEL BEKLEME. Onceden 5sn/10sn dogrusaldi. Artik 2/5/10/20 sn: ilk
 *    deneme hizli tekrarlaniyor (gecici baglanti kesintileri icin yeterli),
 *    israrli hatalarda ise sunucuya nefes aldiracak kadar uzun bekleniyor.
 *
 * 2) KALICI HATADA HIC BEKLEMEDEN CIK. Onceden her RuntimeException tekrar
 *    deneniyordu; "prompt cok uzun" gibi hicbir zaman duzelmeyecek bir hata
 *    da uc tur ve 15 saniye harciyordu. Artik LlmException.retryable()
 *    false donerse dogrudan firlatilir.
 *
 * NOT: "metot tamamen dustu, kosu sonunda tekrar dene" burasi DEGIL. O metot
 * duzeyinde bir tekrar; TestGenerationStage'in isi.
 */
public class RetryingLlmClient implements LlmClient {

    /** attempt N'den sonraki bekleme (ms). Son eleman tasarsa tekrar kullanilir. */
    private static final long[] BACKOFF_MS = { 2_000L, 5_000L, 10_000L, 20_000L };

    private final LlmClient delegate;
    private final int maxTries;
    private final long[] backoff;

    public RetryingLlmClient(LlmClient delegate) {
        this(delegate, 5, BACKOFF_MS);
    }

    public RetryingLlmClient(LlmClient delegate, int maxTries, long[] backoff) {
        this.delegate = delegate;
        this.maxTries = maxTries;
        this.backoff  = backoff;
    }

    @Override
    public String complete(String prompt) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxTries; attempt++) {
            try {
                return delegate.complete(prompt);
            } catch (RuntimeException e) {
                last = e;

                if (e instanceof LlmException le && !le.retryable()) {
                    System.out.println("  İstek kalıcı olarak reddedildi (HTTP "
                            + le.status() + "), tekrar denenmeyecek: " + le.getMessage());
                    throw le;
                }

                var cause = e.getCause() != null ? e.getCause() : e;
                System.out.println("  İstek başarısız (deneme " + attempt + "/" + maxTries + "): " + cause);

                if (attempt == maxTries) break;

                long wait = waitFor(attempt);
                System.out.println("  " + (wait / 1000) + " sn beklenip tekrar denenecek");
                if (!sleep(wait)) break;
            }
        }
        throw last;
    }

    private long waitFor(int attempt) {
        int i = Math.min(attempt - 1, backoff.length - 1);
        return backoff[i];
    }

    /** @return false ise thread kesildi, tekrar denemeyi birak. */
    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}