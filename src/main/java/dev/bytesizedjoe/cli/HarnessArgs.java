package dev.bytesizedjoe.cli;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Optional;

/**
 * Immutable configuration for the harness run.
 */
@NullMarked
@Value
@Builder(toBuilder = true)
public class HarnessArgs {
  Optional<String> label;
  Optional<String> namespace;
  Optional<String> outputDir;

  int repeats;
  long warmupSeconds;
  long durationSeconds;
  int workloadThreads;

  boolean trustCerts;
  int requestTimeoutSeconds;
  int connectTimeoutSeconds;
  int websocketTimeoutSeconds;
  int maxConcurrentRequests;
  int maxConcurrentRequestsPerHost;

  @Singular("tlsVersion")
  List<String> tlsVersions;

  int jfrSeconds;
}
