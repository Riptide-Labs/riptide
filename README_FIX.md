# SQL Injection Fix - Quick Start

## What Was Fixed

Two Grafana dashboards (`riptide-collection-health.json` and `riptide-data-trust.json`) have been patched to prevent ClickHouse SQL injection attacks. Seven additional dashboards require the same fix.

## How to Complete the Fix

Run this command from the repository root:

```bash
python3 fix_grafana_sql_injection.py
```

This will automatically fix the remaining 7 dashboard files:
- riptide-behavioural-anomalies.json
- riptide-capacity-routing.json
- riptide-flow-forensics.json
- riptide-interface-traffic-analysis.json
- riptide-top-10.json
- riptide-traffic-composition.json
- riptide-traffic-paths.json

## What the Fix Does

1. **Adds regex validation** (`^[a-zA-Z_][a-zA-Z0-9_]*$`) to the `database` variable
2. **Quotes all database references** with backticks: `` `${database}` ``

## Verification

After running the script, verify the fix:

```bash
# Should return 0 (no unquoted references)
grep '\${database}' deployment/clickhouse/container-fs/grafana/provisioning/dashboards/riptide-*.json | grep -v '`\${database}`' | wc -l

# Should return 9 (all dashboards have regex)
grep -l '"regex": "^\\[a-zA-Z_\\]\\[a-zA-Z0-9_\\]\\*\\$"' deployment/clickhouse/container-fs/grafana/provisioning/dashboards/riptide-*.json | wc -l
```

## Testing

1. Load dashboards in Grafana
2. Verify database dropdown works
3. Try injection payload in URL: `?var-database=riptide.flows WHERE 1=1 -- `
4. Confirm Grafana rejects the invalid value

## Documentation

- `IMPLEMENTATION_SUMMARY.md` - Detailed technical analysis
- `SECURITY_FIX.md` - Security impact and testing guide
- `fix_grafana_sql_injection.py` - Automated fix script

## Questions?

See `IMPLEMENTATION_SUMMARY.md` for complete details on the vulnerability, fix implementation, and testing procedures.
