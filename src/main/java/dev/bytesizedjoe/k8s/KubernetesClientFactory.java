package dev.bytesizedjoe.k8s;

import dev.bytesizedjoe.cli.HarnessArgs;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.http.HttpClient;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

@NullMarked
@UtilityClass
public class KubernetesClientFactory {
  private static final Logger log = LoggerFactory.getLogger(KubernetesClientFactory.class);

  public KubernetesClient build(final HarnessArgs args) {
    // Discover which HTTP client provider instances are present
    final List<HttpClient.Factory> discoveredFactories = new ArrayList<>();
    final List<String> discoveredNames = new ArrayList<>();
    try {
      for (final HttpClient.Factory factory : ServiceLoader.load(HttpClient.Factory.class)) {
        discoveredFactories.add(factory);
        discoveredNames.add(factory.getClass().getName());
      }
    } catch (Throwable ignored) { }
    if (!discoveredNames.isEmpty()) {
      log.info("Detected HTTP client providers: {}", discoveredNames);
    } else {
      log.info("No HTTP client providers discovered via ServiceLoader (unexpected)");
    }

    // Try to log Vert.x core presence/version if available without compile-time dependency
    try {
      final Class<?> clazz = Class.forName("io.vertx.core.Vertx");
      final Package p = clazz.getPackage();
      final String ver = p != null ? p.getImplementationVersion() : null;
      log.info("Vert.x core detected{}{}", ver != null ? " version " : "", ver != null ? ver : "");
    } catch (Throwable t) {
      log.debug("Vert.x core not present or version unknown");
    }

    // Detect if Vert.x 5 transport-specific classes from Fabric8 are present
    boolean vertx5TransportDetected = false;
    try {
      // Classes only present in the Vert.x 5 transport module
      Class.forName("io.fabric8.kubernetes.client.vertx.VertxHttpClientConfiguration");
      Class.forName("io.fabric8.kubernetes.client.vertx.VertxAsyncBody");
      vertx5TransportDetected = true;
    } catch (Throwable t) {
      // ignore
    }
    log.info("Fabric8 Vert.x 5 transport detected: {}", vertx5TransportDetected);

    final var auto = Config.autoConfigure(null);
    final Config config = new ConfigBuilder(auto)
        .withRequestTimeout((int) TimeUnit.SECONDS.toMillis(args.getRequestTimeoutSeconds()))
        .withConnectionTimeout((int) TimeUnit.SECONDS.toMillis(args.getConnectTimeoutSeconds()))
        .withTrustCerts(args.isTrustCerts())
        .withMaxConcurrentRequests(args.getMaxConcurrentRequests())
        .withMaxConcurrentRequestsPerHost(args.getMaxConcurrentRequestsPerHost())
        .build();

    final String preferRaw = System.getProperty("k8s.httpFactory",
        System.getProperty("transport.id", ""));
    final String prefer = preferRaw.toLowerCase(Locale.ROOT);

    final boolean vertxTransportDiscovered = discoveredNames.stream()
        .anyMatch(n -> n.contains(".kubernetes.client.vertx."));

    HttpClient.Factory chosen = null;
    // Explicit preference via system property
    if (!prefer.isEmpty()) {
      // Common aliases
      if (prefer.contains("vertx")) {
        chosen = discoveredFactories.stream()
            .filter(f -> f.getClass().getName().contains(".kubernetes.client.vertx."))
            .findFirst().orElse(null);
        if (chosen != null) {
          log.info("Preferring Vert.x HTTP client provider (via system property: '{}')", preferRaw);
        }
      } else if (prefer.contains("okhttp")) {
        chosen = discoveredFactories.stream()
            .filter(f -> f.getClass().getName().contains(".kubernetes.client.okhttp."))
            .findFirst().orElse(null);
        if (chosen != null) {
          log.info("Preferring OkHttp HTTP client provider (via system property: '{}')", preferRaw);
        }
      }
      // If the property looks like a fully-qualified class name, try to match exactly
      if (chosen == null && preferRaw.contains(".")) {
        chosen = discoveredFactories.stream()
            .filter(f -> f.getClass().getName().equals(preferRaw))
            .findFirst().orElse(null);
        if (chosen != null) {
          log.info("Preferring HTTP client provider by FQCN (via system property): {}", preferRaw);
        }
      }
      if (chosen == null) {
        log.warn("Requested HTTP client provider '{}' not available among discovered providers {}",
            preferRaw, discoveredNames);
      } else {
        // Sanity checks for vertx4 vs vertx5 intent vs classpath reality
        if ((prefer.contains("vertx5") || prefer.contains("vertx-5")) && !vertx5TransportDetected) {
          log.warn("Preference indicates Vert.x 5, but Vert.x 5 transport classes were not detected on the classpath");
        }
        if ((prefer.contains("vertx4") || prefer.contains("vertx-4")) && (!vertxTransportDiscovered || vertx5TransportDetected)) {
          log.warn("Preference indicates Vert.x 4, but Vert.x 5 transport detected or no Vert.x transport present");
        }
      }
    }

    // If nothing explicitly requested, prefer Vert.x provider when present; rely on profile to pick 4 vs 5
    if (chosen == null) {
      chosen = discoveredFactories.stream()
          .filter(f -> f.getClass().getName().contains(".kubernetes.client.vertx."))
          .findFirst().orElse(null);
      if (chosen != null) {
        log.info("Preferring Vert.x HTTP client provider (auto)");
      }
    }
    // Otherwise prefer OkHttp if present
    if (chosen == null) {
      chosen = discoveredFactories.stream()
          .filter(f -> f.getClass().getName().contains(".kubernetes.client.okhttp."))
          .findFirst().orElse(null);
      if (chosen != null) {
        log.info("Preferring OkHttp HTTP client provider (auto)");
      }
    }

    if (chosen != null) {
      log.info("Selected HTTP client provider: {}", chosen.getClass().getName());
      return new KubernetesClientBuilder().withConfig(config).withHttpClientFactory(chosen).build();
    } else {
      log.info("No explicit provider selected; deferring to Fabric8 default selection");
      return new KubernetesClientBuilder().withConfig(config).build();
    }
  }
}
