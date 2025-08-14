package dev.bytesizedjoe.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgsParserTest {

  @Test
  @DisplayName("Should parse defaults when no args provided")
  void parse_expectsDefaults() {
    var cfg = ArgsParser.parse(new String[]{});
    assertEquals(3, cfg.getRepeats());
    assertEquals(15L, cfg.getWarmupSeconds());
    assertEquals(120L, cfg.getDurationSeconds());
    assertEquals(4, cfg.getWorkloadThreads());
    assertFalse(cfg.isTrustCerts());
    assertEquals(30, cfg.getRequestTimeoutSeconds());
    assertEquals(10, cfg.getConnectTimeoutSeconds());
    assertEquals(600, cfg.getWebsocketTimeoutSeconds());
    assertEquals(64, cfg.getMaxConcurrentRequests());
    assertEquals(32, cfg.getMaxConcurrentRequestsPerHost());
    assertTrue(cfg.getTlsVersions().contains("TLSv1.2"));
    assertTrue(cfg.getTlsVersions().contains("TLSv1.3"));
    assertEquals(0, cfg.getJfrSeconds());
  }

  @Test
  @DisplayName("Should parse provided args and override defaults")
  void parse_withArgs_expectsOverrides() {
    var args = new String[] {
        "--label", "x", "--namespace", "ns", "--out", "outdir",
        "--repeats", "5", "--warmup", "1", "--duration", "2", "--threads", "7",
        "--trustCerts", "true", "--reqTimeout", "3", "--connTimeout", "4", "--wsTimeout", "5",
        "--maxRequests", "6", "--maxRequestsPerHost", "7", "--tls", "TLSv1.3", "--jfr", "8"
    };
    var cfg = ArgsParser.parse(args);
    assertEquals("x", cfg.getLabel().orElseThrow());
    assertEquals("ns", cfg.getNamespace().orElseThrow());
    assertEquals("outdir", cfg.getOutputDir().orElseThrow());
    assertEquals(5, cfg.getRepeats());
    assertEquals(1L, cfg.getWarmupSeconds());
    assertEquals(2L, cfg.getDurationSeconds());
    assertEquals(7, cfg.getWorkloadThreads());
    assertTrue(cfg.isTrustCerts());
    assertEquals(3, cfg.getRequestTimeoutSeconds());
    assertEquals(4, cfg.getConnectTimeoutSeconds());
    assertEquals(5, cfg.getWebsocketTimeoutSeconds());
    assertEquals(6, cfg.getMaxConcurrentRequests());
    assertEquals(7, cfg.getMaxConcurrentRequestsPerHost());
    assertEquals(1, cfg.getTlsVersions().size());
    assertEquals("TLSv1.3", cfg.getTlsVersions().get(0));
    assertEquals(8, cfg.getJfrSeconds());
  }
}
