#!/bin/bash
# run-cluster.sh - Launch the 8-shard simulation locally

# 1. Start Coordinator
echo "Starting Coordinator on port 48100..."
export JAVA_HOME="/home/krickert/.sdkman/candidates/java/25.0.2-tem"
cd coordinator
./gradlew quarkusDev > ../coordinator.log 2>&1 &
COORD_PID=$!
cd ..

sleep 5

# 2. Start 8 Shards
for i in {0..7}
do
    PORT=$((48101 + i))
    echo "Starting Shard-$i on port $PORT..."
    cd shard-node
    ./gradlew quarkusDev 
        -Dquarkus.http.port=$((8081 + i)) 
        -Dquarkus.grpc.server.port=$PORT 
        -Dknn.shard.id=$i 
        -Dknn.index.path="/work/opensearch-grpc-knn/lucene-test-data/data/indices/wiki-1024-sentences/shards-8/shard-$i" 
        > ../shard-$i.log 2>&1 &
    cd ..
done

echo "Cluster is booting. Check *.log files."
echo "Press Ctrl+C to kill the cluster."

trap "kill $COORD_PID; pkill -f quarkusDev; exit" INT
wait
