---
sidebar_position: 4
title: NixOS
description: Run riptide from the flake, or import the NixOS module and its four options.
---

# Deploy on NixOS

The repository's [flake](https://github.com/Riptide-Labs/riptide/blob/main/flake.nix) builds riptide from source and exposes a NixOS module.
There is no `.nix` artifact on the release page; reference the flake by revision.
The [DEB and RPM packages](linux-packages.md) are a separate track.

## Run the package

```bash
nix run 'github:Riptide-Labs/riptide?ref=v%%VERSION%%' -- convert --help
```

Expected output:

```text
usage: riptide convert <legacy-config.yaml> [--out-config <path>] [--out-inventory <path>] [--force]

  Converts a 0.8 riptide.nodes configuration into 0.9 form. Emits two
  documents: credential sets and polling profiles for the main config, and
  agent ranges and enrichment entries for the inventory file.

  Without --out flags both go to stdout separated by '---', and the summary
  goes to stderr so the output can be redirected.
```

Without a subcommand the launcher starts the collector, which reads `/etc/riptide/config.yaml`; there is no `--help` for the daemon itself.
The ref is quoted because `?` is a glob character in zsh.

`nix build 'github:Riptide-Labs/riptide?ref=v%%VERSION%%#default'` produces `result/bin/riptide`, a launcher that execs `java -jar` on the fat jar, and needs no local JDK.

## Module options

Import `riptide.nixosModules.default` from the flake input.

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| **`services.riptide.enable`** | bool | `false` | Creates the `riptide` systemd service. |
| **`services.riptide.package`** | package | the flake's default package | The riptide package to run. |
| **`services.riptide.settings`** | freeform YAML attribute set | `{ }` | Rendered to YAML and linked at `/etc/riptide/config.yaml`, so every key from the configuration chapters applies verbatim. The file lives in the world-readable Nix store. |
| **`services.riptide.environmentFile`** | path or null | `null` | Sourced by the unit. Kept outside the store, so it is where secret values and JVM options go. |

```nix
{
  inputs.riptide.url = "github:Riptide-Labs/riptide?ref=v%%VERSION%%";

  outputs = { nixpkgs, riptide, ... }: {
    nixosConfigurations.collector = nixpkgs.lib.nixosSystem {
      modules = [
        riptide.nixosModules.default
        {
          services.riptide = {
            enable = true;
            settings = {
              riptide.clickhouse.endpoint = "http://clickhouse:8123";
              riptide.clickhouse.password = "env://CLICKHOUSE_PASSWORD";
              riptide.receivers.ipfix = { type = "ipfix"; host = "0.0.0.0"; port = 4739; };
            };
            environmentFile = "/run/secrets/riptide.env";
          };
          networking.firewall.allowedUDPPorts = [ 4739 ];
        }
      ];
    };
  };
}
```

## What the module configures

| Directive | Value | Effect |
| --- | --- | --- |
| `ExecStart` | the package's launcher | |
| `DynamicUser` | `true` | A transient user per start; nothing persists under a fixed uid. |
| `NoNewPrivileges`, `PrivateTmp`, `ProtectHome` | `true` | Same sandbox as the packaged unit. |
| `ProtectSystem` | `strict` | Filesystem read-only to the service. Reads work outside the paths `ProtectHome` and `PrivateTmp` hide, so a `file://` secret or a database under `/root`, `/home` or `/tmp` is not reachable. |
| `Restart` | `on-failure` | |
| `SuccessExitStatus` | `143` | The JVM's exit code on SIGTERM counts as a clean stop. |
| `restartTriggers` | the rendered config | A change to `settings` restarts the unit on activation. |
| `After`, `Wants` | `network-online.target` | |

The module opens no firewall port.
Add every receiver port you configure, as the example does.

## Secrets and JVM options

Do not put credentials in `settings`: the rendered file is in the Nix store.
Use a [secret reference](../configuration/secret-references.md) in `settings` and supply the value through `environmentFile`, as the example does with `env://CLICKHOUSE_PASSWORD`.

JVM options go in `environmentFile` as **`JDK_JAVA_OPTIONS`**, which the `java` launcher reads itself:

```text
CLICKHOUSE_PASSWORD=...
JDK_JAVA_OPTIONS=-Xmx2g
```

`JAVA_OPTS` is silently discarded here.
The launcher execs `java` directly with no shell to expand it, unlike the packaged unit, whose `ExecStart` does expand `$JAVA_OPTS`.

## Development shell

The flake's dev shell mirrors `shell.nix`: JDK 25, Maven, protobuf and the pcap tooling.

```bash
nix develop
make
```

## Open questions

- No `nix` command was run for this page. The options and unit directives are read from `nix/module.nix` and `flake.nix`; the `nix run`, `nix build` and `nix develop` invocations follow the flake's outputs and were not executed `(unverified)`.
