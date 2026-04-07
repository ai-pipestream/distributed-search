#!/usr/bin/env bash
# Run unit tests for knn-node and index-builder (and optionally build Lucene JAR).
#
# Usage:
#   ./run-tests.sh
#   LUCENE_JAR=/path/to/lucene-core-11.0.0-SNAPSHOT.jar ./run-tests.sh
#   ./run-tests.sh --build-lucene   # build Lucene JAR first if not set
#
# Prerequisites:
#   - Java 21+ (25 for knn-node)
#   - For fork/PR: build Lucene core JAR and set LUCENE_JAR, or use --build-lucene
#   - index-builder: run from a dir that has ../lucene-test-data or set LUCENE_TEST_DATA
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LUCENE_TEST_DATA="${LUCENE_TEST_DATA:-$REPO_ROOT/lucene-test-data}"
BUILD_LUCENE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-lucene)
      BUILD_LUCENE=1
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

# Resolve Lucene JAR
if [[ -z "$LUCENE_JAR" && -n "$BUILD_LUCENE" ]]; then
  LUCENE_REPO="${LUCENE_REPO:-$REPO_ROOT/lucene}"
  if [[ -d "$LUCENE_REPO" ]]; then
    echo "Building Lucene core JAR in $LUCENE_REPO ..."
    (cd "$LUCENE_REPO" && ./gradlew :lucene:core:jar -q)
    LUCENE_JAR="$LUCENE_REPO/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar"
  else
    echo "LUCENE_REPO not found at $LUCENE_REPO; set LUCENE_REPO or LUCENE_JAR" >&2
    exit 1
  fi
fi

GRADLE_PROP=""
[[ -n "$LUCENE_JAR" && -f "$LUCENE_JAR" ]] && GRADLE_PROP="-PluceneJar=$LUCENE_JAR"

echo "=== knn-node tests ==="
(cd "$SCRIPT_DIR/knn-node" && ./gradlew test --no-daemon $GRADLE_PROP)

if [[ -d "$LUCENE_TEST_DATA/index-builder" ]]; then
  echo "=== index-builder tests ==="
  (cd "$LUCENE_TEST_DATA/index-builder" && ./gradlew test --no-daemon $GRADLE_PROP)
else
  echo "Skip index-builder (not found at $LUCENE_TEST_DATA/index-builder). Set LUCENE_TEST_DATA if needed."
fi

echo "All tests passed."
