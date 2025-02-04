{ pkgs ? import <nixpkgs-unstable> {} }:

let

  pyenv = pkgs.python3.withPackages (ps: with ps; [
    pyshark
    click
    tqdm
  ]);

in pkgs.mkShell {
  buildInputs = with pkgs; [
    bash
    git
    jdk23_headless
    maven
    protobuf
    just
    pyenv
  ];
}
