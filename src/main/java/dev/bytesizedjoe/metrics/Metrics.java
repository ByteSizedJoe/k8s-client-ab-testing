package dev.bytesizedjoe.metrics;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@NullMarked
@UtilityClass
public class Metrics {
  private static final Logger log = LoggerFactory.getLogger(Metrics.class);

  public void collectAllMetrics(final Path runDir, final String phase) {
    try {
      final long pid = ProcessHandle.current().pid();
      final Path phaseDir = runDir.resolve(phase);
      Files.createDirectories(phaseDir);
      runAndSave(phaseDir.resolve("thread_dump.txt"), jcmdPath(), Long.toString(pid), "Thread.print");
      runAndSave(phaseDir.resolve("nmt_summary.txt"), jcmdPath(), Long.toString(pid), "VM.native_memory", "summary");
      runAndSave(phaseDir.resolve("gcutil.txt"), jstatPath(), "-gcutil", Long.toString(pid), "1", "1");
    } catch (final Exception e) {
      log.warn("Failed to collect metrics: {}", e.toString());
    }
  }

  public void recordJfr(final Path runDir, final int seconds) {
    try {
      final long pid = ProcessHandle.current().pid();
      final Path jfr = runDir.resolve("midrun.jfr");
      runAndWait(jcmdPath(), Long.toString(pid), "JFR.start", "name=midrun", "settings=profile", "filename=" + jfr.toAbsolutePath());
      Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
      runAndWait(jcmdPath(), Long.toString(pid), "JFR.stop", "name=midrun");
    } catch (Exception e) {
      log.warn("Failed to record JFR: {}", e.toString());
    }
  }

  public String jcmdPath() { return toolFromJavaHome("jcmd"); }
  public String jstatPath() { return toolFromJavaHome("jstat"); }

  private String toolFromJavaHome(final String tool) {
    final var javaHome = System.getProperty("java.home");
    final Path bin = Paths.get(javaHome, "bin", tool);
    if (Files.exists(bin)) return bin.toString();
    final Path alt = Paths.get(javaHome, "..", "bin", tool).normalize();
    return alt.toString();
  }

  private void runAndSave(final Path outFile, final String... cmd) throws IOException, InterruptedException {
    final var pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    final var p = pb.start();
    try (InputStream is = p.getInputStream()) {
      Files.write(outFile, is.readAllBytes());
    }
    p.waitFor(30, TimeUnit.SECONDS);
  }

  private void runAndWait(final String... cmd) throws IOException, InterruptedException {
    final var pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    final var p = pb.start();
    p.waitFor(30, TimeUnit.SECONDS);
  }
}
