package org.example.pipeline;

import org.example.analyzer.MethodInfo;
import org.example.analyzer.ServiceInfo;

import java.util.List;

/**
 * Bir hedef metodun kimligi: sinif + metot adi + parametre tipleri.
 *
 * TestNaming'in yerini alir. Overload ayrimi artik TEK bir yerde cozuluyor:
 * dosya adi, baglam haritasinin anahtari ve SONUC tablosundaki satir adi
 * hepsi buradan turetiliyor. Onceden yalniz dosya adi ayirt ediliyordu,
 * tablo iki overload'i ayni satir adiyla gosteriyordu.
 *
 * overloaded bayragi ServiceInfo'ya bakmadan bilinemez, bu yuzden of() ile
 * uretilir ve record'un parcasi olarak tasinir.
 */
public record MethodKey(String className,
                        String methodName,
                        List<String> paramTypes,
                        boolean overloaded) {

    public static MethodKey of(ServiceInfo service, MethodInfo method) {
        boolean overloaded = service.methods().stream()
                .filter(m -> m.name().equals(method.name())).count() > 1;

        List<String> types = method.parameters().stream()
                .map(MethodKey::simpleType)
                .toList();

        return new MethodKey(service.className(), method.name(), types, overloaded);
    }

    /** "LoanRequest request" -> "LoanRequest", "List<Order> orders" -> "List" */
    private static String simpleType(String parameter) {
        return parameter.trim().split("[\\s<]")[0].replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * Test dosyasi adi. Sonek YALNIZCA overload varsa eklenir - aksi halde
     * mevcut projelerdeki tum test dosyalari yeniden adlandirilirdi.
     */
    public String testFileName() {
        String suffix = "";
        if (overloaded) {
            suffix = paramTypes.isEmpty() ? "_NoArgs" : "_" + String.join("_", paramTypes);
        }
        return className + "_" + methodName + suffix + "_GenTest";
    }

    /** SONUC tablosunun METOT sutunu: overload ise imza gorunur. */
    public String display() {
        return overloaded ? methodName + "(" + String.join(",", paramTypes) + ")" : methodName;
    }

    /** Konsol basligi ve failures.txt icin. */
    public String label() {
        return className + "." + display();
    }
}