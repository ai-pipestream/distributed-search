#!/bin/bash
# Build N shards from a single docs.vec (see poc/2-indexer for full dataset pipeline).
# Usage: ./build-shards.sh <path_to_docs.vec> [dim] [num_shards] [chunk_file]
# Default: dim=1024, num_shards=8. Output: ./shards-8-new/
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VEC_PATH="${1:?Usage: ./build-shards.sh <path_to_docs.vec> [dim] [num_shards] [chunk_file]}"
DIM="${2:-1024}"
SHARDS="${3:-8}"
CHUNK_FILE="${4:-}"
OUT_DIR="./shards-$SHARDS-new"

mkdir -p "$OUT_DIR"

IB="$SCRIPT_DIR/../lucene-test-data/index-builder"
LUCENE_JAR="${LUCENE_JAR:-$SCRIPT_DIR/../lucene/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar}"

GRADLE_OPTS=""
[[ -f "$LUCENE_JAR" ]] && GRADLE_OPTS="-PluceneJar=$LUCENE_JAR"

OUT_ABS="$(cd "$SCRIPT_DIR" && pwd)/$OUT_DIR"
cd "$IB"
if [[ -n "$CHUNK_FILE" ]]; then
  ./gradlew $GRADLE_OPTS -q simpleIndexer --args="$VEC_PATH $DIM $SHARDS $OUT_ABS $CHUNK_FILE"
else
  ./gradlew $GRADLE_OPTS -q simpleIndexer --args="$VEC_PATH $DIM $SHARDS $OUT_ABS"
fi
echo "Done: $OUT_ABS"