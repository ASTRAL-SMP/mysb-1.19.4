{ pkgs ? import <nixpkgs> {} }:
import ../lib/shell.nix { inherit pkgs; jdk = pkgs.jdk21; }
