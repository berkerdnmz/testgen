package org.example.context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Hedef projenin target/classes klasorundeki derlenmis siniflari okur.
 * Derleme zamaninda kod ureten araclarin (Lombok, MapStruct, record accessor'lari,
 * openapi-generator vb.) ekledigi uyeler burada gercek haliyle bulunur;
 * hangi aracin urettigini bilmeye gerek yoktur.
 *
 * BAGIMLILIK JAR'LARI (A1): ClassLoader'a onceden yalnizca target/classes
 * veriliyordu. Class.forName superclass ve arayuzleri de yuklemek zorunda
 * oldugu icin, bir kutuphane tipinden turemis HEDEF PROJE SINIFLARI bile
 * yuklenemiyor ve sessizce atlaniyordu (catch Throwable). Kucuk benchmark'larda
 * fark edilmedi; bagimliligi bol bir projede indeks neredeyse bos kalabiliyor.
 * BusinessProject'te olculdu: 18 -> 22 sinif.
 *
 * JAR ICERIGI INDEKSLEME (A2): kardes modullerin ve kurumsal ortak
 * kutuphanelerin tipleri jar icinde geliyor ve kaynak agacinda hic yok.
 * Onlari da indeksliyoruz - ama YALNIZCA projenin kendi paket koku altinda,
 * yoksa Spring'in on binlerce sinifi belege girer ve basit-ad cakismasi patlar.
 */
public class CompiledTypeIndex {

    /** Bellek guvenligi: jar'lardan indekslenecek en fazla sinif. */
    private static final int MAX_JAR_CLASSES = 5000;

    private final Map<String, Class<?>> types = new HashMap<>();
    private final boolean available;

    /** Classpath'e eklenen bagimlilik girdisi sayisi; konsolda raporlanir. */
    private int dependencyCount;

    /** Jar'lardan gelen tip sayisi; 0 ise tek modullu proje demektir. */
    private int jarTypes;

    public CompiledTypeIndex(Path projectRoot) {
        Path classes = projectRoot.resolve("target").resolve("classes");

        if (!Files.isDirectory(classes)) {
            available = false;
            return;
        }

        boolean ok = false;
        try {
            List<URL> classpath = new ArrayList<>();
            classpath.add(classes.toUri().toURL());

            List<URL> jars = dependencyJars(projectRoot);
            classpath.addAll(jars);
            dependencyCount = jars.size();

            var loader = new URLClassLoader(
                    classpath.toArray(URL[]::new),
                    CompiledTypeIndex.class.getClassLoader());

            // Once projenin KENDI siniflari: putIfAbsent kullanildigi icin
            // basit ad cakismasinda hedef projenin sinifi her zaman kazanir.
            try (Stream<Path> paths = Files.walk(classes)) {
                for (Path p : paths.filter(x -> x.toString().endsWith(".class")).toList()) {
                    String rel = classes.relativize(p).toString();
                    if (rel.contains("$")) continue;              // ic siniflar

                    String fqn = rel.substring(0, rel.length() - 6)
                            .replace('\\', '.').replace('/', '.');
                    try {
                        Class<?> c = Class.forName(fqn, false, loader);
                        types.putIfAbsent(c.getSimpleName(), c);
                    } catch (Throwable ignored) {
                        // baglamda olmayan bir tipe bagimliysa atla, kaynak koda dusulur
                    }
                }
            }

            // Sonra jar'lar: kardes moduller ve kurumsal ortak kutuphaneler.
            indexJars(jars, loader, basePackage(classes));

            ok = !types.isEmpty();
        } catch (Exception ignored) {
            ok = false;
        }
        available = ok;
    }

    /**
     * Hedef projenin bagimlilik jar'larinin yollari.
     *
     * mvn dependency:build-classpath ciktiyi bir dosyaya yazar; dosyayi okuyup
     * her girdiyi URL'ye ceviriyoruz. Cok modullu projelerde kardes modullerin
     * jar'lari da bu listede gelir - bagimlilik gorunurlugunun temeli budur.
     *
     * Basarisiz olursa BOS LISTE doner ve eski davranisa duseriz; kosu olmez.
     * Basarisizlik sebepleri gercek: kurumsal proxy eklentiyi indiremeyebilir,
     * depo offline olabilir, cok modullu projede kok pom toplayici olabilir.
     */
    private static List<URL> dependencyJars(Path projectRoot) {
        List<URL> urls = new ArrayList<>();
        Path cpFile = projectRoot.resolve("target").resolve("testgen-classpath.txt");

        try {
            String mvn = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";

            ProcessBuilder pb = new ProcessBuilder(mvn, "-q",
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile.toAbsolutePath());
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();

            // Cikti okunmazsa isletim sistemi tamponu dolar ve alt surec askida
            // kalir; bu dongu veri toplamak icin degil, sureci akitmak icin.
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { }
            }
            p.waitFor();

            if (!Files.exists(cpFile)) return urls;

            for (String entry : Files.readString(cpFile).trim()
                    .split(Pattern.quote(File.pathSeparator))) {
                String e = entry.trim();
                if (e.isEmpty()) continue;
                Path jar = Paths.get(e);
                if (Files.exists(jar)) urls.add(jar.toUri().toURL());
            }
        } catch (Exception ignored) {
            // sessizce eski davranis
        }
        return urls;
    }

    /**
     * Hedef projenin kendi paket koku (ornek: com.business, com.microfinanceBank).
     *
     * Neden ilk IKI parca: kardes moduller groupId'yi paylasir ama alt paketi
     * paylasmaz. Loan modulu com.microfinanceBank.Loan.*, common-dto ise
     * com.microfinanceBank.common.* altinda; ortak onek "com.microfinanceBank".
     * Tam ortak oneki alsaydik kardes modulu kacirirdik, tek parca alsaydik
     * (yalnizca "com") tum kutuphaneleri icine alirdik.
     */
    private static String basePackage(Path classes) {
        try (Stream<Path> paths = Files.walk(classes)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".class")).toList()) {
                String rel = classes.relativize(p).toString()
                        .replace('\\', '.').replace('/', '.');
                String[] parts = rel.split("\\.");
                if (parts.length >= 3) {                  // en az a.b.Sinif.class
                    return parts[0] + "." + parts[1];
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Jar'lardaki siniflari indeksler - yalnizca basePackage altindakileri.
     *
     * Onek filtresi vazgecilmez: onsuz Spring, Jackson ve Hibernate'in on
     * binlerce sinifi indekse girer. Hem bellek hem de basit ad cakismasi
     * problemi olur (Status, User, Response gibi adlar her kutuphanede var).
     */
    private void indexJars(List<URL> jars, ClassLoader loader, String basePackage) {
        if (basePackage == null || basePackage.isBlank()) return;

        String prefix = basePackage.replace('.', '/');
        int indexed = 0;

        for (URL u : jars) {
            if (!u.getPath().endsWith(".jar")) continue;
            if (indexed >= MAX_JAR_CLASSES) break;

            try (JarFile jar = new JarFile(Paths.get(u.toURI()).toFile())) {
                for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(prefix)) continue;
                    if (!name.endsWith(".class") || name.contains("$")) continue;

                    String fqn = name.substring(0, name.length() - 6).replace('/', '.');
                    try {
                        Class<?> c = Class.forName(fqn, false, loader);
                        if (types.putIfAbsent(c.getSimpleName(), c) == null) {
                            jarTypes++;
                            if (++indexed >= MAX_JAR_CLASSES) break;
                        }
                    } catch (Throwable ignored) { }
                }
            } catch (Exception ignored) { }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public Optional<Class<?>> find(String simpleName) {
        return Optional.ofNullable(types.get(simpleName));
    }

    public int size() {
        return types.size();
    }

    /** Classpath'e eklenen bagimlilik sayisi. 0 ise mvn cagrisi basarisiz olmustur. */
    public int dependencyCount() {
        return dependencyCount;
    }

    /** Jar'lardan gelen tip sayisi. 0 ise tek modullu proje demektir. */
    public int jarTypes() {
        return jarTypes;
    }
}