package com.example.harness;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class Main {
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    HarnessArgs a = HarnessArgs.parse(args);

    String label = a.label.orElseGet(() -> System.getProperty("transport.id", readTransportIdFromResource().orElse("vertx-unknown")));
    String runBaseDir = a.outputDir.orElse("out") + File.separator + label;

    // Ensure JVM requirements
    ensureNmtEnabled();

    // Build client with identical configuration
    try (KubernetesClient client = buildClient(a)) {
      // Prepare namespace
      String ns = a.namespace.orElse("ab-harness");
      ensureNamespace(client, ns);

      // Warmup
      log.info("Warmup for {} seconds...", a.warmupSeconds);
      runWarmup(client, ns, a);

      // Repeat runs
      for (int i = 1; i <= a.repeats; i++) {
        String runId = timeStamp() + "-rep" + i;
        Path runDir = Paths.get(runBaseDir, runId);
        Files.createDirectories(runDir);
        log.info("Starting run {} in {}", runId, runDir);

        // Start background watch and log tail
        Watcher<Pod> watcher = new NoopPodWatcher();
        var watch = client.pods().inAnyNamespace().watch(watcher);
        var logTail = startLogTail(client, runDir);

        // Metrics: start snapshot (baseline, before load)
        collectAllMetrics(runDir, "start");

        // Start workload asynchronously for fixed duration
        var workload = startWorkload(client, ns, a);

        // Mid-run snapshot roughly halfway through
        Thread.sleep(TimeUnit.SECONDS.toMillis(Math.max(1, a.durationSeconds / 2)));
        collectAllMetrics(runDir, "mid");

        // Optional JFR mid-run
        if (a.jfrSeconds > 0) {
          recordJfr(runDir, a.jfrSeconds);
        }

        // Wait for workload to finish, then end snapshot
        workload.get();
        collectAllMetrics(runDir, "end");

        // Cleanup
        try { watch.close(); } catch (Exception ignore) {}
        try { if (logTail != null) logTail.close(); } catch (Exception ignore) {}

        // Best-effort cleanup of leftover ConfigMaps from this run
        cleanupRunConfigMaps(client, ns);

        log.info("Completed run {}", runId);

        // Small pause between repeats to stabilize
        Thread.sleep(TimeUnit.SECONDS.toMillis(10));
      }
    }
  }

  private static void ensureNmtEnabled() {
    String nmt = System.getProperty("-XX:NativeMemoryTracking");
    // Can't reliably read VM flag here; instead, warn if jcmd NMT fails later.
  }

  private static String timeStamp() {
    SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd-HHmmss");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    return df.format(new java.util.Date());
  }

  private static KubernetesClient buildClient(HarnessArgs a) {
    Config auto = Config.autoConfigure(null);
    Config config = new ConfigBuilder(auto)
        .withRequestTimeout((int) TimeUnit.SECONDS.toMillis(a.requestTimeoutSeconds))
        .withConnectionTimeout((int) TimeUnit.SECONDS.toMillis(a.connectTimeoutSeconds))
        .withWebsocketTimeout((int) TimeUnit.SECONDS.toMillis(a.websocketTimeoutSeconds))
        .withTrustCerts(a.trustCerts)
        .withMaxConcurrentRequests(a.maxConcurrentRequests)
        .withMaxConcurrentRequestsPerHost(a.maxConcurrentRequestsPerHost)
        // Prefer explicit TLS versions if provided
        .withTlsVersions(a.tlsVersions.toArray(new String[0]))
        .build();

    return new KubernetesClientBuilder().withConfig(config).build();
  }

  private static void ensureNamespace(KubernetesClient client, String ns) {
    try {
      client.namespaces().withName(ns).get();
      if (client.namespaces().withName(ns).get() == null) {
        client.namespaces().createOrReplaceNew().withNewMetadata().withName(ns).endMetadata().done();
      }
    } catch (Exception e) {
      // Try to create if get failed
      client.namespaces().createOrReplaceNew().withNewMetadata().withName(ns).endMetadata().done();
    }
  }

  private static void runWarmup(KubernetesClient client, String ns, HarnessArgs a) throws InterruptedException {
    Instant end = Instant.now().plusSeconds(a.warmupSeconds);
    while (Instant.now().isBefore(end)) {
      // simple operations to initialize pools and caches
      client.pods().inNamespace(ns).list();
      createUpdateDeleteConfigMapOnce(client, ns);
      Thread.sleep(200);
    }
  }

  private static java.util.concurrent.CompletableFuture<Void> startWorkload(KubernetesClient client, String ns, HarnessArgs a) {
    ExecutorService pool = Executors.newFixedThreadPool(a.workloadThreads);
    List<Callable<Void>> tasks = new ArrayList<>();

    // CRUD churn task
    tasks.add(() -> {
      runUntilDeadline(a.durationSeconds, () -> {
        createUpdateDeleteConfigMapOnce(client, ns);
        return null;
      });
      return null;
    });

    // Paginated list task (pods)
    tasks.add(() -> {
      runUntilDeadline(a.durationSeconds, () -> {
        runPaginatedPodList(client);
        return null;
      });
      return null;
    });

    // Paginated list task (services)
    tasks.add(() -> {
      runUntilDeadline(a.durationSeconds, () -> {
        runPaginatedServiceList(client);
        return null;
      });
      return null;
    });

    // Submit additional identical CRUD tasks to generate concurrency
    for (int i = 0; i < Math.max(0, a.workloadThreads - tasks.size()); i++) {
      tasks.add(() -> { runUntilDeadline(a.durationSeconds, () -> { createUpdateDeleteConfigMapOnce(client, ns); return null; }); return null; });
    }

    java.util.concurrent.CompletableFuture<Void> cf = new java.util.concurrent.CompletableFuture<>();
    Thread t = new Thread(() -> {
      try {
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
          futures.add(pool.submit(task));
        }
        for (Future<Void> f : futures) { f.get(); }
      } catch (Throwable t1) {
        cf.completeExceptionally(t1);
        return;
      } finally {
        pool.shutdown();
        try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
      }
      cf.complete(null);
    }, "workload-runner");
    t.setDaemon(true);
    t.start();
    return cf;
  }

  private static void runUntilDeadline(long seconds, Supplier<Void> op) {
    Instant end = Instant.now().plusSeconds(seconds);
    while (Instant.now().isBefore(end)) {
      try {
        op.get();
      } catch (Exception e) {
        // ignore transient errors
      }
    }
  }

  private static void createUpdateDeleteConfigMapOnce(KubernetesClient client, String ns) {
    String name = "ab-" + UUID.randomUUID().toString().substring(0, 8);
    NonNamespaceOperation<ConfigMap, ?, Resource<ConfigMap>> cms = client.configMaps().inNamespace(ns);

    Map<String, String> data1 = Collections.singletonMap("k", randomPayload(256));
    ConfigMap cm = new ConfigMapBuilder()
        .withNewMetadata().withName(name).endMetadata()
        .withData(data1)
        .build();

    cms.create(cm);

    // update a couple times
    for (int i = 0; i < 2; i++) {
      cms.withName(name).edit(c -> new ConfigMapBuilder(c)
          .addToData("k", randomPayload(256))
          .build());
    }

    // delete
    try { cms.withName(name).delete(); } catch (Exception ignored) {}
  }

  private static String randomPayload(int size) {
    byte[] b = new byte[size];
    new Random().nextBytes(b);
    return java.util.Base64.getEncoder().encodeToString(b);
  }

  private static void runPaginatedPodList(KubernetesClient client) {
    String cont = null;
    int page = 0;
    do {
      ListOptions opts = new ListOptionsBuilder().withLimit(200L).withContinue(cont).build();
      PodList list = client.pods().inAnyNamespace().list(opts);
      cont = list.getMetadata() != null ? list.getMetadata().getContinue() : null;
      page++;
    } while (cont != null && !cont.isEmpty() && page < 50);
  }

  private static void runPaginatedServiceList(KubernetesClient client) {
    String cont = null;
    int page = 0;
    do {
      ListOptions opts = new ListOptionsBuilder().withLimit(200L).withContinue(cont).build();
      var list = client.services().inAnyNamespace().list(opts);
      cont = list.getMetadata() != null ? list.getMetadata().getContinue() : null;
      page++;
    } while (cont != null && !cont.isEmpty() && page < 50);
  }

  private static class NoopPodWatcher implements Watcher<Pod> {
    @Override public void eventReceived(Action action, Pod resource) { /* no-op */ }
    @Override public void onClose(WatcherException cause) { }
  }

  private static LogWatch startLogTail(KubernetesClient client, Path runDir) {
    try {
      Optional<Pod> pod = findFirstRunningPod(client);
      if (pod.isEmpty()) { return null; }
      Pod p = pod.get();
      String container = firstRunningContainerName(p).orElse(null);
      if (container == null) { return null; }
      Path logFile = runDir.resolve("logtail-" + p.getMetadata().getNamespace() + "-" + p.getMetadata().getName() + ".log");
      FileOutputStream fos = new FileOutputStream(logFile.toFile());
      return client.pods().inNamespace(p.getMetadata().getNamespace())
          .withName(p.getMetadata().getName())
          .inContainer(container)
          .tailingLines(100)
          .watchLog(fos);
    } catch (Exception e) {
      return null;
    }
  }

  private static Optional<Pod> findFirstRunningPod(KubernetesClient client) {
    List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
    return pods.stream()
        .filter(p -> Objects.equals("Running", Optional.ofNullable(p.getStatus()).map(s -> s.getPhase()).orElse(null)))
        .sorted(Comparator.comparing(p -> p.getMetadata().getNamespace() + "/" + p.getMetadata().getName()))
        .findFirst();
  }

  private static Optional<String> firstRunningContainerName(Pod p) {
    if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null) return Optional.empty();
    for (ContainerStatus cs : p.getStatus().getContainerStatuses()) {
      if (Boolean.TRUE.equals(cs.getReady())) {
        return Optional.ofNullable(cs.getName());
      }
    }
    return Optional.empty();
  }

  private static void cleanupRunConfigMaps(KubernetesClient client, String ns) {
    try {
      client.configMaps().inNamespace(ns).withLabel("app", "ab-harness"); // no-op if not set
      client.configMaps().inNamespace(ns).list().getItems().stream()
          .filter(cm -> cm.getMetadata() != null && cm.getMetadata().getName() != null && cm.getMetadata().getName().startsWith("ab-"))
          .forEach(cm -> {
            try { client.configMaps().inNamespace(ns).withName(cm.getMetadata().getName()).delete(); } catch (Exception ignored) {}
          });
    } catch (Exception ignored) {}
  }

  private static void collectAllMetrics(Path runDir, String phase) {
    try {
      long pid = ProcessHandle.current().pid();
      Path phaseDir = runDir.resolve(phase);
      Files.createDirectories(phaseDir);
      runAndSave(phaseDir.resolve("thread_dump.txt"), jcmdPath(), Long.toString(pid), "Thread.print");
      runAndSave(phaseDir.resolve("nmt_summary.txt"), jcmdPath(), Long.toString(pid), "VM.native_memory", "summary");
      runAndSave(phaseDir.resolve("gcutil.txt"), jstatPath(), "-gcutil", Long.toString(pid), "1", "1");
    } catch (Exception e) {
      log.warn("Failed to collect metrics: {}", e.toString());
    }
  }

  private static void recordJfr(Path runDir, int seconds) {
    try {
      long pid = ProcessHandle.current().pid();
      Path jfr = runDir.resolve("midrun.jfr");
      runAndWait(jcmdPath(), Long.toString(pid), "JFR.start", "name=midrun", "settings=profile", "filename=" + jfr.toAbsolutePath());
      Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
      runAndWait(jcmdPath(), Long.toString(pid), "JFR.stop", "name=midrun");
    } catch (Exception e) {
      log.warn("Failed to record JFR: {}", e.toString());
    }
  }

  private static String jcmdPath() {
    return toolFromJavaHome("jcmd");
  }

  private static String jstatPath() {
    return toolFromJavaHome("jstat");
  }

  private static String toolFromJavaHome(String tool) {
    String javaHome = System.getProperty("java.home");
    Path bin = Paths.get(javaHome, "bin", tool);
    if (Files.exists(bin)) return bin.toString();
    // Some JDKs have java.home pointing to jre/; go up one level
    Path alt = Paths.get(javaHome, "..", "bin", tool).normalize();
    return alt.toString();
  }

  private static void runAndSave(Path outFile, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    try (InputStream is = p.getInputStream()) {
      Files.write(outFile, is.readAllBytes());
    }
    p.waitFor(30, TimeUnit.SECONDS);
  }

  private static void runAndWait(String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.waitFor(30, TimeUnit.SECONDS);
  }

  private static class HarnessArgs {
    final Optional<String> label;
    final Optional<String> namespace;
    final Optional<String> outputDir;
    final int repeats;
    final long warmupSeconds;
    final long durationSeconds;
    final int workloadThreads;
    final boolean trustCerts;
    final int requestTimeoutSeconds;
    final int connectTimeoutSeconds;
    final int websocketTimeoutSeconds;
    final int maxConcurrentRequests;
    final int maxConcurrentRequestsPerHost;
    final List<String> tlsVersions;
    final int jfrSeconds;

    HarnessArgs(Optional<String> label, Optional<String> namespace, Optional<String> outputDir,
                int repeats, long warmupSeconds, long durationSeconds, int workloadThreads,
                boolean trustCerts, int requestTimeoutSeconds, int connectTimeoutSeconds, int websocketTimeoutSeconds,
                int maxConcurrentRequests, int maxConcurrentRequestsPerHost, List<String> tlsVersions, int jfrSeconds) {
      this.label = label;
      this.namespace = namespace;
      this.outputDir = outputDir;
      this.repeats = repeats;
      this.warmupSeconds = warmupSeconds;
      this.durationSeconds = durationSeconds;
      this.workloadThreads = workloadThreads;
      this.trustCerts = trustCerts;
      this.requestTimeoutSeconds = requestTimeoutSeconds;
      this.connectTimeoutSeconds = connectTimeoutSeconds;
      this.websocketTimeoutSeconds = websocketTimeoutSeconds;
      this.maxConcurrentRequests = maxConcurrentRequests;
      this.maxConcurrentRequestsPerHost = maxConcurrentRequestsPerHost;
      this.tlsVersions = tlsVersions;
      this.jfrSeconds = jfrSeconds;
    }

    static HarnessArgs parse(String[] args) {
      Map<String, String> m = new java.util.HashMap<>();
      for (int i = 0; i < args.length; i++) {
        if (args[i].startsWith("--")) {
          String key = args[i].substring(2);
          String val = (i + 1 < args.length && !args[i+1].startsWith("--")) ? args[++i] : "true";
          m.put(key, val);
        }
      }
      Optional<String> label = Optional.ofNullable(m.get("label"));
      Optional<String> namespace = Optional.ofNullable(m.get("namespace"));
      Optional<String> output = Optional.ofNullable(m.get("out"));
      int repeats = Integer.parseInt(m.getOrDefault("repeats", "3"));
      long warm = Long.parseLong(m.getOrDefault("warmup", "15"));
      long dur = Long.parseLong(m.getOrDefault("duration", "120"));
      int threads = Integer.parseInt(m.getOrDefault("threads", "4"));
      boolean trust = Boolean.parseBoolean(m.getOrDefault("trustCerts", "false"));
      int reqT = Integer.parseInt(m.getOrDefault("reqTimeout", "30"));
      int connT = Integer.parseInt(m.getOrDefault("connTimeout", "10"));
      int wsT = Integer.parseInt(m.getOrDefault("wsTimeout", "600"));
      int maxReq = Integer.parseInt(m.getOrDefault("maxRequests", "64"));
      int maxReqHost = Integer.parseInt(m.getOrDefault("maxRequestsPerHost", "32"));
      List<String> tls = parseCsv(m.getOrDefault("tls", "TLSv1.2,TLSv1.3"));
      int jfrSec = Integer.parseInt(m.getOrDefault("jfr", "0"));

      return new HarnessArgs(label, namespace, output, repeats, warm, dur, threads, trust, reqT, connT, wsT, maxReq, maxReqHost, tls, jfrSec);
    }

    private static List<String> parseCsv(String v) {
      if (v == null || v.isBlank()) return List.of();
      String[] parts = v.split(",");
      List<String> out = new ArrayList<>();
      for (String p : parts) {
        out.add(p.trim());
      }
      return out;
    }
  }

  private static Optional<String> readTransportIdFromResource() {
    try (InputStream is = Main.class.getClassLoader().getResourceAsStream("transport.properties")) {
      if (is == null) return Optional.empty();
      java.util.Properties p = new java.util.Properties();
      p.load(is);
      return Optional.ofNullable(p.getProperty("transport.id"));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
