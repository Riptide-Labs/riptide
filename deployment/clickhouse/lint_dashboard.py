#!/usr/bin/env python3
# Copyright 2026 Ronny Trommer <ronny@no42.org>
# SPDX-License-Identifier: Apache-2.0
#
# Vendored unchanged from https://github.com/Riptide-Labs/skills,
# observability/netops-dashboard-design/scripts/lint_dashboard.py at commit
# 2dda40afaee9dda634160c249d18828b1e45a4b1 (2026-08-04). Keeps its own Apache-2.0
# licence. Fix findings in the dashboards, not here; to update, copy a newer
# upstream revision over this file and change the commit above.
#
# netops-dashboard-design: mechanical checks for Grafana and Perses dashboards.
#
# Checks only what a machine can check — a clean run does not mean the dashboard
# answers the right question. Pair it with references/review-rubric.md.
#
#   python3 lint_dashboard.py dashboard.json
#   python3 lint_dashboard.py dashboard.yaml --check-colors
#   python3 lint_dashboard.py *.json --format json --min-severity error
#
# Exit codes: 0 = no findings at or above --fail-on, 1 = findings, 2 = usage/parse error.

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from dataclasses import dataclass, asdict
from typing import Any, Iterable

# --------------------------------------------------------------------------
# Reference data
# --------------------------------------------------------------------------

# Grafana unit ids, extracted from grafana/grafana
# packages/grafana-data/src/valueFormats/categories.ts. Regenerate with:
#   curl -sL <raw url> | grep -oE "id: '[^']+'" | sed "s/id: '//;s/'//" | sort -u
GRAFANA_UNITS = {
    'Bps', 'EHs', 'GBs', 'GHs', 'Gbits', 'GiBs', 'Gibits', 'Hs', 'KBs', 'KHs', 'Kbits', 'KiBs',
    'Kibits', 'MBs', 'MHs', 'Mbits', 'MiBs', 'Mibits', 'Mohm', 'Nm3', 'PBs', 'PHs', 'Pbits',
    'PiBs', 'Pibits', 'TBs', 'THs', 'Tbits', 'TiBs', 'Tibits', 'Wm2', 'accFS2', 'accG', 'accMS2',
    'acres', 'amp', 'amph', 'arcmin', 'arcsec', 'areaF2', 'areaM2', 'areaMI2', 'binBps', 'binbps',
    'bits', 'bool', 'bool_on_off', 'bool_yes_no', 'bps', 'bytes', 'candela', 'celsius', 'clockms',
    'clocks', 'congNm3', 'congm3', 'conmgNm3', 'conmgdL', 'conmgm3', 'conmmolL', 'conngNm3',
    'conngm3', 'conppb', 'cpm', 'cps', 'currencyBGN', 'currencyBRL', 'currencyBTC', 'currencyCHF',
    'currencyCZK', 'currencyDKK', 'currencyEUR', 'currencyGBP', 'currencyIDR', 'currencyILS',
    'currencyINR', 'currencyISK', 'currencyJPY', 'currencyKRW', 'currencyMYR', 'currencyNOK',
    'currencyPHP', 'currencyPLN', 'currencyPYG', 'currencyRUB', 'currencySEK', 'currencyTRY',
    'currencyUAH', 'currencyUSD', 'currencyUYU', 'currencyVND', 'currencyXPF', 'currencyZAR',
    'currencymBTC', 'd', 'dB', 'dBm', 'dateTimeAsIso', 'dateTimeAsIsoNoDateIfToday',
    'dateTimeAsLocal', 'dateTimeAsLocalNoDateIfToday', 'dateTimeAsSystem', 'dateTimeAsUS',
    'dateTimeAsUSNoDateIfToday', 'dateTimeFromNow', 'decbits', 'decbytes', 'decgbytes',
    'deckbytes', 'decmbytes', 'decpbytes', 'dectbytes', 'degree', 'dm3', 'dtdhms', 'dtdurationms',
    'dtdurations', 'dthms', 'eflops', 'epm', 'eps', 'ev', 'fahrenheit', 'farad', 'ffarad', 'flops',
    'flowcfm', 'flowcfs', 'flowcms', 'flowgpm', 'flowlpm', 'flowmlpm', 'forceN', 'forceNm',
    'forcekN', 'forcekNm', 'gallons', 'gbytes', 'gflops', 'grad', 'gwatt', 'h', 'hectares',
    'henry', 'hertz', 'hex', 'hex0x', 'humidity', 'iops', 'joule', 'kamp', 'kamph', 'kbytes',
    'kelvin', 'kohm', 'kvolt', 'kvoltamp', 'kvoltampreact', 'kwatt', 'kwatth', 'kwattm',
    'lengthcm', 'lengthft', 'lengthin', 'lengthkm', 'lengthm', 'lengthmi', 'lengthmm', 'litre',
    'litreh', 'locale', 'lumens', 'lux', 'm', 'm3', 'mamp', 'mamph', 'massg', 'masskg', 'masslb',
    'massmg', 'masst', 'mbytes', 'megwatt', 'mflops', 'mhenry', 'mlitre', 'mohm', 'mpm', 'mps',
    'ms', 'mvolt', 'mwatt', 'mwatth', 'nfarad', 'none', 'ns', 'ohm', 'opm', 'ops', 'pbytes',
    'percent', 'percentunit', 'pfarad', 'pflops', 'pixel', 'ppm', 'pps', 'pressurebar',
    'pressurehg', 'pressurehpa', 'pressurekbar', 'pressurekpa', 'pressurembar', 'pressurepa',
    'pressurepsi', 'radbq', 'radci', 'radexpckg', 'radgy', 'radian', 'radmsv', 'radmsvh', 'radr',
    'radrad', 'radrem', 'radsv', 'radsvh', 'radusv', 'radusvh', 'recpm', 'recps', 'reqpm', 'reqps',
    'rotdegs', 'rotghz', 'rothz', 'rotkhz', 'rotmhz', 'rotrads', 'rotrpm', 'rowspm', 'rowsps',
    'rpm', 'rps', 's', 'sci', 'short', 'sishort', 'string', 'tbytes', 'tflops', 'timeticks',
    'velocitykmh', 'velocityknot', 'velocitymph', 'velocityms', 'volt', 'voltamp', 'voltampreact',
    'watt', 'watth', 'watthperkg', 'wpm', 'wps', 'yflops', 'zflops',
}
# Grafana also accepts parameterised custom units with these prefixes.
GRAFANA_UNIT_PREFIXES = ('suffix:', 'prefix:', 'time:', 'si:', 'count:', 'currency:', 'sci:')

# Perses units, complete, from perses/perses shared CUE common/format.cue.
PERSES_UNITS = {
    'nanoseconds', 'microseconds', 'milliseconds', 'seconds', 'minutes', 'hours', 'days', 'weeks',
    'months', 'years',
    'percent', 'percent-decimal',
    'decimal', 'bits', 'decbits', 'bytes', 'decbytes',
    'bits/sec', 'decbits/sec', 'bytes/sec', 'decbytes/sec', 'counts/sec', 'events/sec',
    'messages/sec', 'ops/sec', 'packets/sec', 'reads/sec', 'records/sec', 'requests/sec',
    'rows/sec', 'writes/sec',
    'celsius', 'fahrenheit',
    'aud', 'cad', 'chf', 'cny', 'eur', 'gbp', 'hkd', 'inr', 'jpy', 'krw', 'nok', 'nzd', 'sek',
    'sgd', 'usd',
    'datetime-iso', 'datetime-us', 'datetime-local', 'date-iso', 'date-us', 'date-local',
    'time-local', 'time-iso', 'time-us', 'relative-time', 'unix-timestamp', 'unix-timestamp-ms',
}

# Panel plugin kinds shipped in perses/plugins. Unknown kinds are reported at info
# level, not error — the plugin system is open.
PERSES_PANEL_KINDS = {
    'TimeSeriesChart', 'StatChart', 'GaugeChart', 'BarChart', 'PieChart', 'Table', 'Markdown',
    'TimeSeriesTable', 'ScatterChart', 'StatusHistoryChart', 'HeatmapChart', 'HistogramChart',
    'FlameChart', 'LogsTable', 'LogExplorer', 'TraceTable', 'TracingGanttChart',
}
PERSES_UNIT_REQUIRED = {'TimeSeriesChart', 'StatChart', 'GaugeChart', 'BarChart', 'HistogramChart'}

GRAFANA_DEPRECATED = {
    'graph': 'timeseries',
    'singlestat': 'stat',
    'grafana-singlestat-panel': 'stat',
    'table-old': 'table',
    'grafana-piechart-panel': 'barchart (or the core piechart, if a pie is truly justified)',
    'graph-old': 'timeseries',
}
GRAFANA_UNIT_REQUIRED = {
    'timeseries', 'stat', 'gauge', 'bargauge', 'barchart', 'heatmap', 'histogram', 'xychart',
    'trend', 'piechart',
}
GRAFANA_NO_TEXT_NEEDED = {'row', 'text'}
GRAFANA_SERIES_PANELS = {'timeseries', 'barchart', 'xychart', 'trend', 'heatmap'}
GRAFANA_STATE_PANELS = {'state-timeline', 'status-history'}

PLACEHOLDER_TITLES = {
    '', 'panel title', 'new panel', 'new dashboard', 'untitled', 'title', 'chart', 'graph',
    'panel', 'row title', 'new row',
}

LATENCY_UNITS = {'s', 'ms', 'ns', 'seconds', 'milliseconds', 'nanoseconds', 'dtdurationms', 'dtdurations'}
LATENCY_KW_RE = re.compile(r'\b(latency|duration|response_time|rtt|ping|delay)\b', re.IGNORECASE)
AVG_EXPR_RE = re.compile(r'\b(avg|avg_over_time)\s*\(', re.IGNORECASE)
PERCENTILE_EXPR_RE = re.compile(r'\b(histogram_quantile|quantile_over_time|percentile|p50|p90|p95|p99)\b', re.IGNORECASE)

TRAFFIC_KW_RE = re.compile(r'\b(rate|requests|throughput|bytes|bits|qps|rps|ifHCInOctets|ifInOctets)\b', re.IGNORECASE)
ERROR_KW_RE = re.compile(r'\b(error|errors|5\.\.|errs|failed|fault|drop|discards|ifInErrors|ifOutErrors|ifInDiscards|ifOutDiscards)\b', re.IGNORECASE)
SATURATION_KW_RE = re.compile(r'\b(saturation|queue|load|wait|backlog|buffer|swap|drop|discards|ifInDiscards|ifOutDiscards)\b', re.IGNORECASE)

ENTITY_VAR_RE = re.compile(
    r'(?i)\b(device|node|host|hostname|instance|interface|ifname|ifindex|port|site|location|'
    r'region|target|switch|router|peer|circuit|link|agent|server|endpoint|exporter)s?\b'
)
# Metric names that are counters and must be rate()d before display.
COUNTER_RE = re.compile(
    r'(?i)\b(\w*(?:octets|inerrors|outerrors|indiscards|outdiscards|errors|discards|'
    r'ucastpkts|packets|pkts)\w*|\w+_total)\b'
)
RATE_FN_RE = re.compile(r'\b(rate|irate|increase|delta|idelta|deriv|rollup_rate|resets)\s*\(')
HEX_RE = re.compile(r'#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})\b')

# Machine identifiers. Readable to a machine, meaningless to the person on call.
# A series name built ONLY from these fails the label-resolution ladder.
TECHNICAL_LABELS = {
    '__name__', 'instance', 'exported_instance', 'job', 'exported_job', 'id', 'uid', 'uuid',
    'ifindex', 'ifidx', 'index', 'nodeid', 'node_id', 'foreignid', 'foreign_id', 'resourceid',
    'resource_id', 'addr', 'address', 'ip', 'ipaddr', 'ip_address', 'mac', 'oid', 'pod', 'uuid',
    'container_id', 'endpoint', 'target', 'series', 'metric', 'value', 'refid',
}
# The human-assigned names to reach for first, per entity level.
READABLE_LABEL_HINTS = {
    'device': ('sysName', 'nodeLabel', 'hostname', 'device_name', 'nodename'),
    'interface': ('ifAlias', 'ifName', 'ifDescr'),
    'site': ('site', 'location', 'building', 'region'),
}
TEMPLATE_TOKEN_RE = re.compile(r'\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}')


def series_name_tokens(template: str) -> list[str]:
    """Label names interpolated into a legendFormat / seriesNameFormat string."""
    return TEMPLATE_TOKEN_RE.findall(template or '')


def only_technical(template: str) -> bool:
    """True when a series-name template resolves to machine identifiers only."""
    tokens = series_name_tokens(template)
    if not tokens:
        return False
    return all(t.lower() in TECHNICAL_LABELS for t in tokens)


def has_label_fallback(expr: str) -> bool:
    """True when the query builds a display label with a fallback chain.

    The two idioms that do this are a label_replace() ladder (least-preferred
    source written first, better sources overwriting it) and an `or` between a
    group_left-joined expression and the bare one.
    """
    return expr.count('label_replace') >= 2 or ('group_left' in expr and re.search(r'\bor\b', expr))

SEVERITY_ORDER = {'info': 0, 'warn': 1, 'error': 2}

# --- audience expectations (references/audiences.md) ----------------------
# range/refresh bounds are in hours; None means unbounded on that side.
AUDIENCES = {
    'noc': {
        'label': 'NOC tier-1/2',
        'range_h': (0.25, 6), 'refresh_min': (0, 1), 'panel_budget': 12,
        'needs_verdict': True,       # every numeric panel pre-judges "bad" for the reader
        'needs_runbook': True,
        'needs_exploration': False,
        'forbid_raw_counters': False,
    },
    'engineer': {
        'label': 'network engineer',
        'range_h': (0.25, 24), 'refresh_min': (0, 5), 'panel_budget': 16,
        'needs_verdict': False,
        'needs_runbook': False,
        'needs_exploration': True,   # ad-hoc filters or a generous variable set
        'forbid_raw_counters': False,
    },
    'capacity': {
        'label': 'capacity planner',
        'range_h': (168, None), 'refresh_min': (15, None), 'panel_budget': 10,
        'needs_verdict': False,
        'needs_runbook': False,
        'needs_exploration': False,
        'needs_percentile': True,    # p95 billing convention
        'forbid_raw_counters': False,
    },
    'service-owner': {
        'label': 'service owner',
        'range_h': (168, 744), 'refresh_min': (5, None), 'panel_budget': 8,
        'needs_verdict': True,
        'needs_runbook': False,
        'needs_exploration': False,
        'forbid_raw_counters': True,  # interface counters are the wrong altitude
    },
    'field': {
        'label': 'field technician',
        'range_h': (0.25, 6), 'refresh_min': (0, 1), 'panel_budget': 6,
        'needs_verdict': True,
        'needs_runbook': True,
        'needs_exploration': False,
        'forbid_raw_counters': False,
    },
}
# Bounded capture: the role may be several words ("network engineer"), so grab a
# short run and let detect_audience() trim it back to the longest known alias.
# An unbounded [a-z0-9 _-]* would run past the role into the next sentence.
AUDIENCE_TAG_RE = re.compile(r'(?i)\baudience\s*[:=]\s*([A-Za-z][A-Za-z0-9 /_-]{0,40})')
AUDIENCE_ALIASES = {
    'noc': 'noc', 'noc tier-1': 'noc', 'noc tier-1/2': 'noc', 'tier1': 'noc', 'tier-1': 'noc',
    'operator': 'noc', 'operations': 'noc',
    'engineer': 'engineer', 'network engineer': 'engineer', 'neteng': 'engineer',
    'capacity': 'capacity', 'capacity planner': 'capacity', 'planning': 'capacity',
    'service-owner': 'service-owner', 'service owner': 'service-owner',
    'serviceowner': 'service-owner', 'exec': 'service-owner', 'executive': 'service-owner',
    'field': 'field', 'field technician': 'field', 'field tech': 'field',
}
RUNBOOK_RE = re.compile(r'(?i)runbook|sop\b|knowledge.?base|\bkb\b|playbook|wiki|confluence|'
                        r'standard.operating')
PERCENTILE_RE = re.compile(r'(?i)quantile|percentile|_p9[59]\b|histogram_quantile|0\.9[59]')
RAW_COUNTER_METRIC_RE = re.compile(r'(?i)\bif(HC)?(In|Out)(Octets|Errors|Discards|UcastPkts)\b')
DURATION_RE = re.compile(r'^(\d+(?:\.\d+)?)\s*([smhdwMy])$')


# --------------------------------------------------------------------------
# Findings
# --------------------------------------------------------------------------

@dataclass
class Finding:
    severity: str
    code: str
    where: str
    message: str
    hint: str = ''


class Linter:
    def __init__(self) -> None:
        self.findings: list[Finding] = []

    def add(self, severity: str, code: str, where: str, message: str, hint: str = '') -> None:
        self.findings.append(Finding(severity, code, where, message, hint))


# --------------------------------------------------------------------------
# Colour science
# --------------------------------------------------------------------------

def hex_to_rgb(value: str) -> tuple[float, float, float]:
    v = value.lstrip('#')
    if len(v) == 3:
        v = ''.join(c * 2 for c in v)
    return tuple(int(v[i:i + 2], 16) / 255.0 for i in (0, 2, 4))  # type: ignore[return-value]


def to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def relative_luminance(rgb: tuple[float, float, float]) -> float:
    r, g, b = (to_linear(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(a: str, b: str) -> float:
    la, lb = relative_luminance(hex_to_rgb(a)), relative_luminance(hex_to_rgb(b))
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def rgb_to_lab(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    r, g, b = (to_linear(c) for c in rgb)
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.00000
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

    def f(t: float) -> float:
        return t ** (1 / 3) if t > 216 / 24389 else (841 / 108) * t + 4 / 29

    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def delta_e76(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


# Machado, Oliveira & Fernandes (2009), severity 1.0, applied in linear RGB.
CVD_MATRICES = {
    'protanopia': ((0.152286, 1.052583, -0.204868),
                   (0.114503, 0.786281, 0.099216),
                   (-0.003882, -0.048116, 1.051998)),
    'deuteranopia': ((0.367322, 0.860646, -0.227968),
                     (0.280085, 0.672501, 0.047413),
                     (-0.011820, 0.042940, 0.968881)),
    'tritanopia': ((1.255528, -0.076749, -0.178779),
                   (-0.078411, 0.930809, 0.147602),
                   (0.004733, 0.691367, 0.303900)),
}


def simulate_cvd(rgb: tuple[float, float, float], kind: str) -> tuple[float, float, float]:
    lin = [to_linear(c) for c in rgb]
    m = CVD_MATRICES[kind]
    out = [sum(m[i][j] * lin[j] for j in range(3)) for i in range(3)]

    def from_linear(c: float) -> float:
        c = min(max(c, 0.0), 1.0)
        return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055

    return tuple(from_linear(c) for c in out)  # type: ignore[return-value]


# --------------------------------------------------------------------------
# Loading
# --------------------------------------------------------------------------

def load_document(path: str) -> tuple[str, Any]:
    """Return (flavour, document). Flavour is 'grafana', 'grafana-v2' or 'perses'."""
    with open(path, 'r', encoding='utf-8') as fh:
        raw = fh.read()

    doc = None
    if path.endswith(('.yaml', '.yml')):
        try:
            import yaml  # type: ignore
        except ImportError:
            raise SystemExit(
                'error: PyYAML is required to lint YAML dashboards.\n'
                '       pip install pyyaml   — or export the dashboard as JSON '
                '(Perses accepts JSON too).'
            )
        doc = yaml.safe_load(raw)
    else:
        doc = json.loads(raw)

    if not isinstance(doc, dict):
        raise SystemExit(f'error: {path}: top level is not an object')

    if isinstance(doc.get('palettes'), dict):
        return 'palette', doc

    api = str(doc.get('apiVersion', ''))
    kind = str(doc.get('kind', ''))
    if api.startswith('perses.dev/') or (kind in ('Dashboard', 'PersesDashboard')
                                         and 'panels' in doc.get('spec', {})):
        return 'perses', doc
    if api.startswith('dashboard.grafana.app/v2'):
        return 'grafana-v2', doc
    if 'panels' in doc or 'schemaVersion' in doc:
        return 'grafana', doc
    if 'spec' in doc and isinstance(doc['spec'], dict) and 'panels' in doc['spec']:
        return 'perses', doc
    # Not fatal: `lint_dashboard.py assets/*.json` legitimately sweeps up
    # non-dashboard JSON. Report it against that file and carry on.
    raise ValueError('cannot tell whether this is a Grafana or Perses dashboard '
                     '(no panels/schemaVersion, no Perses kind)')


# --------------------------------------------------------------------------
# Grafana
# --------------------------------------------------------------------------

def walk_grafana_panels(panels: Iterable[dict]) -> Iterable[dict]:
    for p in panels or []:
        yield p
        if p.get('type') == 'row' and isinstance(p.get('panels'), list):
            yield from walk_grafana_panels(p['panels'])


def target_expressions(panel: dict) -> list[str]:
    out = []
    for t in panel.get('targets') or []:
        if not isinstance(t, dict):
            continue
        for key in ('expr', 'query', 'rawSql', 'rawQuery', 'target', 'queryText'):
            v = t.get(key)
            if isinstance(v, str) and v.strip():
                out.append(v)
    return out


def has_series_name(panel: dict) -> bool:
    for t in panel.get('targets') or []:
        if not isinstance(t, dict):
            continue
        for key in ('legendFormat', 'alias', 'label', 'legend', 'displayName', 'seriesName'):
            if str(t.get(key) or '').strip():
                return True
    defaults = (panel.get('fieldConfig') or {}).get('defaults') or {}
    if str(defaults.get('displayName') or '').strip():
        return True
    for ov in (panel.get('fieldConfig') or {}).get('overrides') or []:
        for prop in ov.get('properties') or []:
            if prop.get('id') == 'displayName':
                return True
    for tr in panel.get('transformations') or []:
        if tr.get('id') in ('organize', 'renameByRegex', 'labelsToFields', 'configFromData'):
            return True
    return False


def lint_grafana(doc: dict, lint: Linter) -> None:
    panels = list(walk_grafana_panels(doc.get('panels') or []))
    viz = [p for p in panels if p.get('type') != 'row']

    # ---- dashboard level ----
    if not str(doc.get('description') or '').strip():
        lint.add('warn', 'G100', 'dashboard',
                 'Dashboard has no description.',
                 'Say what question it answers, who owns it and where the data comes from.')
    if not doc.get('tags'):
        lint.add('info', 'G101', 'dashboard',
                 'Dashboard has no tags.',
                 'Tags are how a reader finds the sibling dashboards in a drill path.')
    if doc.get('graphTooltip', 0) == 0 and len(viz) > 1:
        lint.add('warn', 'G102', 'dashboard',
                 'Shared crosshair/tooltip is off (graphTooltip: 0).',
                 'Set graphTooltip to 1 (shared crosshair) or 2 (shared tooltip) so panels '
                 'can be read against each other in time — Contract 5.')
    if len(viz) > 16:
        lint.add('warn', 'G103', 'dashboard',
                 f'{len(viz)} visualization panels — past the ~12–16 a reader can hold.',
                 'Split by archetype and link the halves.')
    refresh = str(doc.get('refresh') or '')
    if not refresh:
        lint.add('info', 'G104', 'dashboard', 'No auto-refresh interval set.')
    else:
        m = re.fullmatch(r'(\d+)([smh])', refresh)
        if m and m.group(2) == 's' and int(m.group(1)) < 10:
            lint.add('warn', 'G105', 'dashboard',
                     f'Auto-refresh every {refresh} implies precision the source rarely has.',
                     'Refresh no faster than the scrape/poll interval.')
    if not str(doc.get('timezone') or '').strip():
        lint.add('info', 'G106', 'dashboard',
                 'Timezone not set explicitly.',
                 "Pick 'browser' or 'utc' — an incident timeline is compared across people.")
    if not any(a.get('name') != 'Annotations & Alerts'
               for a in (doc.get('annotations') or {}).get('list') or []):
        lint.add('info', 'G107', 'dashboard',
                 'No custom annotation queries (deploys, changes, alerts).',
                 'Change annotations answer "what did we do just before this?" for free.')

    seen_ids: dict[Any, str] = {}
    links_found = bool(doc.get('links'))
    reported_vars: set[str] = set()      # report each undefined variable once per dashboard

    # ---- variables ----
    for var in (doc.get('templating') or {}).get('list') or []:
        name = var.get('name', '?')
        where = f'templating[{name}]'
        vtype = var.get('type')
        if vtype in ('constant', 'datasource', 'interval', 'textbox'):
            continue
        if not str(var.get('label') or '').strip():
            lint.add('warn', 'G200', where,
                     f'Variable "{name}" has no human-readable label.',
                     'The picker shows the raw name otherwise — set label to e.g. "Device".')
        if ENTITY_VAR_RE.search(name):
            if not var.get('multi'):
                lint.add('warn', 'G201', where,
                         f'Entity variable "{name}" is single-select.',
                         'Set multi: true so a reader can compare devices in one view '
                         '— Contract 5.')
            if not var.get('includeAll'):
                lint.add('info', 'G202', where,
                         f'Entity variable "{name}" has no "All" option.',
                         'includeAll: true, plus a query that uses =~"$name".')
        if var.get('hide') == 2 and ENTITY_VAR_RE.search(name):
            lint.add('warn', 'G203', where,
                     f'Entity variable "{name}" is hidden from the reader.',
                     'Hiding the entity picker removes the reader\'s ability to drive context.')
        vquery = var.get('query')
        vquery = vquery if isinstance(vquery, str) else (vquery or {}).get('query', '')
        m = re.search(r'label_values\s*\([^,]*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)', str(vquery))
        if m and m.group(1).lower() in TECHNICAL_LABELS and not var.get('regex'):
            lint.add('warn', 'G204', where,
                     f'Variable "{name}" offers machine identifiers ("{m.group(1)}") '
                     'in the picker.',
                     'Show the human-assigned name and keep the technical value behind it: '
                     'query_result() with a named-capture regex '
                     '(?<text>...)(?<value>...), or a regex that rewrites the display '
                     'text. Fall back to the identifier only where no name exists.')

    # Framework completeness check (Golden Signals / RED / USE)
    all_grafana_text = []
    for p in viz:
        all_grafana_text.append(str(p.get('title') or ''))
        all_grafana_text.extend(target_expressions(p))
    grafana_joined = ' '.join(all_grafana_text)
    if len(viz) > 1 and TRAFFIC_KW_RE.search(grafana_joined) and not ERROR_KW_RE.search(grafana_joined):
        lint.add('warn', 'G410', 'dashboard',
                 'Dashboard contains traffic/request panels but has no explicit Error rate or error count panel.',
                 'Follow RED / Golden Signals: every traffic dashboard must include an error panel.')
    if len(viz) > 1 and TRAFFIC_KW_RE.search(grafana_joined) and not SATURATION_KW_RE.search(grafana_joined):
        lint.add('warn', 'G411', 'dashboard',
                 'Dashboard contains activity/traffic panels but lacks Saturation / Queue depth panels.',
                 'Follow USE / Golden Signals: include saturation indicators (e.g., CPU load average, queue depth, or drop rate).')

    # ---- panels ----
    for idx, p in enumerate(viz):
        ptype = str(p.get('type') or '')
        title = str(p.get('title') or '').strip()
        where = f'panel[{p.get("id", idx)}] "{title or "<untitled>"}"'

        pid = p.get('id')
        if pid is not None:
            if pid in seen_ids:
                lint.add('error', 'G300', where,
                         f'Duplicate panel id {pid} (also used by {seen_ids[pid]}).',
                         'Duplicate ids break panel links, repeats and permalinks.')
            seen_ids[pid] = title or str(pid)

        if ptype in GRAFANA_DEPRECATED:
            lint.add('error', 'G301', where,
                     f'Panel type "{ptype}" is deprecated/removed.',
                     f'Migrate to "{GRAFANA_DEPRECATED[ptype]}".')

        if title.lower() in PLACEHOLDER_TITLES:
            lint.add('error', 'G302', where,
                     f'Placeholder or missing title ("{title}").',
                     'The title is the question the panel answers — Contract 1.')
        elif re.fullmatch(r'[\w.:{}$\[\]"\'=~,\s-]*[a-z]+_[a-z_]+[\w.:{}$\[\]"\'=~,\s-]*', title) \
                and '_' in title and ' ' not in title:
            lint.add('warn', 'G303', where,
                     f'Title "{title}" looks like a metric name, not a question.',
                     'Write what a reader wants to know, e.g. "Inbound throughput".')
        if re.search(r'\((?:%|bps|Bps|pps|ms|s|bytes|bits|packets?/s)\)\s*$', title):
            lint.add('warn', 'G304', where,
                     f'Title "{title}" carries the unit.',
                     'Units belong in fieldConfig.defaults.unit — Contract 2.')

        if ptype not in GRAFANA_NO_TEXT_NEEDED and not str(p.get('description') or '').strip():
            lint.add('warn', 'G305', where,
                     'Panel has no description.',
                     'What it measures / what normal is / what bad means / what to do '
                     '— Contract 1.')

        defaults = (p.get('fieldConfig') or {}).get('defaults') or {}
        custom = defaults.get('custom') or {}
        unit = defaults.get('unit')
        overrides_units = {
            prop.get('value')
            for ov in (p.get('fieldConfig') or {}).get('overrides') or []
            for prop in ov.get('properties') or []
            if prop.get('id') == 'unit'
        }

        # P601: Latency / duration averages check
        is_latency = unit in LATENCY_UNITS or bool(LATENCY_KW_RE.search(title)) or any(LATENCY_KW_RE.search(e) for e in target_expressions(p))
        if is_latency:
            for e in target_expressions(p):
                if AVG_EXPR_RE.search(e) and not PERCENTILE_EXPR_RE.search(e):
                    lint.add('error', 'P601', where,
                             f'Panel computes latency/duration using avg aggregation in query: "{e}".',
                             'Averages hide tail latency (p95/p99) and bimodal distributions. Use histogram_quantile(0.95, ...) or a Heatmap panel.')

        if ptype in GRAFANA_UNIT_REQUIRED and not unit and not overrides_units:
            lint.add('error', 'G306', where,
                     'No unit set on a numeric panel.',
                     'fieldConfig.defaults.unit — Contract 2.')
        for u in filter(None, {unit} | overrides_units):
            if u not in GRAFANA_UNITS and not str(u).startswith(GRAFANA_UNIT_PREFIXES):
                lint.add('error', 'G307', where,
                         f'Unknown unit id "{u}" — it will render as a raw number.',
                         'See references/labels-units-text.md for the valid list.')

        if unit in ('percent', 'percentunit'):
            lo, hi = defaults.get('min'), defaults.get('max')
            expected_hi = 100 if unit == 'percent' else 1
            if lo is None or hi is None:
                lint.add('warn', 'G308', where,
                         f'Percentage panel without pinned axis (min={lo}, max={hi}).',
                         f'Pin min: 0 and max: {expected_hi} so panels compare at a glance.')

        if unit in ('bps', 'Bps', 'binbps', 'binBps', 'pps', 'short', 'ops', 'reqps') \
                and defaults.get('min') is None and ptype == 'timeseries':
            lint.add('info', 'G309', where,
                     'Rate axis is not pinned to min: 0.',
                     'An autoscaled axis makes a flat link look volatile.')

        decimals = defaults.get('decimals')
        if isinstance(decimals, int) and decimals > 3:
            lint.add('warn', 'G310', where,
                     f'decimals: {decimals} is false precision.',
                     '0 for counts, 1 for percentages, 2 only when hundredths drive a decision.')

        if ptype in GRAFANA_SERIES_PANELS and (p.get('targets') or []) and not has_series_name(p):
            lint.add('warn', 'G311', where,
                     'Series have no name at all — the legend shows the raw selector.',
                     'Set legendFormat on each query (e.g. "{{sysName}} · {{ifAlias}}") '
                     'or a displayName override — Contract 3.')

        # Label-resolution ladder: a name built only from machine identifiers.
        for t in p.get('targets') or []:
            if not isinstance(t, dict):
                continue
            tmpl = str(t.get('legendFormat') or t.get('alias') or '')
            if not only_technical(tmpl):
                continue
            expr = ' '.join(target_expressions(p))
            if has_label_fallback(expr):
                continue
            toks = ', '.join(series_name_tokens(tmpl))
            lint.add('warn', 'G319', where,
                     f'Series name "{tmpl}" is built only from machine identifiers ({toks}).',
                     'Prefer the human-assigned name and fall back to the technical one '
                     'only when it is absent: join sysName/nodeLabel/ifAlias, then build '
                     'one display label with a label_replace ladder (least-preferred '
                     'source first). Never let the fallback render empty — Contract 3.')

        if ptype in GRAFANA_STATE_PANELS or (ptype in ('stat', 'table')
                                             and unit in ('bool', 'bool_on_off', 'bool_yes_no')):
            mappings = defaults.get('mappings') or []
            if not mappings:
                lint.add('warn', 'G312', where,
                         'State panel has no value mappings — states render as bare numbers.',
                         'Map every enum value (ifOperStatus has seven) plus null/no-data '
                         '— Contract 4.')

        steps = (defaults.get('thresholds') or {}).get('steps') or []
        if len(steps) > 1 and ptype in GRAFANA_UNIT_REQUIRED:
            colours = [s.get('color') for s in steps]
            if not (defaults.get('mappings')
                    or (custom.get('thresholdsStyle') or {}).get('mode') not in (None, 'off')
                    or ptype in ('stat', 'gauge', 'bargauge', 'table')):
                lint.add('info', 'G313', where,
                         'Thresholds are colour-only on a chart.',
                         'Also render them as a line '
                         '(custom.thresholdsStyle.mode: "line") so distance to the '
                         'threshold is visible over time.')
            if len({c for c in colours if c}) < len([c for c in colours if c]):
                lint.add('warn', 'G314', where,
                         'Two threshold steps share a colour — the step is invisible.')

        if ptype == 'timeseries' and custom.get('spanNulls') in (True, 'always'):
            lint.add('warn', 'G315', where,
                     'spanNulls connects across gaps, inventing data.',
                     'For polled network data a gap usually means "unreachable" — show it.')

        if ptype == 'piechart':
            lint.add('warn', 'G316', where,
                     'Pie chart: angles compare badly and the legend forces a lookup.',
                     'Use a ranked horizontal bar gauge unless a pie is explicitly required.')

        if ptype == 'timeseries' and (custom.get('axisPlacement') == 'right'
                                      or any(prop.get('id') == 'custom.axisPlacement'
                                             and prop.get('value') == 'right'
                                             for ov in (p.get('fieldConfig') or {})
                                             .get('overrides') or []
                                             for prop in ov.get('properties') or [])):
            lint.add('info', 'G317', where,
                     'Second y-axis in use.',
                     'Dual axes invite false correlation — prefer two panels sharing a '
                     'crosshair, or mirror one series with custom.transform: negative-Y.')

        ds = p.get('datasource')
        if isinstance(ds, dict) and isinstance(ds.get('uid'), str) \
                and not ds['uid'].startswith('$') and not ds['uid'].startswith('${'):
            lint.add('info', 'G318', where,
                     f'Hardcoded datasource uid "{ds["uid"]}".',
                     'Use a datasource variable so the dashboard is portable across '
                     'environments.')

        if p.get('links') or any(
                (fc.get('links') for fc in [defaults]) ):
            links_found = True

        # ---- query-level checks ----
        for expr in target_expressions(p):
            if RATE_FN_RE.search(expr) and '$__interval' in expr and '$__rate_interval' not in expr:
                lint.add('error', 'G400', where,
                         'rate()/increase() over $__interval.',
                         '$__interval is derived from panel width and can drop below the '
                         'scrape interval — the panel goes blank when zoomed out. '
                         'Use $__rate_interval.')
            if COUNTER_RE.search(expr) and not RATE_FN_RE.search(expr) \
                    and 'sum_over_time' not in expr and ptype in GRAFANA_UNIT_REQUIRED:
                lint.add('warn', 'G401', where,
                         'A counter appears to be plotted raw.',
                         'Counters need rate()/increase(); a raw counter is a monotonic ramp.')
            if unit in ('bps', 'binbps', 'bits', 'decbits') and not re.search(r'\*\s*8\b', expr) \
                    and re.search(r'(?i)octets|bytes', expr):
                lint.add('error', 'G402', where,
                         f'Unit is "{unit}" (bits) but the query reads octets/bytes without × 8.',
                         'Either multiply by 8, or set a byte unit (Bps / binBps). '
                         'This misreports by 8×.')
            if unit in ('Bps', 'binBps', 'bytes', 'decbytes') and re.search(r'\*\s*8\b', expr):
                lint.add('error', 'G403', where,
                         f'Unit is "{unit}" (bytes) but the query multiplies by 8.',
                         'Drop the × 8, or set a bit unit (bps / binbps).')
            if unit == 'percentunit' and re.search(r'\*\s*100\b', expr):
                lint.add('error', 'G404', where,
                         'Unit "percentunit" expects 0.0–1.0 but the query × 100.',
                         'Use "percent", or drop the × 100. This misreports by 100×.')
            if unit == 'percent' and '/' in expr and not re.search(r'\*\s*100\b', expr):
                lint.add('warn', 'G405', where,
                         'Unit "percent" (0–100) on what looks like a 0–1 ratio.',
                         'Either × 100 in the query or use "percentunit".')
            known = {v.get('name') for v in (doc.get('templating') or {}).get('list') or []}
            for var in dict.fromkeys(re.findall(r'\$\{?(\w+)', expr)):
                # $__* are built-ins; $1, $2 … are regex capture-group references
                # inside label_replace/label_join, not dashboard variables.
                if var.startswith('__') or var.isdigit():
                    continue
                if var not in known and var not in reported_vars:
                    reported_vars.add(var)
                    lint.add('error', 'G406', where,
                             f'Query references undefined variable "${var}".',
                             'Define it under templating, or the query silently returns '
                             'nothing.')

    if not links_found and len(viz) > 3:
        lint.add('info', 'G108', 'dashboard',
                 'No dashboard or panel links — the dashboard is a dead end.',
                 'Add a data link carrying var-* and the time range to the next dashboard '
                 '— Contract 5.')


# --------------------------------------------------------------------------
# Perses
# --------------------------------------------------------------------------

def perses_unit_of(plugin_spec: dict, kind: str) -> tuple[Any, str]:
    if kind == 'TimeSeriesChart':
        fmt = ((plugin_spec.get('yAxis') or {}).get('format') or {})
        if fmt:
            return fmt.get('unit'), 'yAxis.format.unit'
    fmt = plugin_spec.get('format') or {}
    if fmt:
        return fmt.get('unit'), 'format.unit'
    return None, 'format.unit'


def lint_perses(doc: dict, lint: Linter) -> None:
    spec = doc.get('spec') or {}
    panels = spec.get('panels') or {}
    layouts = spec.get('layouts') or []

    display = spec.get('display') or {}
    if not str(display.get('name') or '').strip():
        lint.add('warn', 'P100', 'dashboard', 'Dashboard has no spec.display.name.')
    if not str(display.get('description') or '').strip():
        lint.add('warn', 'P101', 'dashboard',
                 'Dashboard has no spec.display.description.',
                 'Say what question it answers and who owns it.')
    if not spec.get('duration'):
        lint.add('warn', 'P102', 'dashboard',
                 'No spec.duration — the default time range is left to the viewer.',
                 'Pick the window the decision needs, e.g. "6h".')
    if not spec.get('refreshInterval'):
        lint.add('info', 'P103', 'dashboard', 'No spec.refreshInterval set.')
    if len(panels) > 16:
        lint.add('warn', 'P104', 'dashboard',
                 f'{len(panels)} panels — past the ~12–16 a reader can hold.')

    # ---- layout integrity ----
    referenced: set[str] = set()
    for li, layout in enumerate(layouts):
        lspec = layout.get('spec') or {}
        if not str((lspec.get('display') or {}).get('title') or '').strip():
            lint.add('info', 'P200', f'layouts[{li}]',
                     'Layout group has no title — rows are how a reader skims.')
        for ii, item in enumerate(lspec.get('items') or []):
            ref = ((item.get('content') or {}).get('$ref') or '')
            key = ref.rsplit('/', 1)[-1] if ref else ''
            if not key or key not in panels:
                lint.add('error', 'P201', f'layouts[{li}].items[{ii}]',
                         f'Layout references a panel that does not exist: "{ref}".')
            else:
                referenced.add(key)
            w = item.get('width')
            if isinstance(w, int) and not 1 <= w <= 24:
                lint.add('error', 'P202', f'layouts[{li}].items[{ii}]',
                         f'width {w} is outside the 1–24 grid.')
    for key in panels:
        if key not in referenced:
            lint.add('warn', 'P203', f'panels.{key}',
                     'Panel is defined but never placed in a layout — it will not render.')

    # ---- variables ----
    for vi, var in enumerate(spec.get('variables') or []):
        vkind = var.get('kind')
        vspec = var.get('spec') or {}
        name = vspec.get('name', f'#{vi}')
        where = f'variables[{name}]'
        if not str((vspec.get('display') or {}).get('name') or '').strip():
            lint.add('warn', 'P300', where,
                     f'Variable "{name}" has no display.name.',
                     'The picker shows the raw name otherwise.')
        if vkind == 'ListVariable' and ENTITY_VAR_RE.search(str(name)):
            if not vspec.get('allowMultiple'):
                lint.add('warn', 'P301', where,
                         f'Entity variable "{name}" is single-select.',
                         'allowMultiple: true — Contract 5.')
            if not vspec.get('allowAllValue'):
                lint.add('info', 'P302', where,
                         f'Entity variable "{name}" has no "All" option.',
                         'allowAllValue: true (and customAllValue when the backend needs '
                         'a specific wildcard).')

    # ---- panels ----
    for key, panel in panels.items():
        pspec = panel.get('spec') or {}
        pdisplay = pspec.get('display') or {}
        name = str(pdisplay.get('name') or '').strip()
        where = f'panels.{key} "{name or "<unnamed>"}"'
        plugin = pspec.get('plugin') or {}
        kind = str(plugin.get('kind') or '')
        plugin_spec = plugin.get('spec') or {}

        if not name:
            lint.add('error', 'P400', where, 'Panel has no spec.display.name.')
        elif name.lower() in PLACEHOLDER_TITLES:
            lint.add('error', 'P401', where, f'Placeholder panel name ("{name}").')
        if not str(pdisplay.get('description') or '').strip() and kind != 'Markdown':
            lint.add('warn', 'P402', where,
                     'Panel has no spec.display.description.',
                     'What it measures / what normal is / what bad means / what to do.')

        if kind and kind not in PERSES_PANEL_KINDS:
            lint.add('info', 'P403', where,
                     f'Unrecognised panel plugin kind "{kind}" — verify the plugin is '
                     'installed on the target Perses instance.')

        unit, unit_path = perses_unit_of(plugin_spec, kind)
        pexprs = []
        for q in plugin_spec.get('queries') or []:
            qspec = q.get('spec') or {}
            qplugin = qspec.get('plugin') or {}
            qp_spec = qplugin.get('spec') or {}
            if isinstance(qp_spec.get('query'), str):
                pexprs.append(qp_spec['query'])

        is_latency_perses = unit in LATENCY_UNITS or bool(LATENCY_KW_RE.search(name)) or any(LATENCY_KW_RE.search(e) for e in pexprs)
        if is_latency_perses:
            for e in pexprs:
                if AVG_EXPR_RE.search(e) and not PERCENTILE_EXPR_RE.search(e):
                    lint.add('error', 'P601', where,
                             f'Panel computes latency/duration using avg aggregation in query: "{e}".',
                             'Averages hide tail latency (p95/p99) and bimodal distributions. Use histogram_quantile(0.95, ...) or a Heatmap panel.')

        if kind in PERSES_UNIT_REQUIRED:
            if not unit:
                lint.add('error', 'P404', where,
                         f'No unit set ({unit_path}).',
                         'Contract 2. Perses units are listed in '
                         'references/labels-units-text.md.')
            elif unit not in PERSES_UNITS:
                lint.add('error', 'P405', where,
                         f'Unknown Perses unit "{unit}".',
                         'Perses has no bool/short/dBm units — use mappings or "decimal" '
                         'with a y-axis label.')

        if kind == 'TimeSeriesChart':
            yaxis = plugin_spec.get('yAxis') or {}
            if unit in ('percent', 'percent-decimal'):
                if yaxis.get('min') is None or yaxis.get('max') is None:
                    lint.add('warn', 'P406', where,
                             'Percentage panel without pinned yAxis.min/max.')
            if unit in ('bits/sec', 'bytes/sec', 'packets/sec', 'decbits/sec', 'decbytes/sec') \
                    and yaxis.get('min') is None:
                lint.add('info', 'P407', where, 'Rate axis is not pinned to min: 0.')
            if (plugin_spec.get('visual') or {}).get('connectNulls'):
                lint.add('warn', 'P408', where,
                         'visual.connectNulls hides measurement gaps.',
                         'For polled network data a gap usually means "unreachable".')

        if kind == 'StatusHistoryChart' and not plugin_spec.get('mappings'):
            lint.add('warn', 'P409', where,
                     'StatusHistoryChart has no mappings — states render as bare numbers.',
                     'Map every enum value plus null — Contract 4.')

        if kind == 'PieChart':
            lint.add('warn', 'P410', where,
                     'Pie chart: angles compare badly.',
                     'Use BarChart, ranked.')

        fmt_dec = ((plugin_spec.get('format') or {}).get('decimalPlaces')
                   or ((plugin_spec.get('yAxis') or {}).get('format') or {}).get('decimalPlaces'))
        if isinstance(fmt_dec, int) and fmt_dec > 3:
            lint.add('warn', 'P411', where, f'decimalPlaces: {fmt_dec} is false precision.')

        queries = pspec.get('queries') or []
        for qi, q in enumerate(queries):
            qplugin = ((q.get('spec') or {}).get('plugin') or {})
            qspec = qplugin.get('spec') or {}
            qwhere = f'{where} query[{qi}]'
            expr = str(qspec.get('query') or '')
            name_fmt = str(qspec.get('seriesNameFormat') or '').strip()
            if kind in ('TimeSeriesChart', 'BarChart') and not name_fmt:
                lint.add('warn', 'P500', qwhere,
                         'Query has no seriesNameFormat — the legend shows the raw selector.',
                         'e.g. seriesNameFormat: "{{sysName}} · {{ifAlias}} · in" '
                         '— Contract 3.')
            elif only_technical(name_fmt) and not has_label_fallback(expr):
                toks = ', '.join(series_name_tokens(name_fmt))
                lint.add('warn', 'P505', qwhere,
                         f'Series name "{name_fmt}" is built only from machine '
                         f'identifiers ({toks}).',
                         'Prefer the human-assigned name (sysName, nodeLabel, ifAlias) and '
                         'fall back to the technical one only when it is absent — '
                         'Contract 3.')
            if RATE_FN_RE.search(expr) and '$__interval' in expr \
                    and '$__rate_interval' not in expr:
                lint.add('error', 'P501', qwhere,
                         'rate()/increase() over $__interval — use $__rate_interval.')
            if unit in ('bits/sec', 'decbits/sec') and re.search(r'(?i)octets|bytes', expr) \
                    and not re.search(r'\*\s*8\b', expr):
                lint.add('error', 'P502', qwhere,
                         f'Unit "{unit}" (bits) but the query reads octets/bytes without × 8.')
            if unit in ('bytes/sec', 'decbytes/sec') and re.search(r'\*\s*8\b', expr):
                lint.add('error', 'P503', qwhere,
                         f'Unit "{unit}" (bytes) but the query multiplies by 8.')
            if unit == 'percent-decimal' and re.search(r'\*\s*100\b', expr):
                lint.add('error', 'P504', qwhere,
                         'Unit "percent-decimal" expects 0.0–1.0 but the query × 100.')

    # Perses carries drill-down context through the URL, so a link can be a `links`
    # key, a markdown link in a Markdown panel, or any ?var-… query string.
    blob = json.dumps(panels)
    has_exit = any(marker in blob for marker in ('"links"', '](/', 'var-', '/dashboards/'))
    if not has_exit and len(panels) > 3:
        lint.add('info', 'P105', 'dashboard',
                 'No links out of this dashboard — it is a dead end for drill-down.',
                 'Perses passes context through the URL: add a Markdown panel or panel '
                 'links pointing at the next dashboard with ?var-…= — Contract 5.')


# --------------------------------------------------------------------------
# Audience fit
# --------------------------------------------------------------------------

def parse_duration_hours(value: str) -> float | None:
    """'6h' / 'now-30d' / '90m' -> hours. None when it cannot be read."""
    if not value:
        return None
    v = str(value).strip()
    v = v[4:] if v.startswith('now-') else v
    m = DURATION_RE.match(v)
    if not m:
        return None
    n, unit = float(m.group(1)), m.group(2)
    return n * {'s': 1 / 3600, 'm': 1 / 60, 'h': 1, 'd': 24,
                'w': 168, 'M': 730, 'y': 8766}[unit]


def normalize(flavour: str, doc: dict) -> dict:
    """Flatten a Grafana or Perses dashboard into the facts the audience rules need."""
    facts: dict[str, Any] = {
        'declared': None, 'range_h': None, 'refresh_h': None, 'panels': [],
        'exprs': [], 'nav_text': '', 'variables': [], 'has_adhoc': False,
        'annotations': [],
    }

    if flavour == 'perses':
        spec = doc.get('spec') or {}
        desc = str((spec.get('display') or {}).get('description') or '')
        facts['declared'] = desc
        facts['range_h'] = parse_duration_hours(spec.get('duration'))
        facts['refresh_h'] = parse_duration_hours(spec.get('refreshInterval'))
        facts['nav_text'] = json.dumps(spec.get('panels') or {}) + json.dumps(doc.get('metadata')
                                                                              or {})
        for var in spec.get('variables') or []:
            facts['variables'].append((var.get('spec') or {}).get('name', '?'))
        for key, panel in (spec.get('panels') or {}).items():
            pspec = panel.get('spec') or {}
            plugin = pspec.get('plugin') or {}
            pspec_plugin = plugin.get('spec') or {}
            kind = str(plugin.get('kind') or '')
            exprs = [str(((q.get('spec') or {}).get('plugin') or {}).get('spec', {})
                         .get('query') or '') for q in pspec.get('queries') or []]
            facts['exprs'].extend(exprs)
            facts['panels'].append({
                'key': key,
                'title': str((pspec.get('display') or {}).get('name') or key),
                'numeric': kind in PERSES_UNIT_REQUIRED,
                'has_verdict': bool(pspec_plugin.get('thresholds')
                                    or pspec_plugin.get('mappings')),
                'text': json.dumps(pspec),
                'exprs': exprs,
            })
    else:
        facts['declared'] = ' '.join(str(t) for t in (doc.get('tags') or [])) + ' ' + \
                            str(doc.get('description') or '')
        facts['range_h'] = parse_duration_hours((doc.get('time') or {}).get('from'))
        facts['refresh_h'] = parse_duration_hours(doc.get('refresh'))
        facts['nav_text'] = json.dumps(doc.get('links') or []) + json.dumps(doc.get('panels') or [])
        for var in (doc.get('templating') or {}).get('list') or []:
            facts['variables'].append(var.get('name', '?'))
            if var.get('type') == 'adhoc':
                facts['has_adhoc'] = True
        for a in (doc.get('annotations') or {}).get('list') or []:
            facts['annotations'].append(str(a.get('name') or ''))
        for p in walk_grafana_panels(doc.get('panels') or []):
            ptype = str(p.get('type') or '')
            if ptype == 'row':
                continue
            defaults = (p.get('fieldConfig') or {}).get('defaults') or {}
            steps = (defaults.get('thresholds') or {}).get('steps') or []
            exprs = target_expressions(p)
            facts['exprs'].extend(exprs)
            facts['panels'].append({
                'key': p.get('id'),
                'title': str(p.get('title') or ''),
                'numeric': ptype in GRAFANA_UNIT_REQUIRED,
                'has_verdict': len(steps) > 1 or bool(defaults.get('mappings')),
                'text': json.dumps(p),
                'exprs': exprs,
            })
    return facts


def detect_audience(facts: dict) -> str | None:
    """Read the declared audience from a tag or an 'Audience: <role>.' sentence.

    A dashboard may declare it in both places, so every match is tried, and each
    capture is trimmed from the right to the longest run of words that names a
    known role — 'engineer Audience' (a tag running into the description that
    follows it) resolves to 'engineer'.
    """
    for m in AUDIENCE_TAG_RE.finditer(facts.get('declared') or ''):
        tokens = [t for t in re.split(r'[\s./,;:|]+', m.group(1).strip().lower()) if t]
        for n in range(min(3, len(tokens)), 0, -1):
            hit = AUDIENCE_ALIASES.get(' '.join(tokens[:n]))
            if hit:
                return hit
    return None


def lint_audience(flavour: str, doc: dict, lint: Linter, requested: str) -> None:
    facts = normalize(flavour, doc)
    audience = detect_audience(facts) if requested == 'auto' else requested

    if not audience:
        lint.add('info', 'A000', 'dashboard',
                 'No audience declared — nobody owns this dashboard\'s scope.',
                 'Grafana: add a tag "audience:noc" (or engineer / capacity / '
                 'service-owner / field). Perses: start the description with '
                 '"Audience: NOC tier-1." Then re-run with --audience auto. '
                 'See references/audiences.md.')
        return

    spec = AUDIENCES.get(audience)
    if not spec:
        lint.add('warn', 'A001', 'dashboard', f'Unknown audience "{audience}".',
                 f'Known: {", ".join(sorted(AUDIENCES))}.')
        return

    who = spec['label']
    lint.add('info', 'A002', 'dashboard', f'Evaluating against the {who} audience.')

    # --- time range -------------------------------------------------------
    lo, hi = spec['range_h']
    rng = facts['range_h']
    if rng is None:
        lint.add('info', 'A100', 'dashboard',
                 'Default time range not set or not parseable.',
                 f'A {who} dashboard should open on '
                 f'{fmt_hours(lo)}–{fmt_hours(hi) if hi else "longer"}.')
    elif rng < lo or (hi is not None and rng > hi):
        lint.add('warn', 'A100', 'dashboard',
                 f'Default time range {fmt_hours(rng)} does not fit the {who} audience '
                 f'({fmt_hours(lo)}–{fmt_hours(hi) if hi else "longer"}).',
                 'A range mismatch is the most common sign a dashboard is serving a '
                 'different reader than it claims — see references/audiences.md.')

    # --- refresh ----------------------------------------------------------
    rlo, rhi = spec['refresh_min']
    ref = facts['refresh_h']
    if ref is not None:
        ref_m = ref * 60
        if ref_m < rlo:
            lint.add('warn', 'A110', 'dashboard',
                     f'Refresh every {fmt_hours(ref)} is faster than a {who} needs '
                     f'(≥ {rlo} min).',
                     'Implies precision the source does not have and costs backend load '
                     'for no information gain.')
        elif rhi is not None and ref_m > rhi:
            lint.add('warn', 'A110', 'dashboard',
                     f'Refresh every {fmt_hours(ref)} is too slow for a {who} '
                     f'(≤ {rhi} min).')
    elif rhi is not None:
        lint.add('info', 'A110', 'dashboard',
                 f'No auto-refresh set; a {who} dashboard should refresh at least every '
                 f'{rhi} min.')

    # --- panel budget -----------------------------------------------------
    budget = spec['panel_budget']
    if len(facts['panels']) > budget:
        lint.add('warn', 'A120', 'dashboard',
                 f'{len(facts["panels"])} panels exceeds the {who} budget of ~{budget}.',
                 'Split by archetype and link the halves rather than shrinking panels.')

    # --- "bad" must be pre-judged for the reader --------------------------
    if spec['needs_verdict']:
        naked = [p for p in facts['panels'] if p['numeric'] and not p['has_verdict']]
        for p in naked:
            lint.add('warn', 'A101', f'panel {p["key"]} "{p["title"]}"',
                     f'No threshold or value mapping — a {who} cannot tell whether this '
                     'number is bad.',
                     'The dashboard decides what "bad" means, not the reader. Add '
                     'thresholds (with the policy stated in the description) or value '
                     'mappings.')

    # --- runbook ----------------------------------------------------------
    if spec['needs_runbook'] and not RUNBOOK_RE.search(facts['nav_text']):
        lint.add('warn', 'A102', 'dashboard',
                 f'No runbook/SOP link — a {who} reader is expected to act, not diagnose.',
                 'Add a dashboard or panel link to the runbook, per panel where the '
                 'remediation differs.')

    # --- exploration surface ---------------------------------------------
    if spec['needs_exploration']:
        enough_vars = len(facts['variables']) >= 3
        if not facts['has_adhoc'] and not enough_vars:
            hint = ('Add an ad-hoc "Filters" variable so the reader can pivot on a label '
                    'you did not anticipate.') if flavour != 'perses' else \
                   ('Perses has no ad-hoc filter — provide a chained set of ListVariables '
                    'instead (site → device → interface).')
            lint.add('warn', 'A103', 'dashboard',
                     f'No exploration surface — a {who} needs to pivot beyond the '
                     'author\'s anticipated questions.', hint)

    # --- percentile / billing convention ----------------------------------
    if spec.get('needs_percentile') and not PERCENTILE_RE.search(' '.join(facts['exprs'])):
        lint.add('warn', 'A104', 'dashboard',
                 'No percentile aggregation on a capacity dashboard.',
                 'Transit and burstable circuits are billed on the 95th percentile of '
                 '5-minute samples over the billing month — a mean is not the number '
                 'anyone is charged. See references/netops-conventions.md.')

    # --- altitude ---------------------------------------------------------
    if spec['forbid_raw_counters']:
        for p in facts['panels']:
            if any(RAW_COUNTER_METRIC_RE.search(e) for e in p['exprs']):
                lint.add('warn', 'A105', f'panel {p["key"]} "{p["title"]}"',
                         f'Raw interface counters on a {who} dashboard.',
                         'Aggregate to availability, SLO attainment or breach count, and '
                         'link out to the engineer view for the detail.')

    # --- maintenance context (Grafana annotations) ------------------------
    if audience in ('noc', 'field') and flavour != 'perses':
        text = ' '.join(facts['annotations']).lower()
        if not re.search(r'maint|change|deploy|window|freeze', text):
            lint.add('info', 'A106', 'dashboard',
                     'No maintenance or change annotation.',
                     'A reader cannot tell a planned outage from a failure. Paging on '
                     'scheduled work is how a NOC stops trusting a dashboard.')


def fmt_hours(h: float | None) -> str:
    if h is None:
        return '—'
    if h < 1:
        return f'{round(h * 60)}m'
    if h < 48:
        return f'{h:g}h'
    if h < 720:
        return f'{h / 24:g}d'
    return f'{h / 730:g}mo'


# --------------------------------------------------------------------------
# Colour checks
# --------------------------------------------------------------------------

def collect_hex_colors(doc: Any) -> list[str]:
    found: list[str] = []

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
        elif isinstance(node, str):
            found.extend(HEX_RE.findall(node))

    walk(doc)
    # Preserve order, drop duplicates case-insensitively.
    seen, out = set(), []
    for c in found:
        k = c.lower()
        if k not in seen:
            seen.add(k)
            out.append(c)
    return out


def lint_palette(pal: dict, lint: Linter, dark: str, light: str) -> None:
    """Check every group in a palette.json. Groups may declare `$scale` (categorical
    or ordinal) and `$themes` (which backgrounds they are meant to survive)."""
    for group, entry in (pal.get('palettes') or {}).items():
        entries = entry.get('colors') if isinstance(entry, dict) else entry
        meta = entry if isinstance(entry, dict) else {}
        colors = [e['hex'] for e in (entries or []) if isinstance(e, dict) and 'hex' in e]
        lint_colors(colors, lint, dark, light, label=f'palette:{group}',
                    ordinal=meta.get('$scale') == 'ordinal',
                    themes=tuple(meta.get('$themes') or ('dark', 'light')))


def lint_colors(colors: list[str], lint: Linter, dark: str, light: str,
                label: str = 'dashboard', ordinal: bool = False,
                themes: tuple[str, ...] = ('dark', 'light')) -> None:
    """Check contrast for every colour, and pairwise distinctness for categorical scales.

    An ordinal scale (a severity ramp, a sequential heatmap) is *meant* to have
    similar adjacent steps, so pairwise distinctness does not apply — such scales
    must instead carry text, which the panel-level checks enforce.

    `themes` narrows the backgrounds a palette is held to. A deliberately
    dark-theme-only palette should not be failed for missing contrast on white.
    """
    if not colors:
        lint.add('info', 'C000', label, 'No explicit hex colours found — nothing to check.')
        return

    backgrounds = {'dark': dark, 'light': light}
    checked = {k: v for k, v in backgrounds.items() if k in themes} or backgrounds
    if set(checked) != set(backgrounds):
        lint.add('info', 'C006', label,
                 f'Contrast checked against the {"/".join(checked)} theme only.',
                 'Declared single-theme — verify the dashboard set really never '
                 'renders in the other theme.')

    for c in colors:
        ratios = {name: contrast_ratio(c, bg) for name, bg in checked.items()}
        worst_name = min(ratios, key=lambda k: ratios[k])
        worst = ratios[worst_name]
        if worst < 3.0:
            detail = ', '.join(f'{n} {r:.2f}:1' for n, r in ratios.items())
            lint.add('warn' if worst >= 2.0 else 'error', 'C001', f'{label} {c}',
                     f'Contrast {worst:.2f}:1 against the {worst_name} background '
                     f'({checked[worst_name]}) — below the 3:1 WCAG minimum for '
                     'non-text graphics.',
                     f'A line or bar at this colour is hard to see on that theme. ({detail})')

    if ordinal:
        lint.add('info', 'C005', label,
                 f'Ordinal scale of {len(colors)} steps — pairwise distinctness not checked.',
                 'Adjacent steps of a ramp are meant to look related; the scale must '
                 'therefore always render its step name as text.')
        return

    if len(colors) > 8:
        lint.add('warn', 'C004', label,
                 f'{len(colors)} distinct colours — beyond ~8 categorical colours nobody '
                 'can hold the mapping.',
                 'Use top-N, a table, or emphasis-on-one-series instead.')

    labs = {c: rgb_to_lab(hex_to_rgb(c)) for c in colors}
    for i, a in enumerate(colors):
        for b in colors[i + 1:]:
            d = delta_e76(labs[a], labs[b])
            if d < 12:
                lint.add('warn', 'C002', f'{label} {a}/{b}',
                         f'ΔE {d:.1f} in normal vision — these two read as the same colour.')
                continue
            for kind in CVD_MATRICES:
                da = rgb_to_lab(simulate_cvd(hex_to_rgb(a), kind))
                db = rgb_to_lab(simulate_cvd(hex_to_rgb(b), kind))
                dd = delta_e76(da, db)
                if dd < 12:
                    lint.add('error' if dd < 7 else 'warn', 'C003', f'{label} {a}/{b}',
                             f'ΔE {dd:.1f} under simulated {kind} — these collapse for '
                             'readers with that colour-vision deficiency.',
                             'Pair hue with text, position or shape, or pick colours that '
                             'differ in lightness as well as hue.')
                    break


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

ICONS = {'error': '✗', 'warn': '!', 'info': 'i'}


def report_text(path: str, flavour: str, findings: list[Finding], min_sev: str) -> None:
    shown = [f for f in findings if SEVERITY_ORDER[f.severity] >= SEVERITY_ORDER[min_sev]]
    counts = {s: sum(1 for f in findings if f.severity == s) for s in SEVERITY_ORDER}
    print(f'\n=== {path}  [{flavour}] ===')
    if not shown:
        print(f'  no findings at or above "{min_sev}" '
              f'({counts["error"]} error, {counts["warn"]} warn, {counts["info"]} info total)')
    else:
        shown.sort(key=lambda f: (-SEVERITY_ORDER[f.severity], f.code))
        for f in shown:
            print(f'  {ICONS[f.severity]} [{f.code}] {f.where}')
            print(f'      {f.message}')
            if f.hint:
                print(f'      → {f.hint}')
    print(f'  --- {counts["error"]} error, {counts["warn"]} warn, {counts["info"]} info')


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description='Lint Grafana and Perses dashboards for readability, correctness of '
                    'units and labels, colour accessibility, and drivable context.')
    ap.add_argument('paths', nargs='+', metavar='PATH', help='dashboard JSON or YAML file(s)')
    ap.add_argument('--format', choices=('text', 'json'), default='text')
    ap.add_argument('--min-severity', choices=('info', 'warn', 'error'), default='info',
                    help='lowest severity to display (default: info)')
    ap.add_argument('--fail-on', choices=('info', 'warn', 'error', 'never'), default='error',
                    help='exit non-zero when a finding at this severity or above exists '
                         '(default: error)')
    ap.add_argument('--check-colors', action='store_true',
                    help='check every hex colour for WCAG contrast and colour-vision safety')
    ap.add_argument('--audience', default='auto',
                    choices=('auto', 'off', *sorted(AUDIENCES)),
                    help='evaluate against a network-engineering audience\'s expectations. '
                         '"auto" (default) reads the declared audience from a Grafana '
                         '"audience:<role>" tag or an "Audience: <role>." sentence in the '
                         'Perses description; "off" disables the checks.')
    ap.add_argument('--palette', metavar='PATH',
                    help='also validate a palette JSON file (default: ../assets/palette.json '
                         'when --check-colors is given)')
    ap.add_argument('--dark-bg', default='#181B1F', help='dark theme panel background')
    ap.add_argument('--light-bg', default='#FFFFFF', help='light theme panel background')
    args = ap.parse_args(argv)

    all_results = []
    worst = -1

    palette_paths = []
    if args.palette:
        palette_paths.append(args.palette)
    elif args.check_colors:
        default_palette = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                       '..', 'assets', 'palette.json')
        if os.path.exists(default_palette):
            palette_paths.append(default_palette)

    for ppath in palette_paths:
        lint = Linter()
        with open(ppath, encoding='utf-8') as fh:
            pal = json.load(fh)
        lint_palette(pal, lint, args.dark_bg, args.light_bg)
        all_results.append((ppath, 'palette', lint.findings))
        worst = max(worst, max((SEVERITY_ORDER[f.severity] for f in lint.findings), default=-1))

    for path in args.paths:
        try:
            flavour, doc = load_document(path)
        except (json.JSONDecodeError, OSError, ValueError) as exc:
            lint = Linter()
            lint.add('error', 'X001', path, f'Could not lint this file: {exc}',
                     'Pass a Grafana dashboard JSON, a Perses dashboard YAML/JSON, or a '
                     'palette file — or narrow the glob.')
            all_results.append((path, 'unreadable', lint.findings))
            worst = max(worst, SEVERITY_ORDER['error'])
            continue

        if flavour == 'palette':
            lint = Linter()
            lint_palette(doc, lint, args.dark_bg, args.light_bg)
            all_results.append((path, 'palette', lint.findings))
            worst = max(worst,
                        max((SEVERITY_ORDER[f.severity] for f in lint.findings), default=-1))
            continue

        lint = Linter()
        if flavour == 'grafana':
            lint_grafana(doc, lint)
        elif flavour == 'grafana-v2':
            lint.add('warn', 'G001', 'dashboard',
                     'Grafana schema v2 (dashboard.grafana.app/v2*) — this linter checks the '
                     'classic/v1 model.',
                     'Export the classic JSON model for a full check, or review manually '
                     'against references/review-rubric.md.')
            inner = (doc.get('spec') or {})
            if 'elements' in inner:
                shim = {'panels': [e for e in inner['elements'].values()
                                   if isinstance(e, dict)],
                        'templating': {'list': []}}
                lint_grafana(shim, lint)
        else:
            lint_perses(doc, lint)

        if args.audience != 'off':
            lint_audience('perses' if flavour == 'perses' else 'grafana',
                          doc, lint, args.audience)

        if args.check_colors:
            lint_colors(collect_hex_colors(doc), lint, args.dark_bg, args.light_bg,
                        label=os.path.basename(path))

        all_results.append((path, flavour, lint.findings))
        worst = max(worst, max((SEVERITY_ORDER[f.severity] for f in lint.findings), default=-1))

    if args.format == 'json':
        print(json.dumps([
            {'path': p, 'flavour': f, 'findings': [asdict(x) for x in fs]}
            for p, f, fs in all_results
        ], indent=2))
    else:
        for p, f, fs in all_results:
            report_text(p, f, fs, args.min_severity)

    if args.fail_on == 'never':
        return 0
    return 1 if worst >= SEVERITY_ORDER[args.fail_on] else 0


if __name__ == '__main__':
    sys.exit(main())
