// Old documentation paths and where each moved, per anchor. The stub index.html
// files under docs/static/<old path>/ load this and forward the reader; a bare
// entry ("") is the page a link without an anchor lands on. Add a row here and a
// stub when a page moves again.
window.RIPTIDE_MOVED = {
  "/docs/configuration/agent-configuration": {
    "": "/docs/reference/agent-configuration"
  },
  "/docs/configuration/clickhouse": {
    "": "/docs/reference/clickhouse",
    "credentials": "/docs/reference/clickhouse#credentials",
    "identity-columns": "/docs/reference/clickhouse#identity-columns",
    "insert-batching-batch": "/docs/architecture/persistence#insert-batching-batch",
    "insert-coalescing-async-inserts": "/docs/architecture/persistence#why-coalescing-is-off-under-batching",
    "insert-compression": "/docs/architecture/persistence#why-compression-is-on",
    "provisioning": "/docs/architecture/multi-tenancy#write-isolation-multi-tenant",
    "query-performance": "/docs/guides/tune-samples-queries",
    "retention": "/docs/reference/clickhouse#retention",
    "rollup-shape-checks-at-startup": "/docs/operations/rollup-drift",
    "rollups": "/docs/reference/clickhouse#rollups",
    "rollups-gain-dimensions-in-place": "/docs/architecture/rollups#rollups-gain-dimensions-in-place",
    "schema-ownership": "/docs/reference/clickhouse#schema-ownership",
    "server-requirement": "/docs/reference/clickhouse#server-requirement",
    "server-versions": "/docs/reference/clickhouse#server-versions",
    "startup-wait": "/docs/reference/clickhouse#startup-wait",
    "what-the-barrier-guarantees": "/docs/architecture/multi-tenancy#what-the-barrier-guarantees",
    "write-isolation-multi-tenant": "/docs/architecture/multi-tenancy#write-isolation-multi-tenant"
  },
  "/docs/configuration/discovery": {
    "": "/docs/reference/discovery",
    "choosing-a-source": "/docs/architecture/discovery#choosing-a-source",
    "configuration": "/docs/reference/discovery#settings",
    "decommissioning-a-fleet": "/docs/architecture/discovery#decommissioning-a-fleet",
    "how-a-failure-names-itself": "/docs/architecture/discovery#how-a-failure-names-itself",
    "how-a-target-becomes-an-exporter": "/docs/architecture/discovery#how-a-target-becomes-an-exporter",
    "if-your-inventory-emits-prometheus-service-discovery": "/docs/guides/discovery-prometheus-sd",
    "if-your-inventory-is-somewhere-else": "/docs/guides/discovery-mapped-json",
    "if-your-inventory-lives-in-netbox": "/docs/guides/discovery-netbox",
    "limits": "/docs/reference/discovery#limits",
    "mapping-your-own-endpoint": "/docs/reference/discovery#mapping-your-own-endpoint",
    "metrics": "/docs/reference/discovery#metrics",
    "narrowing-what-the-endpoint-returns": "/docs/reference/discovery#settings",
    "nautobot": "/docs/guides/discovery-nautobot#nautobot",
    "startup": "/docs/architecture/discovery#startup",
    "what-a-path-is-and-what-it-is-not": "/docs/architecture/discovery#what-a-path-is-and-what-it-is-not",
    "what-discovery-owns": "/docs/architecture/discovery#what-discovery-owns",
    "what-gets-refused": "/docs/architecture/discovery#what-gets-refused"
  },
  "/docs/configuration/exporter-enrichment": {
    "": "/docs/reference/exporter-enrichment"
  },
  "/docs/configuration/geoip": {
    "": "/docs/reference/geoip"
  },
  "/docs/configuration/mcp-server": {
    "": "/docs/reference/mcp-server"
  },
  "/docs/configuration/outbound-tls": {
    "": "/docs/reference/outbound-tls"
  },
  "/docs/configuration/receivers": {
    "": "/docs/reference/receivers",
    "exporter-identity": "/docs/architecture/session-state#exporter-identity",
    "option-records-nobody-used": "/docs/architecture/sampling#option-records-nobody-used",
    "sampling-corrected-volume-beyond-raw-retention": "/docs/guides/sampling-corrected-volume",
    "sampling-rate": "/docs/architecture/sampling",
    "session-state-bounds": "/docs/reference/receivers#session-state-bounds",
    "sflow-semantics": "/docs/architecture/session-state#what-an-sflow-sample-becomes",
    "sizing": "/docs/architecture/session-state#how-the-bounds-are-sized",
    "timeout-fallbacks": "/docs/reference/receivers#keys-by-receiver-type",
    "watching-them": "/docs/reference/metrics#session-state-bounds",
    "what-this-is-not": "/docs/architecture/session-state#what-bounding-is-not",
    "when-a-bound-is-reached": "/docs/architecture/session-state#what-happens-when-a-bound-is-reached",
    "where-a-rate-came-from": "/docs/architecture/sampling#where-a-rate-came-from"
  },
  "/docs/configuration/routing": {
    "": "/docs/reference/routing"
  },
  "/docs/configuration/secret-references": {
    "": "/docs/reference/secret-references"
  },
  "/docs/deploy/docker-compose": {
    "": "/docs/guides/docker-compose"
  },
  "/docs/deploy/linux-packages": {
    "": "/docs/guides/linux-packages"
  },
  "/docs/deploy/multi-tenancy": {
    "": "/docs/operations/tenants/onboard-a-tenant",
    "adding-rollups-to-an-existing-deployment": "/docs/operations/tenants/onboard-a-tenant#adding-rollups-to-an-existing-deployment",
    "adding-the-dead-letter-table-to-an-existing-deployment": "/docs/operations/tenants/onboard-a-tenant#adding-the-dead-letter-table-to-an-existing-deployment",
    "admin-privileges": "/docs/reference/provisioning-cli#admin-privileges",
    "dropping-the-roles": "/docs/architecture/multi-tenancy#dropping-the-roles",
    "grafana-topology": "/docs/architecture/multi-tenancy#grafana-topology",
    "object-names-carry-their-database": "/docs/architecture/multi-tenancy#object-names-carry-their-database",
    "onboard-a-tenant": "/docs/operations/tenants/onboard-a-tenant",
    "open-questions": "/docs/architecture/multi-tenancy#open-questions",
    "prerequisites": "/docs/operations/tenants/onboard-a-tenant#prerequisites",
    "revoking-the-pre-rename-roles-on-a-migrated-database": "/docs/operations/tenants/migrate-tenant-accounts#revoking-the-pre-rename-roles-on-a-migrated-database",
    "the-identity-model": "/docs/architecture/multi-tenancy#the-identity-model",
    "upgrading-a-deployment-onboarded-before-the-rename": "/docs/operations/tenants/migrate-tenant-accounts#upgrading-a-deployment-onboarded-before-the-rename",
    "what-it-provisions": "/docs/reference/provisioning-cli#what-onboard-emits",
    "what-the-reader-guarantees": "/docs/architecture/multi-tenancy#what-the-reader-guarantees",
    "when-it-refuses": "/docs/operations/tenants/migrate-tenant-accounts#when-it-refuses"
  },
  "/docs/deploy/nixos": {
    "": "/docs/guides/nixos"
  },
  "/docs/deploy/operations": {
    "": "/docs/operations/troubleshooting",
    "a-stable-application-name": "/docs/operations/profiling#give-the-service-a-stable-name",
    "classification-rule-reloads": "/docs/architecture/reloading#classification-rule-reloads",
    "config-hot-reload": "/docs/operations/hot-reload",
    "containers-and-what-to-do-when-a-profile-looks-wrong": "/docs/operations/profiling#fall-back-to-jfr-when-a-profile-looks-wrong",
    "continuous-profiling": "/docs/operations/profiling",
    "dead-letters": "/docs/operations/dead-letters",
    "elements-riptide-parses-and-discards": "/docs/architecture/loss-accounting#elements-riptide-parses-and-discards",
    "enable-native-access-on-a-future-jdk": "/docs/operations/profiling#steps",
    "health-endpoints--probes": "/docs/reference/management#health-endpoints--probes",
    "image-tags": "/docs/reference/management#image-tags",
    "ingest-loss-counters": "/docs/architecture/loss-accounting",
    "memory-budget-for-the-queues": "/docs/architecture/loss-accounting#memory-budget-for-the-queues",
    "metrics-endpoint": "/docs/reference/management#metrics-endpoint",
    "netflow-v5-sampling-rate-resolution": "/docs/architecture/loss-accounting#netflow-v5-sampling-rate-resolution",
    "parser-gauges-exporters-and-templates": "/docs/architecture/loss-accounting#parser-gauges-exporters-and-templates",
    "ports": "/docs/reference/management#ports",
    "restarts-and-data": "/docs/operations/upgrades/upgrade#what-the-schema-check-migrates",
    "supported-ruleset-size": "/docs/architecture/classification-build-cost#supported-ruleset-size",
    "upgrading": "/docs/operations/upgrades/upgrade",
    "what-it-costs-when-it-is-off": "/docs/operations/profiling#what-it-costs-when-it-is-off",
    "what-the-profile-measures-and-which-event-to-ask-for": "/docs/operations/profiling#choose-the-profiler-event"
  },
  "/docs/deploy/plain-jar": {
    "": "/docs/guides/plain-jar"
  },
  "/docs/enrichment": {
    "": "/docs/architecture/enrichment",
    "application-names-from-the-exporter": "/docs/architecture/enrichment#application-names-from-the-exporter",
    "as-numbers-and-names": "/docs/architecture/enrichment#as-numbers-and-names",
    "classification": "/docs/architecture/enrichment#classification",
    "clock-correction": "/docs/architecture/enrichment#clock-correction",
    "http-host-and-uri-from-cisco-avc": "/docs/architecture/enrichment#http-host-and-uri-from-cisco-avc",
    "interface-tables-are-polled-not-looked-up": "/docs/architecture/enrichment#interface-tables-are-polled-not-looked-up",
    "locality": "/docs/architecture/enrichment#locality",
    "migrating-from-riptidesnmpcache": "/docs/operations/upgrades/upgrade#snmp-cache-keys-are-retired",
    "reverse-dns-hostnames": "/docs/architecture/enrichment#reverse-dns-hostnames",
    "snmp-interface-data": "/docs/architecture/enrichment#snmp-interface-data",
    "static-interface-mapping": "/docs/architecture/enrichment#static-interface-mapping",
    "the-enrichment-ladder": "/docs/architecture/enrichment#the-enrichment-ladder",
    "writing-a-rule": "/docs/operations/classification-rules"
  },
  "/docs/getting-started": {
    "": "/docs/#quickstart"
  },
  "/docs/guides/classification-rules": {
    "": "/docs/operations/classification-rules"
  },
  "/docs/guides/hot-reload": {
    "": "/docs/operations/hot-reload"
  },
  "/docs/guides/migrate-tenant-accounts": {
    "": "/docs/operations/tenants/migrate-tenant-accounts"
  },
  "/docs/guides/onboard-a-tenant": {
    "": "/docs/operations/tenants/onboard-a-tenant"
  },
  "/docs/guides/profiling": {
    "": "/docs/operations/profiling"
  },
  "/docs/guides/upgrade": {
    "": "/docs/operations/upgrades/upgrade"
  },
  "/docs/guides/upgrading-from-0.8": {
    "": "/docs/operations/upgrades/upgrading-from-0.8"
  },
  "/docs/upgrading-from-0.8": {
    "": "/docs/operations/upgrades/upgrading-from-0.8"
  }
};
(function () {
  var path = location.pathname.replace(/\/$/, '');
  var moved = window.RIPTIDE_MOVED[path];
  if (!moved) return;
  var anchor = location.hash.slice(1);
  var to = moved[anchor] || (moved[''].indexOf('#') < 0 && anchor ? moved[''] + '#' + anchor : moved['']);
  var hash = to.indexOf('#');
  location.replace(hash < 0 ? to + location.search : to.slice(0, hash) + location.search + to.slice(hash));
})();
