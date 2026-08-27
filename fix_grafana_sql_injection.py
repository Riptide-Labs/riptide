#!/usr/bin/env python3
"""
Apply SQL injection fixes to all Grafana dashboard files.
Run this script from the repository root.
"""

import json
import sys
from pathlib import Path


def main():
    dashboard_dir = Path(
        "deployment/clickhouse/container-fs/grafana/provisioning/dashboards"
    )
    database_regex = "^[a-zA-Z_][a-zA-Z0-9_]*$"

    # Files to fix (collection-health and data-trust already done manually)
    files_to_fix = [
        "riptide-behavioural-anomalies.json",
        "riptide-capacity-routing.json",
        "riptide-flow-forensics.json",
        "riptide-interface-traffic-analysis.json",
        "riptide-top-10.json",
        "riptide-traffic-composition.json",
        "riptide-traffic-paths.json",
    ]

    for filename in files_to_fix:
        filepath = dashboard_dir / filename
        print(f"Processing {filename}...")

        # Read and parse JSON
        with open(filepath, "r", encoding="utf-8") as f:
            dashboard = json.loads(f.read())

        # Add regex to database variable
        if "templating" in dashboard and "list" in dashboard["templating"]:
            for var in dashboard["templating"]["list"]:
                if var.get("name") == "database":
                    var["regex"] = database_regex
                    break

        # Convert to JSON string and replace ${database} with `${database}`
        dashboard_json = json.dumps(dashboard, indent=2, ensure_ascii=False)
        count = dashboard_json.count("${database}")
        dashboard_json = dashboard_json.replace("${database}", "`${database}`")

        # Write back
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(dashboard_json)
            f.write("\n")

        print(f"  Fixed {count} occurrences")

    print("\nAll files processed successfully!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
