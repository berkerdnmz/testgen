package org.example.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ProjectProfile(
        boolean maven,
        String testFramework,   // junit5 | junit4 | testng | unknown | none
        String mockLibrary,     // mockito | easymock | jmockit | unknown | none
        Integer javaVersion,    // pom'un istedigi major surum; tespit edilemezse null
        String notes
) {

    public static ProjectProfile of(Path projectRoot) throws Exception {
        Path pom = projectRoot.resolve("pom.xml");

        if (!Files.exists(pom)) {
            return new ProjectProfile(false, "none", "none", null,
                    "pom.xml bulunamadı (Gradle projesi olabilir.)");
        }

        String p = Files.readString(pom);
        String lower = p.toLowerCase();
        boolean starterTest = p.contains("spring-boot-starter-test");

        String framework =
                (starterTest || p.contains("junit-jupiter"))          ? "junit5"
                        : (p.contains("junit-vintage")
                        || p.contains("<artifactId>junit</artifactId>")) ? "junit4"
                        : p.contains("testng")                                   ? "testng"
                        : lower.contains("test")                                 ? "unknown"
                        : "none";

        String mock =
                (starterTest || p.contains("mockito-")) ? "mockito"
                        : p.contains("easymock")                  ? "easymock"
                        : p.contains("jmockit")                   ? "jmockit"
                        : lower.contains("mock")                  ? "unknown"
                        : "none";

        return new ProjectProfile(true, framework, mock, requiredJavaVersion(p), "");
    }

    /**
     * pom'un istedigi Java major surumu.
     * Sirayla bakilir: maven.compiler.release, java.version,
     * maven.compiler.source/target, ve compiler eklentisinin release/source etiketi.
     */
    static Integer requiredJavaVersion(String pom) {
        String[] tags = {
                "maven\\.compiler\\.release",
                "java\\.version",
                "maven\\.compiler\\.source",
                "maven\\.compiler\\.target",
                "release",
                "source"
        };

        for (String tag : tags) {
            Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">")
                    .matcher(pom);
            if (m.find()) {
                Integer v = parseMajor(m.group(1));
                if (v != null) return v;
            }
        }
        return null;
    }

    /** "17" -> 17, "1.8" -> 8 */
    private static Integer parseMajor(String value) {
        try {
            String[] parts = value.trim().split("[._-]");
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) return Integer.parseInt(parts[1]);
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    public String describe() {
        return "Maven: " + maven
                + " | Test: " + testFramework
                + " | Mock: " + mockLibrary
                + " | Java: " + (javaVersion == null ? "?" : javaVersion)
                + (notes.isEmpty() ? "" : " | " + notes);
    }

    /** Devam edilemeyecek durumlar; yoksa null. */
    public String blocker() {
        if (!maven)
            return "Bu araç şu an yalnızca Maven projelerini destekliyor. " + notes;
        if (testFramework.equals("none"))
            return "Hedef projede bir test çerçevesi bulunamadı (junit-jupiter, junit, testng vb.).";
        if (mockLibrary.equals("none"))
            return "Hedef projede bir mocking kütüphanesi bulunamadı (mockito, easymock vb.).";
        return null;
    }

    /** Devam edilir ama kullanici bilsin; yoksa null. */
    public String warning() {
        if (testFramework.equals("unknown"))
            return "Test çerçevesi sürümü tespit edilemedi; JUnit 5 söz dizimi varsayılarak devam ediliyor.";
        if (mockLibrary.equals("unknown"))
            return "Mocking kütüphanesi tespit edilemedi; Mockito varsayılarak devam ediliyor.";
        if (javaVersion == null)
            return "pom.xml'de Java sürümü belirtilmemiş; JDK seçimi yapılmayacak, çalışan JVM kullanılacak.";
        return null;
    }
}