package org.example.llm;

/**
 * LLM cagrisinin basarisizligi, HTTP durum koduyla birlikte.
 *
 * Neden gerekli: "tekrar denemeye deger mi" karari durum koduna bagli.
 * 429 ve 5xx gecicidir, tekrar denenir. 400 (prompt cok uzun), 401, 404
 * kalicidir - tekrar denemek hem zaman hem kredi kaybi.
 *
 * status = 0: aga hic cikilamadi (baglanti kesildi, zaman asimi). Bu da
 * gecici sayilir.
 */
public class LlmException extends RuntimeException {

    private final int status;
    private final String body;

    public LlmException(int status, String message, String body, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.body   = body;
    }

    public LlmException(int status, String message) {
        this(status, message, null, null);
    }

    /** Aga cikilamadi. */
    public static LlmException network(Throwable cause) {
        return new LlmException(0, "bağlantı hatası: " + cause, null, cause);
    }

    public int status() {
        return status;
    }

    /** Sunucunun dondurdugu ham govde; hata mesajini anlamak icin. */
    public String body() {
        return body;
    }

    /**
     * Tekrar denemeye deger mi?
     *
     * Bilinmeyen durum kodlarinda (ve status 0'da) TRUE donuyor: yanlis
     * pozitif birkac saniye kaybettirir, yanlis negatif metodu kalici olarak
     * dusurur. Olcum butunlugu acisindan ikincisi cok daha pahali.
     */
    public boolean retryable() {
        return switch (status) {
            case 400, 401, 402, 403, 404, 413, 422 -> false;
            default -> true;
        };
    }
}