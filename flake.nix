# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
{
  description = "Riptide — NetFlow analysis engine";

  # Pinned to a nixos-unstable rev that carries jdk25_headless. flake.lock is the
  # real pin; bump it manually with `nix flake update` (no Dependabot flake support).
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/18b9261cb3294b6d2a06d03f96872827b8fe2698";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems =
        f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAllSystems (pkgs: {
        default = pkgs.callPackage ./nix/package.nix { };
        riptide = self.packages.${pkgs.system}.default;
      });

      # `nix develop` — reuse shell.nix so the two toolchains cannot drift.
      devShells = forAllSystems (pkgs: {
        default = import ./shell.nix { inherit pkgs; };
      });

      # Wrap the bare module so its package default resolves to this flake's
      # build — a stock consumer's nixpkgs has no `riptide` attribute.
      nixosModules.default =
        { pkgs, lib, ... }:
        {
          imports = [ ./nix/module.nix ];
          services.riptide.package = lib.mkDefault self.packages.${pkgs.stdenv.hostPlatform.system}.default;
        };

      # Eval-based module check (no KVM needed): builds a NixOS system that
      # enables the service and asserts the rendered unit + config land.
      checks = forAllSystems (pkgs: {
        module-eval =
          let
            sys = nixpkgs.lib.nixosSystem {
              inherit (pkgs) system;
              modules = [
                self.nixosModules.default
                (
                  { ... }:
                  {
                    # Container stub keeps the eval light (no bootloader/fs assertions).
                    boot.isContainer = true;
                    system.stateVersion = "24.11";
                    services.riptide = {
                      enable = true;
                      settings.riptide.clickhouse.endpoint = "http://clickhouse:8123";
                    };
                  }
                )
              ];
            };
            svc = sys.config.systemd.services.riptide.serviceConfig;
          in
          assert svc.DynamicUser == true;
          assert nixpkgs.lib.hasSuffix "/bin/riptide" svc.ExecStart;
          assert sys.config.environment.etc ? "riptide/config.yaml";
          pkgs.runCommand "riptide-module-eval-ok" { } ''
            echo "module renders: DynamicUser, ExecStart=${svc.ExecStart}, /etc/riptide/config.yaml wired" > $out
          '';
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
