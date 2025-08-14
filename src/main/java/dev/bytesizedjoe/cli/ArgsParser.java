package dev.bytesizedjoe.cli;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@NullMarked
@UtilityClass
public class ArgsParser {

  public HarnessArgs parse(final String[] args) {
    final Map<String, String> map = new HashMap<>();
    for (var i = 0; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        final var key = args[i].substring(2);
        final var value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
        map.put(key, value);
      }
    }

    final var label = Optional.ofNullable(map.get("label"));
    final var namespace = Optional.ofNullable(map.get("namespace"));
    final var outputDir = Optional.ofNullable(map.get("out"));

    final var repeats = Integer.parseInt(map.getOrDefault("repeats", "3"));
    final var warmup = Long.parseLong(map.getOrDefault("warmup", "15"));
    final var duration = Long.parseLong(map.getOrDefault("duration", "120"));
    final var threads = Integer.parseInt(map.getOrDefault("threads", "4"));
    final var trust = Boolean.parseBoolean(map.getOrDefault("trustCerts", "false"));
    final var reqT = Integer.parseInt(map.getOrDefault("reqTimeout", "30"));
    final var connT = Integer.parseInt(map.getOrDefault("connTimeout", "10"));
    final var wsT = Integer.parseInt(map.getOrDefault("wsTimeout", "600"));
    final var maxReq = Integer.parseInt(map.getOrDefault("maxRequests", "64"));
    final var maxReqHost = Integer.parseInt(map.getOrDefault("maxRequestsPerHost", "32"));
    final var tls = parseCsv(map.getOrDefault("tls", "TLSv1.2,TLSv1.3"));
    final var jfrSec = Integer.parseInt(map.getOrDefault("jfr", "0"));

    return HarnessArgs.builder()
        .label(label)
        .namespace(namespace)
        .outputDir(outputDir)
        .repeats(repeats)
        .warmupSeconds(warmup)
        .durationSeconds(duration)
        .workloadThreads(threads)
        .trustCerts(trust)
        .requestTimeoutSeconds(reqT)
        .connectTimeoutSeconds(connT)
        .websocketTimeoutSeconds(wsT)
        .maxConcurrentRequests(maxReq)
        .maxConcurrentRequestsPerHost(maxReqHost)
        .tlsVersions(tls)
        .jfrSeconds(jfrSec)
        .build();
  }

  private List<String> parseCsv(final String value) {
    if (value == null || value.isBlank()) return List.of();
    final var parts = value.split(",");
    final List<String> out = new ArrayList<>(parts.length);
    for (final var p : parts) {
      out.add(p.trim());
    }
    return out;
  }
}
