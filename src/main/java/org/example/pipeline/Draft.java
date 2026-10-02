package org.example.pipeline;

import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;

/**
 * Bir metot icin URETILMIS ama henuz dogrulanmamis test kodu.
 *
 * Uretim ile dogrulama arasindaki sinir burasi. Uretim paralel kosuyor
 * (ag beklemesi), dogrulama tek seritli (paylasilan src/test/java ve target/).
 * Draft ikisini birbirinden ayiran saf veri.
 *
 * code null ise uretim basarisiz olmustur; error sebebi tasir. Istisnayi
 * paralel is parcacigindan disari firlatmak yerine boyle tasiyoruz - tek bir
 * metodun dusmesi diger 53'unu iptal etmemeli.
 */
public record Draft(ServiceInfo service,
                    MethodInfo method,
                    MethodKey key,
                    String prompt,
                    String code,
                    int promptLength,
                    String error) {

    public static Draft ok(ServiceInfo service, MethodInfo method, MethodKey key,
                           String prompt, String code) {
        return new Draft(service, method, key, prompt, code, prompt.length(), null);
    }

    public static Draft failed(ServiceInfo service, MethodInfo method, MethodKey key,
                               String error) {
        return new Draft(service, method, key, null, null, 0, error);
    }

    public boolean generated() {
        return code != null;
    }
}