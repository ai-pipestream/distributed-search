#!/usr/bin/env bash
# Single-container E2E: text → DJL embedding → KNN search
# Prerequisites:
#   1. DJL serving running (e.g. lucene-test-data/start-embedding-docker: docker compose up -d)
#   2. Shard index built (e.g. distributed-search/shards-8-new/shard-0)
#   3. Lucene core built: lucene/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DJL_URL="${DJL_URL:-http://localhost:8091}"
INDEX_PATH="${KNN_INDEX_PATH:-$SCRIPT_DIR/shards-8-new/shard-0}"

echo "=== Single-Node E2E ==="
echo "  DJL URL: $DJL_URL"
echo "  Index:   $INDEX_PATH"
echo ""
echo "  Smoke test (no DJL): curl 'http://localhost:48100/search/smoke?k=5'"
echo "  Text search (needs DJL): curl 'http://localhost:48100/search/text?q=machine+learning&k=5'"
echo ""

if [[ ! -d "$INDEX_PATH" ]]; then
  echo "ERROR: Index not found at $INDEX_PATH"
  echo "  Build shards first, or set KNN_INDEX_PATH to your shard directory."
  exit 1
fi

# Check DJL is reachable
if ! curl -s -f "$DJL_URL/models" > /dev/null 2>&1; then
  echo "WARN: DJL not reachable at $DJL_URL. Start with: cd lucene-test-data/start-embedding-docker && docker compose up -d"
  echo "      Then: ./load-models.sh (or wait for BGE-M3 to auto-load)"
fi

cd "$SCRIPT_DIR/knn-node"
# Override via env: DJL_API_MP_REST_URL, KNN_INDEX_PATH
export KNN_INDEX_PATH="$INDEX_PATH"
export DJL_API_MP_REST_URL="$DJL_URL"
# Java 25 required for knn-node; use sdkman Java 25 if available
if [[ -z "$JAVA_HOME" && -d "$HOME/.sdkman/candidates/java/25.0.2-tem" ]]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.2-tem"
fi
./gradlew quarkusDev -Dquarkus.profile=single -Dquarkus.http.host=0.0.0.0
