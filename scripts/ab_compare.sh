#!/usr/bin/env bash
set -euo pipefail

# Orchestrates back-to-back runs with Vert.x 4 and Vert.x 5
# Usage:
#   scripts/ab_compare.sh \
#     --namespace ab-harness \
#     --repeats 3 --warmup 15 --duration 120 --threads 4 \
#     --out out --label-prefix k8s-client
# Any remaining flags are passed through to the Java harness.

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

ns="ab-harness"
repeats="3"
warmup="15"
duration="120"
threads="4"
out_dir="out"
label_prefix="k8s-client"
extra_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) ns="$2"; shift 2;;
    --repeats) repeats="$2"; shift 2;;
    --warmup) warmup="$2"; shift 2;;
    --duration) duration="$2"; shift 2;;
    --threads) threads="$2"; shift 2;;
    --out) out_dir="$2"; shift 2;;
    --label-prefix) label_prefix="$2"; shift 2;;
    *) extra_args+=("$1"); shift 1;;
  esac
done

run_one() {
  local profile="$1"  # vertx-4 | vertx-5
  local label_suffix="$2" # vertx4 | vertx5
  echo "[ab] Building with profile $profile..."
  mvn -q -f "$root/pom.xml" -P"$profile" -DskipTests package

  echo "[ab] Running harness ($label_suffix)..."
  java -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions \
    -Dtransport.id="$label_suffix" -jar "$root/target/k8s-client-ab-harness-0.1.0.jar" \
    --label "$label_suffix" \
    --namespace "$ns" \
    --out "$out_dir" \
    --repeats "$repeats" \
    --warmup "$warmup" \
    --duration "$duration" \
    --threads "$threads" \
    "${extra_args[@]}"
}

mkdir -p "$root/$out_dir"

run_one vertx-4 vertx4
run_one vertx-5 vertx5

# Helper functions for report generation
latest_run_dir() {
  local variant_dir="$root/$out_dir/$1"
  local latest=""
  shopt -s nullglob
  for d in "$variant_dir"/*/; do
    d=${d%/}
    local name=$(basename "$d")
    if [[ -z "$latest" || "$name" > "$(basename "$latest")" ]]; then
      latest="$d"
    fi
  done
  echo "$latest"
}

parse_gcutil() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo "-|-|-"
    return
  fi
  awk 'NR==2{printf "%s|%s|%s\n", $7, $9, $11}' "$file" 2>/dev/null || echo "-|-|-"
}

to_mb() {
  local num="$1"; local unit="$2"
  case "$unit" in
    G|g) awk -v n="$num" 'BEGIN{printf "%.2f", n*1024.0}' ;;
    M|m|"") awk -v n="$num" 'BEGIN{printf "%.2f", n+0.0}' ;;
    K|k) awk -v n="$num" 'BEGIN{printf "%.2f", n/1024.0}' ;;
    *) awk -v n="$num" 'BEGIN{printf "%.2f", n/1048576.0}' ;;
  esac
}

parse_nmt_committed_mb() {
  local file="$1"; local section="$2"
  if [[ ! -f "$file" ]]; then echo "-"; return; fi
  local line
  line=$(grep -F "${section}" "$file" | head -n1 || true)
  if [[ -z "$line" ]]; then echo "-"; return; fi
  local commit num unit
  commit=$(echo "$line" | sed -E 's/.*committed=([0-9]+)([KMGkmg]?)[Bb]?.*/\1 \2/')
  num=$(echo "$commit" | awk '{print $1}')
  unit=$(echo "$commit" | awk '{print $2}')
  to_mb "$num" "$unit"
}

parse_threads_count() {
  local file="$1"
  if [[ ! -f "$file" ]]; then echo "-"; return; fi
  grep -E '^"' "$file" | wc -l | awk '{print $1}'
}

delta() {
  # numeric delta b - a
  awk -v a="$1" -v b="$2" 'BEGIN{if(a=="-"||b=="-")print "-"; else printf "%+.2f", b-a}'
}

delta_pct() {
  # percent change (b-a)/a * 100
  awk -v a="$1" -v b="$2" 'BEGIN{if(a=="-"||b=="-"||a==0)print "-"; else printf "%+.1f%%", (b-a)/a*100.0}'
}

# Gather metrics for latest runs
vx4_dir=$(latest_run_dir vertx4)
vx5_dir=$(latest_run_dir vertx5)

vx4_run=$(basename "$vx4_dir")
vx5_run=$(basename "$vx5_dir")

vx4_threads=$(parse_threads_count "$vx4_dir/end/thread_dump.txt")
vx5_threads=$(parse_threads_count "$vx5_dir/end/thread_dump.txt")

vx4_nmt_total=$(parse_nmt_committed_mb "$vx4_dir/end/nmt_summary.txt" "Total:")
vx5_nmt_total=$(parse_nmt_committed_mb "$vx5_dir/end/nmt_summary.txt" "Total:")

vx4_nmt_heap=$(parse_nmt_committed_mb "$vx4_dir/end/nmt_summary.txt" "Java Heap (reserved=")
vx5_nmt_heap=$(parse_nmt_committed_mb "$vx5_dir/end/nmt_summary.txt" "Java Heap (reserved=")

IFS='|' read -r vx4_ygc vx4_fgc vx4_gct < <(parse_gcutil "$vx4_dir/end/gcutil.txt")
IFS='|' read -r vx5_ygc vx5_fgc vx5_gct < <(parse_gcutil "$vx5_dir/end/gcutil.txt")

# Build Markdown report
report="$root/$out_dir/REPORT.md"
{
  echo "## A/B Comparison"
  echo
  echo "Base: \`$out_dir\`  •  Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "- **Vert.x 4 run**: \`$vx4_run\`"
  echo "- **Vert.x 5 run**: \`$vx5_run\`"
  echo
  echo "| Metric | Vert.x 4 | Vert.x 5 | Delta | % Change |"
  echo "|---|---:|---:|---:|---:|"
  echo "| Threads | ${vx4_threads} | ${vx5_threads} | $(delta "$vx4_threads" "$vx5_threads") | $(delta_pct "$vx4_threads" "$vx5_threads") |"
  echo "| NMT Total MB | ${vx4_nmt_total} | ${vx5_nmt_total} | $(delta "$vx4_nmt_total" "$vx5_nmt_total") | $(delta_pct "$vx4_nmt_total" "$vx5_nmt_total") |"
  echo "| NMT Heap MB | ${vx4_nmt_heap} | ${vx5_nmt_heap} | $(delta "$vx4_nmt_heap" "$vx5_nmt_heap") | $(delta_pct "$vx4_nmt_heap" "$vx5_nmt_heap") |"
  echo "| YGC (count) | ${vx4_ygc} | ${vx5_ygc} | $(delta "$vx4_ygc" "$vx5_ygc") | $(delta_pct "$vx4_ygc" "$vx5_ygc") |"
  echo "| FGC (count) | ${vx4_fgc} | ${vx5_fgc} | $(delta "$vx4_fgc" "$vx5_fgc") | $(delta_pct "$vx4_fgc" "$vx5_fgc") |"
  echo "| GCT (s) | ${vx4_gct} | ${vx5_gct} | $(delta "$vx4_gct" "$vx5_gct") | $(delta_pct "$vx4_gct" "$vx5_gct") |"
} > "$report"

# Create a simple index to help browse outputs
index="$root/$out_dir/index.txt"
{
  echo "A/B results index"
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Base: $out_dir"
  echo
  echo "- Vert.x 4: $out_dir/vertx4/ ($vx4_run)"
  echo "- Vert.x 5: $out_dir/vertx5/ ($vx5_run)"
  echo "- Report:  $out_dir/REPORT.md"
} > "$index"

echo "[ab] Done. See $index and $report"
