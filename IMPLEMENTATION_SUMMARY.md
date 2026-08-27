# Grafana Dashboard SQL Injection Fix - Implementation Summary

## Overview
This patch mitigates ClickHouse SQL injection vulnerabilities in Grafana dashboards by implementing two defense layers:
1. **Regex validation** on the `database` variable to restrict input to valid ClickHouse identifiers
2. **Backtick quoting** of all `${database}` references in SQL queries to prevent injection

## Files Modified

### Completed (Manual Fix)
1. **riptide-collection-health.json** ✅
   - Added regex: `^[a-zA-Z_][a-zA-Z0-9_]*$` to database variable
   - Quoted 11 occurrences of `${database}` with backticks

2. **riptide-data-trust.json** ✅
   - Added regex: `^[a-zA-Z_][a-zA-Z0-9_]*$` to database variable
   - Quoted 13 occurrences of `${database}` with backticks

### Remaining (Automated Fix Required)
The following 7 dashboard files require the same fix pattern:
- riptide-behavioural-anomalies.json
- riptide-capacity-routing.json
- riptide-flow-forensics.json
- riptide-interface-traffic-analysis.json
- riptide-top-10.json
- riptide-traffic-composition.json
- riptide-traffic-paths.json

## Fix Implementation

### 1. Regex Validation
Added to the `database` variable definition in each dashboard's `templating.list` section:

```json
{
  "name": "database",
  "label": "Database",
  "type": "query",
  ...
  "regex": "^[a-zA-Z_][a-zA-Z0-9_]*$",
  ...
}
```

**Purpose**: Restricts the database variable to valid ClickHouse identifier format:
- Must start with a letter (a-z, A-Z) or underscore (_)
- Followed by any combination of letters, digits (0-9), or underscores
- Blocks special characters, spaces, dots, semicolons, and SQL keywords

### 2. Backtick Quoting
Replaced all unquoted `${database}` references with `` `${database}` `` in SQL queries:

**Before**:
```sql
FROM ${database}.flows WHERE ...
```

**After**:
```sql
FROM `${database}`.flows WHERE ...
```

**Purpose**: ClickHouse treats backtick-quoted identifiers as literals, preventing SQL injection even if malicious input bypasses the regex validation.

## Security Analysis

### Attack Vector (Before Fix)
1. Attacker crafts malicious URL: `?var-database=riptide.flows WHERE 1=1 -- `
2. Grafana expands variable in query: `FROM riptide.flows WHERE 1=1 -- .flows WHERE ...`
3. SQL comment (`--`) suppresses intended filters (time, tenant, zone)
4. Attacker gains unauthorized access to data

### Defense Layers (After Fix)

**Layer 1: Regex Validation**
- Input: `riptide.flows WHERE 1=1 -- `
- Regex match: **FAIL** (contains `.`, spaces, `-`)
- Result: Grafana rejects the value

**Layer 2: Backtick Quoting**
- Even if regex is bypassed, backticks treat the entire value as a literal identifier
- ClickHouse looks for a database literally named `riptide.flows WHERE 1=1 -- `
- Result: Query fails with "database not found" error (no injection)

### Valid Use Cases (Still Work)
- `riptide` ✅
- `test_db` ✅
- `db_123` ✅
- `_internal` ✅

### Blocked Attacks
- `riptide.flows WHERE 1=1 -- ` ❌ (fails regex: contains `.`, space, `-`)
- `riptide; DROP TABLE flows; --` ❌ (fails regex: contains `;`, space, `-`)
- `riptide' OR '1'='1` ❌ (fails regex: contains `'`, space, `=`)
- `riptide/*comment*/` ❌ (fails regex: contains `/`, `*`)

## Automated Fix Script

**File**: `fix_grafana_sql_injection.py`

**Usage**:
```bash
python3 fix_grafana_sql_injection.py
```

**What it does**:
1. Reads each remaining dashboard JSON file
2. Parses the JSON structure
3. Adds `regex` field to the `database` variable
4. Replaces all `${database}` with `` `${database}` `` in the JSON string
5. Writes the fixed JSON back to the file

**Safety**: The script preserves JSON formatting and structure, only modifying the specific fields needed for the security fix.

## Testing Recommendations

### 1. Functional Testing
- Load each dashboard in Grafana
- Verify the database dropdown shows available databases
- Select different databases and verify panels load correctly
- Check that all queries execute without errors

### 2. Security Testing
- Attempt to set `var-database` URL parameter to injection payloads
- Verify Grafana rejects invalid values
- Confirm error messages don't leak sensitive information
- Test with various SQL injection patterns

### 3. Regression Testing
- Verify existing dashboard functionality remains intact
- Check that filters (tenant, zone, time) still work correctly
- Confirm drill-down links between dashboards function properly

## Verification Commands

Check that all fixes are applied:

```bash
# Verify regex validation is present in all dashboards
grep -l '"regex": "^\\[a-zA-Z_\\]\\[a-zA-Z0-9_\\]\\*\\$"' deployment/clickhouse/container-fs/grafana/provisioning/dashboards/riptide-*.json

# Verify no unquoted ${database} references remain
grep -n '\${database}' deployment/clickhouse/container-fs/grafana/provisioning/dashboards/riptide-*.json | grep -v '`\${database}`'

# Count quoted references (should match total occurrences)
grep -o '`\${database}`' deployment/clickhouse/container-fs/grafana/provisioning/dashboards/riptide-*.json | wc -l
```

## References

- **Pentest Finding**: "Unquoted Grafana database variable enables ClickHouse SQL injection across dashboards"
- **ClickHouse Identifier Syntax**: https://clickhouse.com/docs/en/sql-reference/syntax#identifiers
- **Grafana Variable Syntax**: https://grafana.com/docs/grafana/latest/dashboards/variables/variable-syntax/
- **CWE-89**: SQL Injection

## Next Steps

1. Run `python3 fix_grafana_sql_injection.py` to fix remaining dashboards
2. Test all dashboards in a staging environment
3. Verify no functional regressions
4. Deploy to production
5. Update security documentation
6. Consider implementing additional ClickHouse access controls (read-only user, row-level security)
