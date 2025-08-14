package dev.bytesizedjoe.k8s;

import dev.bytesizedjoe.cli.HarnessArgs;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@NullMarked
@UtilityClass
public class Workloads {

  public void runWarmup(final KubernetesClient client, final String namespace, final HarnessArgs args) throws InterruptedException {
    final var end = Instant.now().plusSeconds(args.getWarmupSeconds());
    while (Instant.now().isBefore(end)) {
      client.pods().inNamespace(namespace).list();
      createUpdateDeleteConfigMapOnce(client, namespace);
      Thread.sleep(200);
    }
  }

  public CompletableFuture<Void> startWorkload(final KubernetesClient client, final String namespace, final HarnessArgs args) {
    final ExecutorService pool = Executors.newFixedThreadPool(args.getWorkloadThreads());
    final List<Callable<Void>> tasks = new ArrayList<>();

    tasks.add(() -> { runUntilDeadline(args.getDurationSeconds(), () -> { createUpdateDeleteConfigMapOnce(client, namespace); return null; }); return null; });
    tasks.add(() -> { runUntilDeadline(args.getDurationSeconds(), () -> { runPaginatedPodList(client); return null; }); return null; });
    tasks.add(() -> { runUntilDeadline(args.getDurationSeconds(), () -> { runPaginatedServiceList(client); return null; }); return null; });

    for (var i = 0; i < Math.max(0, args.getWorkloadThreads() - tasks.size()); i++) {
      tasks.add(() -> { runUntilDeadline(args.getDurationSeconds(), () -> { createUpdateDeleteConfigMapOnce(client, namespace); return null; }); return null; });
    }

    final CompletableFuture<Void> cf = new CompletableFuture<>();
    final Thread t = new Thread(() -> {
      try {
        final List<Future<Void>> futures = new ArrayList<>();
        for (final var task : tasks) {
          futures.add(pool.submit(task));
        }
        for (final var f : futures) { f.get(); }
      } catch (Throwable t1) {
        cf.completeExceptionally(t1);
        return;
      } finally {
        pool.shutdown();
        try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
      }
      cf.complete(null);
    }, "workload-runner");
    t.setDaemon(true);
    t.start();
    return cf;
  }

  public void createUpdateDeleteConfigMapOnce(final KubernetesClient client, final String namespace) {
    final var name = "ab-" + UUID.randomUUID().toString().substring(0, 8);
    final NonNamespaceOperation<ConfigMap, ?, Resource<ConfigMap>> cms = client.configMaps().inNamespace(namespace);

    final Map<String, String> data1 = Collections.singletonMap("k", randomPayload(256));
    final var cm = new ConfigMapBuilder()
        .withNewMetadata().withName(name).endMetadata()
        .withData(data1)
        .build();

    cms.create(cm);

    for (var i = 0; i < 2; i++) {
      cms.withName(name).edit(c -> new ConfigMapBuilder(c)
          .addToData("k", randomPayload(256))
          .build());
    }

    try { cms.withName(name).delete(); } catch (Exception ignored) {}
  }

  private String randomPayload(final int size) {
    final byte[] b = new byte[size];
    new Random().nextBytes(b);
    return Base64.getEncoder().encodeToString(b);
  }

  public void runPaginatedPodList(final KubernetesClient client) {
    String cont = null;
    var page = 0;
    do {
      final ListOptions opts = new ListOptionsBuilder().withLimit(200L).withContinue(cont).build();
      final PodList list = client.pods().inAnyNamespace().list(opts);
      cont = list.getMetadata() != null ? list.getMetadata().getContinue() : null;
      page++;
    } while (cont != null && !cont.isEmpty() && page < 50);
  }

  public void runPaginatedServiceList(final KubernetesClient client) {
    String cont = null;
    var page = 0;
    do {
      final ListOptions opts = new ListOptionsBuilder().withLimit(200L).withContinue(cont).build();
      final var list = client.services().inAnyNamespace().list(opts);
      cont = list.getMetadata() != null ? list.getMetadata().getContinue() : null;
      page++;
    } while (cont != null && !cont.isEmpty() && page < 50);
  }

  private void runUntilDeadline(final long seconds, final Supplier<Void> op) {
    final var end = Instant.now().plusSeconds(seconds);
    while (Instant.now().isBefore(end)) {
      try {
        op.get();
      } catch (Exception ignored) {
      }
    }
  }
}
