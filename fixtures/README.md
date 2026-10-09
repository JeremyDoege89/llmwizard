# Scan fixtures

Output of `core/src/main/resources/llmwizard/scan.sh` captured from real machines, used by the
parser and recommendation tests. Capture a new one with `cli scan <target> --save-fixture <name>`.
Tailscale addresses are replaced with placeholders; nothing else is edited.

| File | Machine |
|---|---|
| dl380p-hyperv-ubuntu2404.txt | Jay's dev VM: Hyper-V guest on the DL380p Gen8, 2x E5-2680 v2 (AVX, no AVX2), 40 vCPU, 2 NUMA nodes, 122 GB fixed memory, no GPU |
| lxd-ubuntu2404-on-dl380p.txt | LXD system container (Ubuntu 24.04) on that VM; sees the host's CPU and meminfo |
| lxd-debian12-on-dl380p.txt | LXD system container (Debian 12) on that VM; no curl installed |
| hyperv-wiz-ubuntu.txt | Hyper-V VM on the DL380p: Ubuntu 26.04, 64 GB fixed, 10 vCPU (5 cores x 2 threads) |
| hyperv-wiz-small.txt | Hyper-V VM: Ubuntu 24.04, 8 GB, 4 vCPU |
| hyperv-wiz-debian.txt | Hyper-V VM: Debian 13, 8 GB, 4 vCPU |
| hyperv-wiz-compat.txt | Hyper-V VM like wiz-small with processor compatibility mode ON (no AVX visible) |
| synthetic-*.txt | Hand-written edge cases (marked as such inside), for hardware not available to test on |
