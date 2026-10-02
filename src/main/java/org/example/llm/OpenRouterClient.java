package org.example.llm;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenRouterClient implements LlmClient {

    /** OpenRouter yaniti: "usage":{"prompt_tokens":123,"completion_tokens":456,...} */
    private static final Pattern PROMPT_TOKENS =
            Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_TOKENS =
            Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");

    private final String model;
    private final String apiKey;
    private final TokenUsage usage;

    public OpenRouterClient(String model) {
        this(model, new TokenUsage());
    }

    public OpenRouterClient(String model, TokenUsage usage) {
        this.model  = model;
        this.usage  = usage;
        this.apiKey = System.getenv("OR_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OR_KEY tanımlı değil");
        }
    }

    public TokenUsage usage() {
        return usage;
    }

    @Override
    public String complete(String prompt) {
        // Her istekte yeni istemci: kurumsal proxy havuzdaki baglantiyi kesince
        // eski istemci olu baglantiyi tekrar kullanmaya calisiyordu (GOAWAY).
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        String body = """
        {"model":"%s","messages":[{"role":"user","content":%s}],"provider":{"sort":"throughput"}}
        """.formatted(model, Json.quote(prompt));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Aga hic cikilamadi: gecici sayilir, RetryingLlmClient tekrar dener.
            throw LlmException.network(e);
        }

        if (res.statusCode() != 200) {
            // Durum kodu tasindigi icin RetryingLlmClient 400/401'i bosuna
            // tekrar denemeyecek.
            throw new LlmException(res.statusCode(),
                    "OpenRouter " + res.statusCode() + ": " + shorten(res.body()),
                    res.body(), null);
        }

        recordUsage(res.body());

        try {
            return Json.extractString(res.body(), "content");
        } catch (Exception e) {
            // 200 geldi ama govde beklenen sekilde degil. Tekrar denemeye
            // deger (saglayici bazen bos yanit donuyor), o yuzden status 0.
            throw new LlmException(0, "yanıt ayrıştırılamadı", res.body(), e);
        }
    }

    /**
     * usage alani saglayiciya gore gelmeyebilir; gelmezse sifir eklenir ve
     * TokenUsage bunu ayrica sayar - rapordaki toplam eksikse fark edilsin.
     */
    private void recordUsage(String responseBody) {
        usage.add(firstLong(PROMPT_TOKENS, responseBody),
                firstLong(COMPLETION_TOKENS, responseBody));
    }

    private static long firstLong(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static String shorten(String body) {
        if (body == null) return "";
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}