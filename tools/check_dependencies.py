"""Fail on OSV findings in the resolved production inventory; no compile dependency."""
import json
import sys
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

inventory = Path(sys.argv[1] if len(sys.argv) > 1 else "build/security/dependencies.txt")
packages = []
for line in inventory.read_text(encoding="utf-8").splitlines():
    group, artifact, version = line.rsplit(":", 2)
    packages.append({"package": {"ecosystem": "Maven", "name": f"{group}:{artifact}"}, "version": version})
assert packages, "Empty dependency inventory"
findings = {}
pending = packages
while pending:
    next_page = []
    for start in range(0, len(pending), 100):
        queries = pending[start:start + 100]
        request = urllib.request.Request("https://api.osv.dev/v1/querybatch", json.dumps({"queries": queries}).encode(), {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response)["results"]
        assert len(results) == len(queries), "Incomplete OSV response"
        for query, result in zip(queries, results):
            coordinate = f'{query["package"]["name"]}:{query["version"]}'
            findings.setdefault(coordinate, set()).update(v["id"] for v in result.get("vulns", []))
            if result.get("next_page_token"):
                next_page.append(dict(query, page_token=result["next_page_token"]))
    pending = next_page
for coordinate, ids in findings.items():
    if ids:
        print(coordinate, ", ".join(sorted(ids)))
print(f"Scanned {len(packages)} resolved production dependencies; {sum(bool(ids) for ids in findings.values())} affected packages")
sys.exit(1 if any(findings.values()) else 0)
