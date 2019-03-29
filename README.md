# eos-cljs

This is WIP. It requires a `docker-eos` local network.

`npm install`

## CLI

To use the command line you'll have to install this dependency
manually:

`clj -Sdeps '{:deps {org.clojure/tools.cli {:mvn/version "0.3.7"}}}'` (version 0.4.1 gives warning in bootstrapped clojure, will be fixed soon)

## Examples

`npm start -- -a jesse -p /home/jesse/repos/effectai/docker-eos/effect.contracts/contracts/effect.token/effect.token deploy`
