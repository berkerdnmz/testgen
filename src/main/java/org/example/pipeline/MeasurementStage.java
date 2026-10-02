package org.example.pipeline;

import org.example.runner.MetricsCollector;
import org.example.runner.PomConfigurer;

/**
 * JaCoCo + PIT olcumu.
 *
 * Uretim sonuclarina hic bakmiyor: diskte ne varsa onu olcuyor. Bu yuzden
 * tek basina da cagrilabilir - "testleri dun urettim, bugun yalnizca olc".
 */
public class MeasurementStage {

    public void run(RunConfig config, String jdkHome) throws Exception {
        var pomConfigurer = new PomConfigurer(config.projectPath());
        try {
            if (pomConfigurer.configure(config.targets())) {
                new MetricsCollector(config.projectPath(), config.targets(), jdkHome).collect();
            } else {
                System.out.println("pom.xml düzenlenemedi, ölçüm atlandı.");
            }
        } finally {
            pomConfigurer.restore();
        }
    }
}