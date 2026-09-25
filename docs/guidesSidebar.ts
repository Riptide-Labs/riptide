/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import type {PluginOptions} from '@docusaurus/plugin-content-docs';

// The Guides category groups its pages by task. Every other category stays
// exactly as the default generator builds it from its folder and _category_.json.
// The pages stay in docs/guides/, so no URL changes. A guide missing here, or a
// name here with no page, fails the build.
const GROUPS = [
  {
    label: 'Deploy',
    description: 'Install riptide and its dashboards with Docker Compose, the plain jar, DEB and RPM packages or NixOS.',
    docs: ['docker-compose', 'plain-jar', 'linux-packages', 'nixos', 'grafana-dashboards'],
  },
  {
    label: 'Upgrades',
    description: 'Move a running deployment to a new release, and the one-time steps when coming from 0.8.',
    docs: ['upgrade', 'upgrading-from-0.8'],
  },
  {
    label: 'Operate',
    description: 'Change a running collector: reload its configuration without a restart, profile it, and classify traffic with your own rules.',
    docs: ['hot-reload', 'profiling', 'classification-rules'],
  },
  {
    label: 'Discover exporters',
    description: 'Fill exporter enrichment from a source of truth instead of the inventory file, one guide per source.',
    docs: [
      ['discovery-netbox', 'NetBox'],
      ['discovery-prometheus-sd', 'Prometheus service discovery'],
      ['discovery-nautobot', 'Nautobot'],
      ['discovery-mapped-json', 'Any JSON endpoint'],
    ],
  },
  {
    label: 'Query flows',
    description: 'Get correct volumes out of ClickHouse: correct for sampling, keep queries over samples() fast, and backfill a rollup.',
    docs: ['sampling-corrected-volume', 'tune-samples-queries', 'backfill-a-rollup'],
  },
  {
    label: 'Tenants',
    description: 'Give each tenant its own ClickHouse account, and migrate accounts created before the rename.',
    docs: ['onboard-a-tenant', 'migrate-tenant-accounts'],
  },
] as const;

export const guidesSidebar: PluginOptions['sidebarItemsGenerator'] = async ({defaultSidebarItemsGenerator, ...args}) => {
  const items = await defaultSidebarItemsGenerator(args);
  const guides = args.docs.filter((doc) => doc.sourceDirName === 'guides').map((doc) => doc.id);
  const grouped = GROUPS.flatMap((group) => group.docs.map((doc) => 'guides/' + (typeof doc === 'string' ? doc : doc[0])));
  const ungrouped = guides.filter((id) => !grouped.includes(id));
  const missing = grouped.filter((id) => !guides.includes(id));
  if (ungrouped.length > 0 || missing.length > 0) {
    throw new Error(`docs/guidesSidebar.ts is out of date. Not in any group: [${ungrouped}]. No such page: [${missing}].`);
  }
  return items.map((item) =>
    item.type === 'category' && item.label === 'Guides'
      ? {
          ...item,
          items: GROUPS.map((group) => ({
            type: 'category' as const,
            label: group.label,
            collapsed: true,
            link: {
              type: 'generated-index' as const,
              slug: '/guides/' + group.label.toLowerCase().replace(/ /g, '-'),
              description: group.description,
            },
            items: group.docs.map((doc) =>
              typeof doc === 'string'
                ? {type: 'doc' as const, id: 'guides/' + doc}
                : {type: 'doc' as const, id: 'guides/' + doc[0], label: doc[1]},
            ),
          })),
        }
      : item,
  );
};
