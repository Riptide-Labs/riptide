---
sidebar_position: 4
title: NixOS
---

# Deploy on NixOS

Riptide ships a [flake](https://github.com/Riptide-Labs/riptide/blob/main/flake.nix) that builds
the engine from source and exposes a NixOS module. Nix/NixOS is a separate track from the
[DEB/RPM packages](linux-packages.md) — there is no `.nix` artifact to download; you reference the
flake by revision.

## Run it directly

```bash
nix run github:Riptide-Labs/riptide -- --help
```

`nix build github:Riptide-Labs/riptide#default` produces `result/bin/riptide` (a `java -jar`
launcher over the fat jar) and requires no local JDK. Pin a release by ref:
`github:Riptide-Labs/riptide?ref=v0.3.1`.

## NixOS module

Add the flake as an input and import `nixosModules.default`:

```nix
{
  inputs.riptide.url = "github:Riptide-Labs/riptide";

  outputs = { nixpkgs, riptide, ... }: {
    nixosConfigurations.collector = nixpkgs.lib.nixosSystem {
      modules = [
        riptide.nixosModules.default
        {
          services.riptide = {
            enable = true;
            settings = {
              riptide.clickhouse.endpoint = "http://clickhouse:8123";
              riptide.receivers.ipfix = { type = "ipfix"; host = "0.0.0.0"; port = 4739; };
            };
            environmentFile = "/run/secrets/riptide.env";
          };
        }
      ];
    };
  };
}
```

`settings` is freeform — everything from the [configuration chapters](../configuration/receivers.md)
goes there. It is rendered to YAML and exposed at `/etc/riptide/config.yaml`, so the configuration
reference applies verbatim. The service runs under `DynamicUser` with the same sandboxing as the
packaged unit (`NoNewPrivileges`, `PrivateTmp`, `ProtectSystem=strict`, `ProtectHome`), and restarts
automatically when `settings` change.

## Secrets

The rendered config lives in the world-readable Nix store, so **do not put credentials in
`settings`**. Use `environmentFile` (kept out of the store) for values like passwords, or a
[secret reference](../configuration/secret-references.md) (`env://`, `file://`, `vault://`,
`sops://`) that Riptide resolves at runtime.

## Firewall

Receiver ports depend on your configuration; the module does not open any. Add the ones you use:

```nix
networking.firewall.allowedUDPPorts = [ 4739 ];
```

## Development shell

The flake's dev shell mirrors the toolchain in `shell.nix` (JDK 25, Maven, protobuf, and the pcap
tooling):

```bash
nix develop
make
```
