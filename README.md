### Kubernetes Client A/B Harness

Compare the Fabric8 Kubernetes client using Vert.x 4 vs Vert.x 5 HTTP transports under an identical, repeatable workload. The harness warms up, runs a concurrent workload (CRUD ConfigMaps, paginated Pod/Service listing, cluster-wide Pod watch), collects JVM diagnostics, and organizes run artifacts for side‑by‑side analysis.

## Quick Start

**Automated comparison (recommended):**
```bash
./run.sh
```
This builds both transports and runs them sequentially with identical configurations, generating a comparison report.

**Alternative via Maven:**
```bash
mvn -Pab-compare verify
```

## Prerequisites
- **Java 17 JDK**: Not just a JRE. `jcmd` and `jstat` must be available on PATH (the harness invokes them).
- **Kubernetes access**: KUBECONFIG or in‑cluster config with permissions to:
  - List/watch Pods across namespaces
  - List Services
  - Create/update/delete ConfigMaps in the chosen namespace
- **Maven 3.8+** to build.
- Optional but recommended for native memory data: start the JVM with `-XX:NativeMemoryTracking=summary`.

## Manual Build & Run

**Note:** For automated comparison, use the Quick Start section above. The manual steps below are for individual transport testing.

### Build
Two Maven profiles select the transport at build time:
- `vertx-4` → adds `kubernetes-httpclient-vertx`
- `vertx-5` → adds `kubernetes-httpclient-vertx-5`

```bash
# Vert.x 4 build
mvn -Pvertx-4 -DskipTests package

# Vert.x 5 build
mvn -Pvertx-5 -DskipTests package
```

Outputs a fat jar at `target/k8s-client-ab-harness-0.1.0.jar`.

### Run Individual Transport
To test a single transport (rather than comparing both), build the desired profile and run:

```bash
# Example: Vert.x 4 run
java -XX:NativeMemoryTracking=summary \
     -Dtransport.id=vertx-4 \
     -jar target/k8s-client-ab-harness-0.1.0.jar \
     --label vertx4 --namespace ab-harness --repeats 3 --warmup 15 --duration 120 --threads 4 --out out

# Example: Vert.x 5 run
java -XX:NativeMemoryTracking=summary \
     -Dtransport.id=vertx-5 \
     -jar target/k8s-client-ab-harness-0.1.0.jar \
     --label vertx5 --namespace ab-harness --repeats 3 --warmup 15 --duration 120 --threads 4 --out out
```

- To accept self‑signed clusters during testing, add: `--trustCerts true`.
- The harness creates the namespace if missing and cleans up its own `ab-*` ConfigMaps at the end of each run.

## CLI options
All flags are `--k=v` or `--k v` form. Defaults shown in parentheses.

- **--label**: Label for this run set; appears in output path (default: uses `-Dtransport.id` or `vertx-unknown`).
- **--namespace**: Namespace for workload ConfigMaps (default: `ab-harness`).
- **--out**: Base output directory (default: `out`).
- **--repeats**: How many full runs to execute (default: `3`).
- **--warmup**: Warmup seconds before measuring (default: `15`).
- **--duration**: Duration seconds per run (default: `120`).
- **--threads**: Worker threads for workload (default: `4`).
- **--trustCerts**: Trust all TLS certs (default: `false`).
- **--reqTimeout**: Request timeout seconds (default: `30`).
- **--connTimeout**: Connect timeout seconds (default: `10`).
- **--wsTimeout**: WebSocket timeout seconds (default: `600`).
- **--maxRequests**: Max concurrent HTTP requests (default: `64`).
- **--maxRequestsPerHost**: Max concurrent requests per host (default: `32`).
- **--tls**: Comma‑separated TLS versions, e.g. `TLSv1.2,TLSv1.3` (default). Note: reserved; not currently applied to the client.
- **--jfr**: Record a mid‑run JFR for N seconds (default: `0`, disabled).

## What the harness does
- **Warmup**: Lists Pods, and creates/updates/deletes a small ConfigMap repeatedly.
- **Measurement run** (repeated `--repeats` times):
  - Starts a cluster‑wide Pod watch and tails logs from the first ready container found.
  - Launches concurrent tasks:
    - Repeated ConfigMap create→edit→delete in `--namespace`
    - Paginated Pod listing across all namespaces
    - Paginated Service listing across all namespaces
  - Collects JVM diagnostics at start/mid/end of each run
  - Optionally records a mid‑run JFR for `--jfr` seconds
  - Cleans up `ab-*` ConfigMaps created by the run

## Output layout
Artifacts are placed under `out/<label>/<UTC-timestamp>-repN/`:

- `start/`, `mid/`, `end/`:
  - `thread_dump.txt` (jcmd Thread.print)
  - `nmt_summary.txt` (jcmd VM.native_memory summary)
  - `gcutil.txt` (jstat -gcutil)
- `logtail-<ns>-<pod>.log`: Tail of the first ready container found (if any)
- `midrun.jfr`: Present only if `--jfr > 0`

**Comparison reports:** When using automated comparison (Quick Start), results include a comparison report showing performance differences between transports.

## Transport selection notes
- The transport is selected at build time by activating one Maven profile (`-Pvertx-4` or `-Pvertx-5`).
- The `-Dtransport.id` system property is used only for labeling output; it does not affect which transport is used.

## Troubleshooting
- **Missing jcmd/jstat**: Use a full JDK 17 (not a JRE) or ensure `JAVA_HOME` points to a JDK.
- **Empty or failing NMT summary**: Start the JVM with `-XX:NativeMemoryTracking=summary` before running the jar.
- **Permissions (RBAC) errors**: Ensure your identity can list Pods/Services cluster‑wide and create/update/delete ConfigMaps in `--namespace`.
- **Cluster connectivity/SSL issues**: Use `--trustCerts true` for test clusters with self‑signed certs.

## Code entrypoint
- Main class: `dev.bytesizedjoe.Main`
- Key components: `HarnessRunner`, `KubernetesClientFactory`, `Workloads`, `Metrics`, `ArgsParser`
