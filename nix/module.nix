# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.riptide;
  format = pkgs.formats.yaml { };
  configFile = format.generate "riptide-config.yaml" cfg.settings;
in
{
  options.services.riptide = {
    enable = lib.mkEnableOption "the Riptide NetFlow analysis engine";

    package = lib.mkOption {
      type = lib.types.package;
      default = pkgs.riptide;
      defaultText = lib.literalExpression "pkgs.riptide";
      description = "The Riptide package to run.";
    };

    settings = lib.mkOption {
      type = format.type;
      default = { };
      example = lib.literalExpression ''
        {
          riptide.clickhouse.endpoint = "http://clickhouse:8123";
          riptide.receivers.ipfix = { type = "ipfix"; host = "0.0.0.0"; port = 4739; };
        }
      '';
      description = ''
        Riptide configuration, rendered to YAML and exposed at
        `/etc/riptide/config.yaml`. See the configuration reference at
        <https://riptide.space/docs/configuration/receivers>.

        The rendered file is world-readable in the Nix store; keep inline
        credentials out of it and use {option}`services.riptide.environmentFile`
        or secret references (`env://`, `file://`, `vault://`, `sops://`) instead.
      '';
    };

    environmentFile = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      example = "/run/secrets/riptide.env";
      description = ''
        Environment file (JAVA_OPTS, secret values) sourced by the service.
        Kept outside the world-readable Nix store.
      '';
    };
  };

  config = lib.mkIf cfg.enable {
    environment.etc."riptide/config.yaml".source = configFile;

    systemd.services.riptide = {
      description = "Riptide NetFlow analysis engine";
      documentation = [ "https://riptide.space/docs/" ];
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];

      # New config renders a new /etc link; restart the unit to pick it up.
      restartTriggers = [ configFile ];

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        Restart = "on-failure";
        SuccessExitStatus = 143; # JVM exit on SIGTERM
        EnvironmentFile = lib.mkIf (cfg.environmentFile != null) cfg.environmentFile;

        DynamicUser = true;
        NoNewPrivileges = true;
        PrivateTmp = true;
        ProtectSystem = "strict";
        ProtectHome = true;
      };
    };
  };
}
