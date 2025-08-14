package dev.bytesizedjoe;

import dev.bytesizedjoe.cli.ArgsParser;
import dev.bytesizedjoe.cli.HarnessArgs;
import dev.bytesizedjoe.harness.HarnessRunner;
import dev.bytesizedjoe.k8s.KubernetesClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(final String[] args) throws Exception {
    final HarnessArgs config = ArgsParser.parse(args);
    checkNativeMemoryFlag();
    try (final var client = KubernetesClientFactory.build(config)) {
      new HarnessRunner(client).run(config);
    }
    log.info("Finished all runs. Exiting now.");
    // Ensure we don't linger due to any non-daemon threads in dependencies
    System.exit(0);
  }

  private static void checkNativeMemoryFlag() {
    final var nmt = System.getProperty("-XX:NativeMemoryTracking");
    // Can't reliably read VM flag here; instead, warn if jcmd NMT fails later.
    if (nmt != null) {
      log.debug("NMT property present: {}", nmt);
    }
  }
}
