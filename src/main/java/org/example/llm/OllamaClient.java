package org.example.llm;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class OllamaClient implements LlmClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String model;

    public OllamaClient(String model) {
        this.model = model;
    }

    @Override
    public String complete(String prompt) {
        try {
            String body = """
                {"model":"%s","prompt":%s,"stream":false}
                """.formatted(model, quote(prompt));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            String json = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return extractResponse(json);

        } catch (Exception e) {
            throw new RuntimeException("Ollama call failed", e);
        }
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "") + "\"";
    }

    private String extractResponse(String json) {
        int i = json.indexOf("\"response\":\"");
        if (i < 0) return json;
        int start = i + 12;
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\') {
                char n = json.charAt(++k);
                if (n == 'u') {
                    String hex = json.substring(k + 1, k + 5);
                    sb.append((char) Integer.parseInt(hex, 16));
                    k += 4;
                } else {
                    sb.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        default -> n;
                    });
                }
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }
}