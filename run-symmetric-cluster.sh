#!/bin/bash
# run-symmetric-cluster.sh - Launch 8 identical Symmetric Peers locally
# Unified Vert.x server: single port per node serves both HTTP REST and gRPC (HTTP/2)

export JAVA_HOME="/home/krickert/.sdkman/candidates/java/25.0.2-tem"
CDIR=$(pwd)
INDEX_BASE="${KNN_INDEX_BASE:-/work/opensearch-grpc-knn/distributed-search/shards-8-new}"

# 1. Build the single Uber-App
echo "Building knn-node..."
cd knn-node
./gradlew build -Dquarkus.package.type=fast-jar -x test
cd ..

echo "Using shard index base: $INDEX_BASE"

# 2. Start 8 Nodes (single port per node: HTTP + gRPC on same port)
for i in {0..7}
do
    PORT=$((48100 + i))  # 48100, 48101, 48102, ...

    echo "Starting Node-$i (HTTP+gRPC: $PORT)..."

    java \
        -Dquarkus.http.port=$PORT \
        -Dknn.shard.id=$i \
        -Dknn.index.path="$INDEX_BASE/shard-$i" \
        -jar knn-node/build/quarkus-app/quarkus-run.jar \
        > node-$i.log 2>&1 &
done

echo "Cluster is booting. Every node is both a Leader and a Worker."
echo "Ports: 48100-48107 (HTTP + gRPC unified)"
echo "Check node-*.log files for startup status."
echo "Press Ctrl+C to kill the cluster."

trap "pkill -f knn-node/build/quarkus-app/quarkus-run.jar; exit" INT
wait
