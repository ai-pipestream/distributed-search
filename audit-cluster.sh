#!/bin/bash
# audit-cluster.sh - Precise hardware/software audit

NODES=("cm5v1" "cm5v2" "pi5v1" "pi5v2" "pi5v3" "pi5v4" "pi5v5" "pi500p")

printf "%-10s %-6s %-6s %-12s %-10s %-10s\n" "Node" "RAM" "NVMe" "Java" "SDKman?" "DiskFree"
echo "---------------------------------------------------------------------------------------"

for node in "${NODES[@]}"
do
    OUT=$(ssh -o ConnectTimeout=2 -o BatchMode=yes $node "
        # 1. RAM
        RAM=\$(free -g | grep Mem | awk '{print \$2}')
        
        # 2. NVMe
        [[ -d /sys/block/nvme0n1 ]] && NVME='YES' || NVME='No'
        
        # 3. SDKman check
        [[ -d \$HOME/.sdkman ]] && SDK='YES' || SDK='No'
        
        # 4. Java Version (checking PATH and SDKMAN)
        source \$HOME/.sdkman/bin/sdkman-init.sh 2>/dev/null
        JAVA_VER=\$(java -version 2>&1 | head -n 1 | cut -d'\"' -f2)
        [[ -z \"\$JAVA_VER\" ]] && JAVA_VER='None'
        
        # 5. Free disk on root
        DISK=\$(df -h / | tail -1 | awk '{print \$4}')
        
        echo \"\$RAM GB|\$NVME|\$JAVA_VER|\$SDK|\$DISK\"
    " 2>/dev/null)

    if [ $? -eq 0 ]; then
        IFS='|' read -r r_ram r_nvme r_java r_sdk r_disk <<< "$OUT"
        printf "%-10s %-6s %-6s %-12s %-10s %-10s\n" "$node" "$r_ram" "$r_nvme" "$r_java" "$r_sdk" "$r_disk"
    else
        printf "%-10s %-6s %-6s %-12s %-10s %-10s\n" "$node" "OFFLINE" "-" "-" "-" "-"
    fi
done
