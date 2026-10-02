package org.example.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.llm.TokenUsage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asama ciktilarini diske yazar ve geri okur.
 *
 * Bunun sayesinde asamalar FARKLI ZAMANLARDA calistirilabiliyor:
 * bugun baglami cikar, yarin uret, ertesi gun olc. Mumkun olmasinin sebebi
 * her ara verinin saf veri olmasi - AST, ClassLoader ya da acik surec
 * tasiyan hicbir sey asamalar arasinda gecmiyor.
 *
 * NEDEN target/ DEGIL: MetricsCollector olcum icin "mvn clean" kosuyor ve
 * target/ altindaki her seyi siliyor. Artefaktlar orada olsaydi olcum
 * asamasi kendi girdisini silerdi.
 */
public class RunStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** MethodKey bir Map anahtari; JSON anahtarlari string olmak zorunda. */
    public record ContextEntry(MethodKey key, MethodContext context) { }

    private final Path dir;

    public RunStore(Path projectPath) {
        this.dir = projectPath.resolve(".testgen");
    }

    // ------------------------------------------------------------------
    // analiz
    // ------------------------------------------------------------------

    public void saveAnalysis(AnalysisStage.Analysis analysis) {
        write("analysis.json", analysis);
    }

    public AnalysisStage.Analysis loadAnalysis() {
        return read("analysis.json", AnalysisStage.Analysis.class);
    }

    // ------------------------------------------------------------------
    // baglam
    // ------------------------------------------------------------------

    public void saveContexts(Map<MethodKey, MethodContext> contexts) {
        var entries = contexts.entrySet().stream()
                .map(e -> new ContextEntry(e.getKey(), e.getValue()))
                .toList();
        write("contexts.json", entries);
    }

    public Map<MethodKey, MethodContext> loadContexts() {
        var entries = readList("contexts.json", ContextEntry.class);
        if (entries == null) return null;

        var out = new LinkedHashMap<MethodKey, MethodContext>();
        entries.forEach(e -> out.put(e.key(), e.context()));
        return out;
    }

    // ------------------------------------------------------------------
    // uretim
    // ------------------------------------------------------------------

    public void saveOutcome(TestGenerationStage.Outcome outcome) {
        write("outcome.json", outcome);
    }

    public TestGenerationStage.Outcome loadOutcome() {
        return read("outcome.json", TestGenerationStage.Outcome.class);
    }

    public void saveTokens(TokenUsage.Snapshot tokens) {
        write("tokens.json", tokens);
    }

    /** Bulunamazsa sifirli anlik goruntu - rapor yine de basilabilsin. */
    public TokenUsage.Snapshot loadTokens() {
        var t = read("tokens.json", TokenUsage.Snapshot.class);
        return t != null ? t : new TokenUsage.Snapshot(0, 0, 0, 0);
    }

    // ------------------------------------------------------------------
    // ortak
    // ------------------------------------------------------------------

    private void write(String name, Object value) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name);
            MAPPER.writeValue(file.toFile(), value);
            System.out.println("  kaydedildi: " + file);
        } catch (Exception e) {
            // Kayit best-effort: basarisiz olursa kosu devam etsin, yalnizca
            // "sonra devam et" imkani kaybolsun.
            System.out.println("  UYARI: " + name + " yazılamadı: " + e.getMessage());
        }
    }

    private <T> T read(String name, Class<T> type) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) return null;
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (Exception e) {
            System.out.println("  UYARI: " + name + " okunamadı: " + e.getMessage());
            return null;
        }
    }

    private <T> List<T> readList(String name, Class<T> element) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) return null;
        try {
            var listType = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, element);
            return MAPPER.readValue(file.toFile(), listType);
        } catch (Exception e) {
            System.out.println("  UYARI: " + name + " okunamadı: " + e.getMessage());
            return null;
        }
    }
}