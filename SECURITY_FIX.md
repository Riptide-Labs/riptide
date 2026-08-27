# SQL Injection Fix for Grafana Dashboards

## Summary

This patch mitigates ClickHouse SQL injection in Grafana dashboards by adding regex validation to the `database` variable and quoting all database references with backticks.

## Changes Made

### 1. Files Fixed Manually
- `riptide-collection-health.json` - ✅ Complete
- `riptide-data-trust.json` - ✅ Complete

### 2. Remaining Files (Fix with Python script)
The following files require the same fix pattern:
- `riptide-behavioural-anomalies.json`
- `riptide-capacity-routing.json`
- `riptide-flow-forensics.json`
- `riptide-interface-traffic-analysis.json`
- `riptide-top-10.json`
- `riptide-traffic-composition.json`
- `riptide-traffic-paths.json`

## Fix Pattern

For each dashboard file:

1. **Add regex validation** to the `database` variable definition:
   ```json
   {
     "name": "database",
     ...
     "regex": "^[a-zA-Z_][a-zA-Z0-9_]*$",
     ...
   }
   ```

2. **Quote all database references** in SQL queries:
   - Replace: `${database}`
   - With: `` `${database}` ``

## How to Apply Remaining Fixes

Run the provided Python script from the repository root:

```bash
python3 fix_grafana_sql_injection.py
```

This script will:
- Parse each JSON dashboard file
- Add the regex validation to the database variable
- Replace all unquoted `${database}` references with backtick-quoted versions
- Write the fixed JSON back to the file

## Security Impact

### Before Fix
- The `database` variable accepted any value via URL parameters
- SQL injection possible via: `var-database=riptide.flows WHERE 1=1 -- `
- This bypassed tenant, zone, and time filters
- Allowed access to any table readable by the ClickHouse datasource account

### After Fix
1. **Regex validation** (`^[a-zA-Z_][a-zA-Z0-9_]*$`) restricts the database variable to valid ClickHouse identifiers only
2. **Backtick quoting** (`` `${database}` ``) treats the variable value as a literal identifier, preventing SQL injection even if the regex is bypassed

## Testing

To verify the fix:

1. **Test valid database names** (should work):
   - `riptide`
   - `test_db`
   - `db123`

2. **Test injection attempts** (should fail):
   - `riptide.flows WHERE 1=1 -- ` (fails regex)
   - `riptide; DROP TABLE flows; --` (fails regex)
   - `riptide` OR `1=1` (fails regex)

3. **Verify dashboard functionality**:
   - Load each dashboard
   - Select different database values from the dropdown
   - Verify queries execute correctly
   - Check that panels display data as expected

## Files Created

- `fix_grafana_sql_injection.py` - Python script to fix remaining dashboards
- `SECURITY_FIX.md` - This documentation file

## References

- Pentest finding: "Unquoted Grafana database variable enables ClickHouse SQL injection across dashboards"
- Affected files: All dashboards using the `${database}` variable
- ClickHouse identifier quoting: https://clickhouse.com/docs/en/sql-reference/syntax#identifiers
