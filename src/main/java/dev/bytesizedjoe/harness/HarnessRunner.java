package dev.bytesizedjoe.harness;

import dev.bytesizedjoe.cli.HarnessArgs;
import dev.bytesizedjoe.k8s.Pods;
import dev.bytesizedjoe.k8s.Workloads;
import dev.bytesizedjoe.metrics.Metrics;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

@NullMarked
@Slf4j
@RequiredArgsConstructor
public class HarnessRunner {
  private final KubernetesClient client;

  public void run(final HarnessArgs args) throws Exception {
    final var label = args.getLabel().orElseGet(() -> System.getProperty("transport.id", "vertx-unknown"));
    final var runBaseDir = args.getOutputDir().orElse("out") + File.separator + label;

    final var ns = args.getNamespace().orElse("ab-harness");
    ensureNamespace(ns);

    log.info("Warmup for {} seconds...", args.getWarmupSeconds());
    Workloads.runWarmup(client, ns, args);

    for (var i = 1; i <= args.getRepeats(); i++) {
      final var runId = timeStamp() + "-rep" + i;
      final Path runDir = Paths.get(runBaseDir, runId);
      Files.createDirectories(runDir);
      log.info("Starting run {} in {}", runId, runDir);

      final Watcher<Pod> watcher = new Pods.NoopPodWatcher();
      final var watch = client.pods().inAnyNamespace().watch(watcher);
      final var logTail = startLogTail(runDir);

      Metrics.collectAllMetrics(runDir, "start");

      final var workload = Workloads.startWorkload(client, ns, args);

      Thread.sleep(TimeUnit.SECONDS.toMillis(Math.max(1, args.getDurationSeconds() / 2)));
      Metrics.collectAllMetrics(runDir, "mid");

      if (args.getJfrSeconds() > 0) {
        Metrics.recordJfr(runDir, args.getJfrSeconds());
      }

      workload.get();
      Metrics.collectAllMetrics(runDir, "end");

      try { watch.close(); } catch (Exception ignore) {}
      try { if (logTail != null) logTail.close(); } catch (Exception ignore) {}

      cleanupRunConfigMaps(ns);

      log.info("Completed run {}", runId);
      Thread.sleep(TimeUnit.SECONDS.toMillis(10));
    }

    // Final completion log to make end-of-runs explicit
    log.info("All runs complete. Artifacts available under {}", runBaseDir);
  }

  private void ensureNamespace(final String namespace) {
    try {
      if (client.namespaces().withName(namespace).get() == null) {
        final var ns = new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build();
        client.namespaces().resource(ns).create();
      }
    } catch (Exception e) {
      final var ns = new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build();
      try { client.namespaces().resource(ns).create(); } catch (Exception ignored) { }
    }
  }

  private LogWatch startLogTail(final Path runDir) {
    try {
      final Optional<Pod> pod = Pods.findFirstRunningPod(client);
      if (pod.isEmpty()) return null;
      final var p = pod.get();
      final var container = Pods.firstRunningContainerName(p).orElse(null);
      if (container == null) return null;
      final Path logFile = runDir.resolve("logtail-" + p.getMetadata().getNamespace() + "-" + p.getMetadata().getName() + ".log");
      final var fos = new FileOutputStream(logFile.toFile());
      return client.pods().inNamespace(p.getMetadata().getNamespace())
          .withName(p.getMetadata().getName())
          .inContainer(container)
          .tailingLines(100)
          .watchLog(fos);
    } catch (Exception e) {
      return null;
    }
  }

  private void cleanupRunConfigMaps(final String namespace) {
    try {
      client.configMaps().inNamespace(namespace).list().getItems().stream()
          .filter(cm -> cm.getMetadata() != null && cm.getMetadata().getName() != null && cm.getMetadata().getName().startsWith("ab-"))
          .forEach(cm -> { try { client.configMaps().inNamespace(namespace).withName(cm.getMetadata().getName()).delete(); } catch (Exception ignored) {} });
    } catch (Exception ignored) {
    }
  }

  private String timeStamp() {
    final var df = new SimpleDateFormat("yyyyMMdd-HHmmss");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    return df.format(new java.util.Date());
  }
}
