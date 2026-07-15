---
sidebar_position: 1
title: Environment
---

# Development environment

**Requirements:** git, Java 25, Maven, Docker (for integration/e2e tests and `make oci`).

```bash
git clone https://github.com/Riptide-Labs/riptide.git && cd riptide
make            # compile with tests → runnable jar in target/
make help       # all goals
```

Nix users can get the full toolchain (JDK 25, Maven, protobuf, pcap tooling) with
`nix develop` (flake) or `nix-shell` (the `shell.nix`), then run `make` as above.

| Goal | What it does |
|---|---|
| `make` / `make jar` | full build with tests and all quality gates |
| `make coverage` | unit suite + JaCoCo report (`target/site/jacoco/`) |
| `make e2e` | integration/e2e tier (`*IT`, needs Docker) |
| `make oci` | build the `riptide:local` container image |
| `make docs` / `make docs-serve` | this documentation site |

## IDE

Import as a Maven project. The build relies on annotation processing (Lombok, MapStruct)
— enable it in your IDE. `.mvn/jvm.config` (committed) provides the `jdk.compiler`
exports that Error Prone needs; Maven picks it up automatically, no setup required.

Run/debug the app via `org.riptide.RiptideApplication` — see
[Run & debug](run-and-debug.md).
