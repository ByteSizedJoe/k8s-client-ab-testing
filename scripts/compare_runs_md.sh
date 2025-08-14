#!/usr/bin/env bash
set -euo pipefail

# Generate a quick Markdown comparison of two variants (vertx4 vs vertx5)
# Usage: scripts/compare_runs_md.sh <base_out_dir> [variantA] [variantB]
# Defaults: variantA=vertx4 variantB=vertx5

base_dir=${1:-out}
varA=${2:-vertx4}
varB=${3:-vertx5}

latest_run_dir() {
  local variant_dir="$base_dir/$1"
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
  # Expect header on line 1 and data on line 2
  # Columns: S0 S1 E O M CCS YGC YGCT FGC FGCT GCT
  awk 'NR==2{printf "%s|%s|%s\n", $7, $9, $11}' "$file" 2>/dev/null || echo "-|-|-"
}

summarize_variant() {
  local vlabel="$1"
  local rundir
  rundir=$(latest_run_dir "$vlabel")
  if [[ -z "$rundir" ]]; then
    echo "| $vlabel | - | - | - | - | - | - | - | - | - |"
    return
  fi
  local runid
  runid=$(basename "$rundir")
  local ygc fgc gct
  IFS='|' read -r ygc fgc gct < <(parse_gcutil "$rundir/end/gcutil.txt")
  # Threads and NMT from end-of-run captures
  local threads nmt_total_mb nmt_heap_mb
  threads=$(parse_threads_count "$rundir/end/thread_dump.txt")
  nmt_total_mb=$(parse_nmt_committed_mb "$rundir/end/nmt_summary.txt" "Total:")
  nmt_heap_mb=$(parse_nmt_committed_mb "$rundir/end/nmt_summary.txt" "Java Heap (reserved=")
  # Count files and total size
  local files_count size_kb
  files_count=0
  shopt -s globstar nullglob
  for f in "$rundir"/**/*; do
    [[ -f "$f" ]] && ((files_count++)) || true
  done
  size_kb=$(du -sk "$rundir" | awk '{print $1}')
  local size_mb
  size_mb=$(awk -v kb="$size_kb" 'BEGIN{printf "%.2f", kb/1024.0}')
  echo "| $vlabel | $runid | ${threads:--} | ${nmt_total_mb:--} | ${nmt_heap_mb:--} | ${ygc:--} | ${fgc:--} | ${gct:--} | $files_count | ${size_mb} MB |"
}

to_mb() {
  # Args: <number> <unit>
  # unit can be empty, K, M, G (optionally with a trailing B in source)
  local num="$1"; local unit="$2"
  case "$unit" in
    G|g) awk -v n="$num" 'BEGIN{printf "%.2f", n*1024.0}' ;;
    M|m|"") awk -v n="$num" 'BEGIN{printf "%.2f", n+0.0}' ;;
    K|k) awk -v n="$num" 'BEGIN{printf "%.2f", n/1024.0}' ;;
    *) awk -v n="$num" 'BEGIN{printf "%.2f", n/1048576.0}' ;;
  esac
}

parse_nmt_committed_mb() {
  # Args: <file> <section-prefix>
  # section-prefix examples: "Total:" or "Java Heap (reserved=" or "Thread (reserved="
  local file="$1"; local section="$2"
  if [[ ! -f "$file" ]]; then echo "-"; return; fi
  local line
  line=$(grep -F "${section}" "$file" | head -n1 || true)
  if [[ -z "$line" ]]; then echo "-"; return; fi
  # Extract committed=NUM[UNIT][B]
  local commit num unit
  commit=$(echo "$line" | sed -E 's/.*committed=([0-9]+)([KMGkmg]?)[Bb]?.*/\1 \2/')
  num=$(echo "$commit" | awk '{print $1}')
  unit=$(echo "$commit" | awk '{print $2}')
  to_mb "$num" "$unit"
}

parse_threads_count() {
  local file="$1"
  if [[ ! -f "$file" ]]; then echo "-"; return; fi
  # Count lines starting with a double quote which denote thread headers
  grep -E '^"' "$file" | wc -l | awk '{print $1}'
}

out_md="$base_dir/COMPARE.md"
{
  echo "# A/B Summary"
  echo
  echo "Base dir: \`$base_dir\`  (generated $(date -u +%Y-%m-%dT%H:%M:%SZ))"
  echo
  echo "| Variant | Run ID | Threads | NMT Total MB | NMT Heap MB | YGC | FGC | GCT | Files | Size |"
  echo "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"
  summarize_variant "$varA"
  summarize_variant "$varB"
} > "$out_md"

echo "Wrote $out_md"
