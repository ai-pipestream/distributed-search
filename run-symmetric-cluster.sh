#!/bin/bash
# run-symmetric-cluster.sh - Launch 8 identical Symmetric Peers locally
# Unified Vert.x server: single port per node serves both HTTP REST and gRPC (HTTP/2)

CDIR=$(pwd)
INDEX_BASE="${KNN_INDEX_BASE:-./shards-8-new}"
KNN_NODE_XMS="${KNN_NODE_XMS:-8g}"
KNN_NODE_XMX="${KNN_NODE_XMX:-8g}"
# Lucene Vector API acceleration (recommended by startup warning)
KNN_VECTOR_OPTS="${KNN_VECTOR_OPTS:---add-modules=jdk.incubator.vector}"
# Optional: suppress native access warning from Lucene native access calls
KNN_NATIVE_OPTS="${KNN_NATIVE_OPTS:---enable-native-access=ALL-UNNAMED}"
# Optional collaborative-bar tuning knobs (defaults are in Lucene code).
KNN_COLLAB_JVM_OPTS="${KNN_COLLAB_JVM_OPTS:-}"

# 1. Build the single Uber-App
echo "Building knn-node..."
cd knn-node
./gradlew build -Dquarkus.package.type=fast-jar -x test
cd ..

echo "Using shard index base: $INDEX_BASE"
echo "Node JVM opts: -Xms$KNN_NODE_XMS -Xmx$KNN_NODE_XMX $KNN_VECTOR_OPTS $KNN_NATIVE_OPTS $KNN_COLLAB_JVM_OPTS"

# 2. Start 8 Nodes (single port per node: HTTP + gRPC on same port)
for i in {0..7}
do
    PORT=$((48100 + i))  # 48100, 48101, 48102, ...

    echo "Starting Node-$i (HTTP+gRPC: $PORT)..."

    java \
        -Xms$KNN_NODE_XMS \
        -Xmx$KNN_NODE_XMX \
        $KNN_VECTOR_OPTS \
        $KNN_NATIVE_OPTS \
        $KNN_COLLAB_JVM_OPTS \
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
