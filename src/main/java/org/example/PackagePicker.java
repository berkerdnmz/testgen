package org.example;

import org.example.analyzer.TargetSelector;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Hedef projeyi ve hedefleri dosya gezgininden sectirir.
 *
 * Klasor secilirse o paketin tamami, .java dosyasi secilirse yalnizca o sinif
 * hedeflenir. Ikisi ayni pencerede karisik secilebilir (Ctrl ile).
 *
 * Dosya secimi neden gerekli: 300 servisli bir pakette klasor secmek tum
 * pakedi kosmak demek. Kullanici tek tek sinif secebilmeli.
 */
public final class PackagePicker {

    private static final Path LAST_PATH_FILE =
            Paths.get(System.getProperty("user.home"), ".testgen-last-project");

    private PackagePicker() { }

    static {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
    }

    /** @return secilen proje klasoru; iptal edilirse null */
    public static Path pickProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Test üretilecek projeyi seçin (pom.xml'in bulunduğu klasör)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(lastProjectDir());

        if (chooser.showDialog(null, "Seç") != JFileChooser.APPROVE_OPTION) return null;

        Path project = chooser.getSelectedFile().toPath();

        if (!Files.exists(project.resolve("pom.xml"))) {
            int answer = JOptionPane.showConfirmDialog(null,
                    "Bu klasörde pom.xml yok. Yine de devam edilsin mi?",
                    "Uyarı", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return null;
        }

        rememberProject(project);
        return project;
    }

    /**
     * Paket klasorleri ve/veya tek tek .java dosyalari sectirir.
     *
     * @return secilen hedefler; iptal edilirse bos liste
     */
    public static List<TargetSelector> pickTargets(Path projectPath) {
        Path sourceRoot = projectPath.resolve("src").resolve("main").resolve("java");

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Klasör (paketin tamamı) veya .java dosyası seçin - Ctrl ile çoklu seçim");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(javaFilter());
        chooser.setCurrentDirectory(
                Files.isDirectory(sourceRoot) ? sourceRoot.toFile() : projectPath.toFile());

        if (chooser.showDialog(null, "Seç") != JFileChooser.APPROVE_OPTION) return List.of();

        List<TargetSelector> targets = new ArrayList<>();

        for (File f : chooser.getSelectedFiles()) {
            Path p = f.toPath();
            String relative = relativeToSource(sourceRoot, p);

            if (relative == null) {
                System.out.println("Atlandı (src/main/java altında değil): " + p);
                continue;
            }

            if (Files.isDirectory(p)) {
                if (relative.isEmpty()) {
                    System.out.println("Atlandı (kök klasör seçildi, paket adı yok): " + p);
                } else {
                    targets.add(TargetSelector.ofPackage(relative));
                }
            } else if (f.getName().endsWith(".java")) {
                // com/x/service/Foo.java -> com.x.service.Foo
                targets.add(TargetSelector.ofClass(relative.substring(0, relative.length() - ".java".length())));
            } else {
                System.out.println("Atlandı (.java değil): " + p);
            }
        }
        return targets;
    }

    private static FileFilter javaFilter() {
        return new FileFilter() {
            @Override public boolean accept(File f) {
                return f.isDirectory() || f.getName().endsWith(".java");
            }
            @Override public String getDescription() {
                return "Java kaynak dosyaları ve paket klasörleri";
            }
        };
    }

    /**
     * src/main/java altindaki goreli yolu nokta ayracli hale getirir.
     * Kaynak kokunun altinda degilse null.
     */
    private static String relativeToSource(Path sourceRoot, Path target) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Path abs  = target.toAbsolutePath().normalize();

        if (!abs.startsWith(root)) return null;

        return root.relativize(abs).toString()
                .replace(File.separatorChar, '.')
                .replace('/', '.');
    }

    private static File lastProjectDir() {
        try {
            if (Files.exists(LAST_PATH_FILE)) {
                Path last = Paths.get(Files.readString(LAST_PATH_FILE).trim());
                if (Files.isDirectory(last) && last.getParent() != null) {
                    return last.getParent().toFile();
                }
            }
        } catch (Exception ignored) { }
        return new File(System.getProperty("user.home"));
    }

    private static void rememberProject(Path project) {
        try {
            Files.writeString(LAST_PATH_FILE, project.toAbsolutePath().toString());
        } catch (Exception ignored) { }
    }
}