#!/usr/bin/env bash
# Run the Distributed Search test suite against an explicit shared-floor Lucene build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -n "${LUCENE_ROOT:-}" ]]; then
  LUCENE_CORE_JAR_DIR="${LUCENE_CORE_JAR_DIR:-$LUCENE_ROOT/lucene/core/build/libs}"
  LUCENE_SANDBOX_JAR_DIR="${LUCENE_SANDBOX_JAR_DIR:-$LUCENE_ROOT/lucene/sandbox/build/libs}"
  LUCENE_MODULE_JAR_DIRS="${LUCENE_MODULE_JAR_DIRS:-$LUCENE_ROOT/lucene/analysis/common/build/libs,$LUCENE_ROOT/lucene/queryparser/build/libs}"
fi

: "${LUCENE_CORE_JAR_DIR:?Set LUCENE_ROOT or LUCENE_CORE_JAR_DIR}"
: "${LUCENE_SANDBOX_JAR_DIR:?Set LUCENE_ROOT or LUCENE_SANDBOX_JAR_DIR}"
: "${LUCENE_MODULE_JAR_DIRS:?Set LUCENE_ROOT or LUCENE_MODULE_JAR_DIRS}"

for directory in "$LUCENE_CORE_JAR_DIR" "$LUCENE_SANDBOX_JAR_DIR"; do
  if [[ ! -d "$directory" ]]; then
    echo "Lucene artifact directory does not exist: $directory" >&2
    exit 1
  fi
done

cd "$SCRIPT_DIR/knn-node"
./gradlew test --no-daemon \
  -PluceneCoreJarDir="$LUCENE_CORE_JAR_DIR" \
  -PluceneSandboxJarDir="$LUCENE_SANDBOX_JAR_DIR" \
  -PluceneModuleJarDirs="$LUCENE_MODULE_JAR_DIRS" \
  "$@"
