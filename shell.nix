{ pkgs ? import <nixpkgs-unstable> {} }:

let

in pkgs.mkShell {
  buildInputs = with pkgs; [
    bash
    git
    jdk21_headless
    maven
    protobuf
    just
  ];
}
