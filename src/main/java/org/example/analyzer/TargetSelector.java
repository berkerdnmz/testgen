package org.example.analyzer;

/**
 * Kullanicinin sectigi hedef: ya bir PAKET ya da tek bir SINIF.
 *
 * Neden tek kavram: secim uc yerde birden kullaniliyor - sinif tarama,
 * PIT konfigurasyonu ve olcum ayristirmasi. Ucu farkli mantik kullanirsa
 * tek dosya secildiginde PIT tum paketi olcer ve rapor yaniltici cikar.
 */
public record TargetSelector(Kind kind, String value) {

    public enum Kind { PACKAGE, CLASS }

    public static TargetSelector ofPackage(String packageName) {
        return new TargetSelector(Kind.PACKAGE, packageName);
    }

    public static TargetSelector ofClass(String fullyQualifiedName) {
        return new TargetSelector(Kind.CLASS, fullyQualifiedName);
    }

    /** Tam nitelikli sinif adi bu seciciye giriyor mu? */
    public boolean matches(String classFqn) {
        if (kind == Kind.CLASS) return classFqn.equals(value);

        int dot = classFqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : classFqn.substring(0, dot);
        // alt paketler de dahil: com.x.service ile com.x.service.impl
        return pkg.equals(value) || pkg.startsWith(value + ".");
    }

    public boolean matches(String packageName, String simpleName) {
        return matches(packageName.isEmpty() ? simpleName : packageName + "." + simpleName);
    }

    /** PIT targetClasses degeri. */
    public String pitClasses() {
        return kind == Kind.PACKAGE ? value + ".*" : value;
    }

    /**
     * PIT targetTests degeri.
     * Sinif secildiginde iki isim mumkun: FooGenTest ve Foo_metot_GenTest;
     * Foo*GenTest ikisini de kapsar.
     */
    public String pitTests() {
        return kind == Kind.PACKAGE ? value + ".*GenTest" : value + "*GenTest";
    }

    /** Konsolda ve metrics.txt'te gorunecek etiket. */
    public String label() {
        return kind == Kind.PACKAGE ? "paket " + value : "sınıf " + value;
    }
}