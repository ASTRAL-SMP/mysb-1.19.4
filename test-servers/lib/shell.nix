# 共通 nix-shell 環境。各バージョンの shell.nix が jdk を指定して import する。
{ pkgs ? import <nixpkgs> {}, jdk ? pkgs.jdk17 }:

pkgs.mkShell {
  packages = [
    jdk
    pkgs.curl
    pkgs.jq
    pkgs.gh
  ];

  shellHook = ''
    export JAVA_HOME="${jdk}/lib/openjdk"
    echo "Fabric test-server shell (JDK $(${jdk}/bin/java --version | head -1))"
  '';
}
