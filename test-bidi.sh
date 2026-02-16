#!/bin/bash
# test-bidi.sh - Launch 2 nodes locally to verify bidirectional gRPC coordination

export JAVA_HOME="/home/krickert/.sdkman/candidates/java/25.0.2-tem"
PROJECT_ROOT="/work/opensearch-grpc-knn"
BIN="$PROJECT_ROOT/distributed-search/knn-node/build/quarkus-app/quarkus-run.jar"
INDEX_BASE="$PROJECT_ROOT/distributed-search/shards-8-new"
LIB_DIR="$PROJECT_ROOT/distributed-search/knn-node/build/quarkus-app/lib/main"
CLASSES="$PROJECT_ROOT/distributed-search/knn-node/build/classes/java/main"
LUCENE_JAR="$PROJECT_ROOT/lucene/lucene/core/build/libs/lucene-core-11.0.0-SNAPSHOT.jar"

# Cleanup previous logs
rm -f "$PROJECT_ROOT/distributed-search/node-*.log"

echo "=== Building Index Shards ==="
# Building index shards using direct java -cp
$JAVA_HOME/bin/java -cp "$CLASSES:$LUCENE_JAR" \
    org.apache.lucene.collab.index.SimpleIndexer \
    "$PROJECT_ROOT/data/sentences-1024d-docs.vec" 1024 8 "$INDEX_BASE"

echo "=== Starting Node 0 (Leader) ==="
nohup $JAVA_HOME/bin/java \
    -Dquarkus.http.port=48100 \
    -Dquarkus.grpc.server.port=48101 \
    -Dknn.shard.id=0 \
    -Dknn.index.path="$INDEX_BASE/shard-0" \
    -jar "$BIN" > "$PROJECT_ROOT/distributed-search/node-0.log" 2>&1 &
PID0=$!

echo "=== Starting Node 1 (Follower) ==="
nohup $JAVA_HOME/bin/java \
    -Dquarkus.http.port=48110 \
    -Dquarkus.grpc.server.port=48102 \
    -Dknn.shard.id=1 \
    -Dknn.index.path="$INDEX_BASE/shard-1" \
    -jar "$BIN" > "$PROJECT_ROOT/distributed-search/node-1.log" 2>&1 &
PID1=$!

echo "Nodes started (PIDs: $PID0, $PID1). Waiting 20s for boot..."
sleep 20

echo "=== Cluster Status ==="
ps -fp $PID0 $PID1 || echo "Warning: One or more nodes failed to start."

echo "=== Triggering Search on Node 0 ==="
# Search for 'artificial intelligence'
curl -s -X GET "http://localhost:48100/search/text?q=artificial+intelligence&k=5" | head -c 500

echo -e "\n\n=== Node 0 Log (Check for Peer calls) ==="
grep "Leading Distributed Search" "$PROJECT_ROOT/distributed-search/node-0.log" | tail -n 1
grep "merged" "$PROJECT_ROOT/distributed-search/node-0.log" | tail -n 1

echo "=== Node 1 Log (Check for incoming search) ==="
grep "executing local search" "$PROJECT_ROOT/distributed-search/node-1.log" | tail -n 1

echo "Test complete. Logs in distributed-search/"
