package org.example.pipeline;

import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;
import org.example.context.CompiledTypeIndex;
import org.example.context.ContextExtractor;
import org.example.context.TypeIndex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tüm hedef metotların bağlamını TOPLU olarak çıkarır.
 *
 * TypeIndex ve CompiledTypeIndex bu asamanin ic detayi - ne parametre olarak
 * girer ne donus degeri olarak cikar (biri AST, digeri URLClassLoader tutuyor).
 *
 */
public class ContextStage {

    public Map<MethodKey, MethodContext> extract(RunConfig config,
                                                 List<ServiceInfo> services) throws Exception {
        var index = new TypeIndex(config.sourceRoot());
        if (index.unparsed() > 0) {
            System.out.println("UYARI: " + index.unparsed()
                    + " kaynak dosya ayrıştırılamadı, bağlamda görünmeyecek.");
        }
        if (index.ambiguousCount() > 0) {
            System.out.println("Çakışan basit ad: " + index.ambiguousCount()
                    + " (bu tiplere import müdahalesi yapılmayacak)");
        }

        var bytecode = new CompiledTypeIndex(config.projectPath());
        System.out.println("Bağımlılık jar'ı: " + bytecode.dependencyCount()
                + " | jar'dan gelen tip: " + bytecode.jarTypes());
        System.out.println("Derlenmiş sınıf sayısı: " + bytecode.size());
        if (!bytecode.isAvailable()) {
            System.out.println("UYARI: bytecode katmanı devre dışı, yalnızca kaynak kod kullanılacak.");
        }

        var extractor = new ContextExtractor(index, bytecode);
        var contexts = new LinkedHashMap<MethodKey, MethodContext>();

        for (ServiceInfo service : services) {
            for (MethodInfo method : service.methods()) {
                contexts.put(MethodKey.of(service, method), new MethodContext(
                        extractor.extract(service, method),
                        extractor.voidMethods(service),
                        extractor.calledMethodBodies(service, method),
                        extractor.usedConstants(service, method)));
            }
        }

        // Kaynagi olmayan (kardes modul / kurumsal ortak kutuphane) tipler.
        if (extractor.compiledOnlyTypes() > 0) {
            System.out.println("Yalnızca bytecode'dan gelen tip: "
                    + extractor.compiledOnlyTypes());
        }

        return contexts;
    }
}