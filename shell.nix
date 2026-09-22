{ pkgs ? import <nixpkgs-unstable> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    bash
    git
    jdk25_headless
    maven
    protobuf
    just
    python3  # contrib/reply-pcap.py and the fixture-test scripts need only the standard library
  ];
}
