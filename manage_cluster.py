#!/usr/bin/env python3
import argparse
import subprocess
import time
import sys
import json
import threading
import os
from concurrent.futures import ThreadPoolExecutor

# --- Configuration ---
CONFIG_FILE = "cluster-config.json"

def load_config():
    if not os.path.exists(CONFIG_FILE):
        print(f"ERROR: {CONFIG_FILE} not found. Please create it from cluster-config.json.template")
        sys.exit(1)
    with open(CONFIG_FILE, "r") as f:
        return json.load(f)

CONFIG = load_config()
NODES = CONFIG["nodes"]
SEED_IP = CONFIG["seed_ip"]
SEED_PORT = CONFIG.get("seed_port", 4900)
DJL_URL = CONFIG.get("djl_url", "http://localhost:8091")

HTTP_PORT = 48100

LOCAL_APP_DIR = "knn-node/build/quarkus-app"
LOCAL_SHARD_BASE = "shards-8-new"

# --- Helpers ---

def run_cmd(cmd, shell=True, capture_output=True, text=True, check=True):
    try:
        result = subprocess.run(cmd, shell=shell, capture_output=capture_output, text=text, check=check)
        return result.stdout.strip() if result.stdout else ""
    except subprocess.CalledProcessError as e:
        if check:
            print(f"Error running '{cmd}': {e.stderr}")
            raise e
        return None

def ssh_cmd(host, command, background=False, check=True):
    ssh_opts = "-o ConnectTimeout=5 -o StrictHostKeyChecking=no"
    if background:
        full_cmd = f"ssh {ssh_opts} {host} '{command} > /dev/null 2>&1 &'"
    else:
        full_cmd = f"ssh {ssh_opts} {host} '{command}'"
    return run_cmd(full_cmd, check=check)

def check_health(host, port=HTTP_PORT, retries=10, delay=2):
    url = f"http://{host}:{port}/q/health"
    print(f"[{host}] Checking health...")
    for i in range(retries):
        try:
            cmd = f"curl -s --max-time 2 http://localhost:{port}/q/health"
            out = ssh_cmd(host, cmd, check=False)
            if out and '"status": "UP"' in out:
                print(f"[{host}] is UP!")
                return True
        except:
            pass
        time.sleep(delay)
    return False

# --- Actions ---

def build_app():
    print("=== Building Quarkus App ===")
    run_cmd("cd knn-node && ./gradlew build -x test")

def deploy_node(node):
    host = node["host"]
    shard_id = node["shard"]
    remote_dir = node["remote_dir"]
    print(f"[{host}] Deploying Shard {shard_id} to {remote_dir}...")
    
    # mkdir and purge old app
    ssh_cmd(host, f"rm -rf {remote_dir}/quarkus-app && mkdir -p {remote_dir}/index {remote_dir}/quarkus-app")
    
    # rsync app
    run_cmd(f"rsync -az --delete {LOCAL_APP_DIR}/ {host}:{remote_dir}/quarkus-app/")
    
    # rsync shard (only if needed? For now, always sync to be safe)
    # Check if shard exists locally
    if not os.path.isdir(f"{LOCAL_SHARD_BASE}/shard-{shard_id}"):
        print(f"[{host}] WARNING: Local shard {shard_id} not found!")
        return

    run_cmd(f"rsync -az {LOCAL_SHARD_BASE}/shard-{shard_id}/ {host}:{remote_dir}/index/shard-{shard_id}/")
    
    # Create run script
    run_script = f"""#!/bin/bash
cd {remote_dir}/quarkus-app
# Kill any previous instance
pkill -f quarkus-run.jar || true

# Start
~/.sdkman/candidates/java/current/bin/java -Xms{node['mem']} -Xmx{node['mem']} \\
  -Djava.net.preferIPv4Stack=true \\
  -Ddjl-api/mp-rest/url={DJL_URL} \\
  -Dknn.scalecube.host={node['ip']} \\
  --add-modules=jdk.incubator.vector \\
  --enable-native-access=ALL-UNNAMED \\
  -Dquarkus.profile=scalecube \\
  -Dquarkus.http.port={HTTP_PORT} \\
  -Dquarkus.grpc.server.use-separate-server=false \\
  -Dknn.scalecube.port=4900 \\
  -Dknn.scalecube.seeds={SEED_IP}:{SEED_PORT} \\
  -Dknn.shard.id={shard_id} \\
  -Dknn.index.path=../index/shard-{shard_id} \\
  -jar quarkus-run.jar > {remote_dir}/node.log 2>&1 &
"""
    # Write script to temp file then scp
    tmp_script = f"run-{host}.sh"
    with open(tmp_script, "w") as f:
        f.write(run_script)
    
    run_cmd(f"scp {tmp_script} {host}:{remote_dir}/run.sh")
    ssh_cmd(host, f"chmod +x {remote_dir}/run.sh")
    os.remove(tmp_script)
    print(f"[{host}] Deployed successfully.")

def start_node(node):
    host = node["host"]
    remote_dir = node["remote_dir"]
    print(f"[{host}] Starting...")
    ssh_cmd(host, f"nohup {remote_dir}/run.sh > /dev/null 2>&1 &")

def stop_node(node):
    host = node["host"]
    print(f"[{host}] Stopping...")
    ssh_cmd(host, "pkill -f quarkus-run.jar || true", check=False)

def get_status():
    print("=== Cluster Status ===")
    active_count = 0
    with ThreadPoolExecutor(max_workers=len(NODES)) as executor:
        futures = {executor.submit(check_health, n["host"], retries=1, delay=0): n for n in NODES}
        for future in futures:
            node = futures[future]
            is_up = future.result()
            status = "UP" if is_up else "DOWN"
            if is_up: active_count += 1
            print(f"{node['host']:<10} (Shard {node['shard']}): {status}")
    print(f"Total Active: {active_count}/{len(NODES)}")

# --- Main ---

def main():
    parser = argparse.ArgumentParser(description="Manage Distributed KNN Cluster")
    parser.add_argument("action", choices=["build", "deploy", "start", "stop", "restart", "status"])
    args = parser.parse_args()

    if args.action == "build":
        build_app()
    
    elif args.action == "deploy":
        build_app()
        with ThreadPoolExecutor(max_workers=len(NODES)) as executor:
            executor.map(deploy_node, NODES)
            
    elif args.action == "stop":
        with ThreadPoolExecutor(max_workers=len(NODES)) as executor:
            executor.map(stop_node, NODES)
            
    elif args.action == "start":
        # 1. Start Seed
        seed = NODES[0]
        start_node(seed)
        if not check_health(seed["host"], retries=30):
            print("CRITICAL: Seed node failed to start. Aborting.")
            sys.exit(1)
            
        # 2. Start Followers in batches or parallel (parallel is fine if seed is ready)
        followers = NODES[1:]
        # Slight staggered start to avoid gossip storms
        for f in followers:
            start_node(f)
            time.sleep(2) 
            
        print("Waiting for cluster convergence...")
        time.sleep(10)
        get_status()

    elif args.action == "restart":
        print("Stopping all nodes...")
        with ThreadPoolExecutor(max_workers=len(NODES)) as executor:
            executor.map(stop_node, NODES)
        time.sleep(5)
        
        # Start logic (dup from above)
        seed = NODES[0]
        start_node(seed)
        if not check_health(seed["host"], retries=30):
            print("CRITICAL: Seed node failed to start. Aborting.")
            sys.exit(1)
            
        followers = NODES[1:]
        for f in followers:
            start_node(f)
            time.sleep(2)
            
        print("Waiting for cluster convergence...")
        time.sleep(10)
        get_status()

    elif args.action == "status":
        get_status()

if __name__ == "__main__":
    main()
