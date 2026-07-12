/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import {themes as prismThemes} from 'prism-react-renderer';

const config: Config = {
  title: 'Riptide',
  tagline: 'Give people who love working with networks the tools they deserve to optimize and troubleshoot network traffic.',
  favicon: 'img/riptide-logo.png',

  url: 'https://riptide-labs.github.io',
  baseUrl: '/riptide/',

  organizationName: 'Riptide-Labs',
  projectName: 'riptide',

  onBrokenLinks: 'throw',

  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  future: {
    v4: true,
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/Riptide-Labs/riptide/tree/main/docs/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    navbar: {
      // no text title — the logo already carries the "Riptide" wordmark
      logo: {
        alt: 'Riptide logo',
        src: 'img/riptide-logo.png',
      },
      items: [
        {
          href: 'https://github.com/Riptide-Labs/riptide',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      copyright: `Copyright © ${new Date().getFullYear()} Riptide Labs. Licensed under GPL-3.0-or-later.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'properties', 'bash', 'yaml'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
