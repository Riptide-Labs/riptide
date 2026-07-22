# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
{
  lib,
  maven,
  jdk25_headless,
  makeWrapper,
}:

let
  # Project version straight from the pom (source of truth); string-split avoids
  # nix regex's lack of dotall.
  pom = builtins.readFile ../pom.xml;
  afterId = lib.elemAt (lib.splitString "<artifactId>riptide-flows</artifactId>" pom) 1;
  version = lib.elemAt (lib.splitString "</version>" (lib.elemAt (lib.splitString "<version>" afterId) 1)) 0;
in
maven.buildMavenPackage {
  pname = "riptide-flows";
  inherit version;

  # Flake source = the git tree; drop build/doc trees so the deps hash and store
  # copy stay stable and small.
  src = lib.cleanSourceWith {
    src = ../.;
    filter =
      path: type:
      let
        base = baseNameOf path;
      in
      !(builtins.elem base [
        "target"
        "docs"
        "openspec"
        "deployment"
        "artwork"
        "result"
      ]);
  };

  # JAVA_HOME for the deps + build phases (Maven honors it over maven's bundled JDK).
  mvnJdk = jdk25_headless;

  # Tests need Docker/ClickHouse testcontainers, unavailable in the nix sandbox.
  doCheck = false;

  # git-commit-id plugin reads .git, which is not part of the store source.
  mvnParameters = "-Dmaven.gitcommitid.skip=true";

  # Fixed-output hash of the maven dependency set. Regenerate with `make nix-hash` whenever the
  # pom changes; the nix CI job fails the PR if it drifts and prints the expected hash in its
  # job summary.
  mvnHash = "sha256-k617eYQ/aWBGSSFkUI78XPkbNR0VYd/dCiBv+RC0B/w=";

  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/riptide
    cp target/riptide-flows-*.jar $out/share/riptide/riptide.jar
    makeWrapper ${jdk25_headless}/bin/java $out/bin/riptide \
      --add-flags "-jar $out/share/riptide/riptide.jar"
    runHook postInstall
  '';

  meta = {
    description = "NetFlow analysis engine";
    homepage = "https://github.com/Riptide-Labs/riptide";
    license = lib.licenses.gpl3Plus;
    mainProgram = "riptide";
    platforms = lib.platforms.linux;
  };
}
