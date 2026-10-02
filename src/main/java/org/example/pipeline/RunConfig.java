package org.example.pipeline;

import org.example.analyzer.TargetSelector;

import java.nio.file.Path;
import java.util.List;

/**
 * Kosunun DEGISMEZ yapilandirmasi.
 *
 * RunContext'ten farki: burada yalnizca basta bilinen ve sonuna kadar
 * degismeyen degerler var. Bir asamanin uretip digerinin tukettigi veri
 * (profil, servisler, baglam, sonuclar) buraya KONULMAZ - o veri artik
 * donus degeri olarak akiyor.
 */
public record RunConfig(Path projectPath,
                        Path sourceRoot,
                        List<TargetSelector> targets,
                        boolean force) {

    public static RunConfig of(Path projectPath, List<TargetSelector> targets, boolean force) {
        return new RunConfig(
                projectPath,
                projectPath.resolve("src").resolve("main").resolve("java"),
                List.copyOf(targets),
                force);
    }
}