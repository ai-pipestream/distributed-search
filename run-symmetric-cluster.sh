#!/bin/bash
# run-symmetric-cluster.sh - Launch 8 identical Symmetric Peers locally

export JAVA_HOME="/home/krickert/.sdkman/candidates/java/25.0.2-tem"
CDIR=$(pwd)

# 1. Build the single Uber-App
echo "Building knn-node..."
cd knn-node
./gradlew build -Dquarkus.package.type=fast-jar -x test
cd ..

# 2. Start 8 Nodes
for i in {0..7}
do
    HTTP_PORT=$((48100 + (i * 10)))  # REST: 48100, 48110, 48120...
    GRPC_PORT=$((48101 + i))         # gRPC: 48101, 48102, 48103...
    
    echo "Starting Node-$i (REST: $HTTP_PORT, gRPC: $GRPC_PORT)..."
    
    java 
        -Dquarkus.http.port=$HTTP_PORT 
        -Dquarkus.grpc.server.port=$GRPC_PORT 
        -Dknn.shard.id=$i 
        -Dknn.index.path="/work/opensearch-grpc-knn/lucene-test-data/data/indices/wiki-1024-sentences/shards-8/shard-$i" 
        -jar knn-node/build/quarkus-app/quarkus-run.jar 
        > node-$i.log 2>&1 &
done

echo "Cluster is booting. Every node is both a Leader and a Worker."
echo "Check node-*.log files for startup status."
echo "Press Ctrl+C to kill the cluster."

trap "pkill -f knn-node/build/quarkus-app/quarkus-run.jar; exit" INT
wait
