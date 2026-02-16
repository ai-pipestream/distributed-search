#!/bin/bash
# bootstrap-cluster.sh - Cluster-wide Java installation

NODES=("cm5v1" "cm5v2" "pi5v1" "pi5v2" "pi5v3" "pi5v4" "pi5v5" "pi500p")
JAVA_VERSION="25.0.2-tem"

echo "=== KNN Cluster Software Bootstrap ==="

for node in "${NODES[@]}"
do
    echo "------------------------------------------------"
    echo "Processing $node..."
    
    ssh -o ConnectTimeout=5 $node "
        # Install dependencies
        sudo apt-get update -qq && sudo apt-get install -y -qq curl zip unzip tar
        
        # Install SDKMAN if missing
        if [ ! -d \$HOME/.sdkman ]; then
            echo 'Installing SDKMAN...'
            curl -s 'https://get.sdkman.io' | bash
        fi
        
        # Initialize SDKMAN and install Java 25
        export sdkman_auto_answer=true
        source \$HOME/.sdkman/bin/sdkman-init.sh
        if ! sdk current java | grep -q '$JAVA_VERSION'; then
            echo 'Installing Java $JAVA_VERSION...'
            sdk install java $JAVA_VERSION
            sdk default java $JAVA_VERSION
        else
            echo 'Java $JAVA_VERSION is already the default.'
        fi
        
        # Verify
        java -version 2>&1 | head -n 1
    "
done

echo "Cluster Software Bootstrap Complete."