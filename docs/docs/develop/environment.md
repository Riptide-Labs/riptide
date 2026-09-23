---
sidebar_position: 1
title: Environment
description: The toolchain, the clone-and-build steps with their output, the make targets a contributor uses, IDE setup and the Nix shell.
---

# Set up a development environment

## Prerequisites

| Requirement | Version | Needed for |
| --- | --- | --- |
| JDK | 25 | Everything. `make` checks the major version of `java` on the path and stops on any other. |
| Maven | no floor enforced; `make` prints the version it found | Everything. There is no wrapper, so `mvn` has to be on the path. |
| git | any | The clone, and the branch and commit stamps `make` reads. |
| Docker with the Compose plugin | any current | `make e2e`, `make oci`, `make packages`, `make packages-smoke`, `make compose-smoke`, and the [local stack](run-and-debug.md). |
| Node.js and npm | Node 20 or newer; CI uses 24 | `make docs` and `make docs-serve`. |
| Python 3 | any; standard library only | `contrib/reply-pcap.py` and the Python-based `*-test` fixture targets. |

## Steps

1. Clone and build:

   ```bash
   git clone https://github.com/Riptide-Labs/riptide.git
   cd riptide
   make
   ```

   Expected output, excerpts:

   ```text
   Found `java`
   Found `javac`
   Found `mvn`
   ...
   [INFO] BugInstance size is 0
   [INFO] Error size is 0
   [INFO] No errors/warnings found
   ...
   [INFO] All coverage checks have been met.
   [INFO] ------------------------------------------------------------------------
   [INFO] BUILD SUCCESS
   [INFO] ------------------------------------------------------------------------
   [INFO] Total time:  02:41 min
   ```

   The jar is **`target/riptide-flows-<version>.jar`**, with the version from `pom.xml`.

2. Run it against a local ClickHouse: [Run and debug locally](run-and-debug.md).

## Make targets

| Target | What it does | Needs |
| --- | --- | --- |
| **`make`**, **`make jar`** | `mvn verify`: compile, unit tests, Checkstyle, Error Prone, SpotBugs and the coverage floor. The command CI's `build` job runs. | JDK, Maven |
| **`make coverage`** | `mvn test jacoco:report`: Checkstyle, Error Prone, unit tests and the JaCoCo report at `target/site/jacoco/index.html`. Skips SpotBugs and the coverage floor. | JDK, Maven |
| **`make e2e`** | `make jar` plus every `*IT` class; see [Testing](testing.md). | Docker |
| **`make fuzz`** | Coverage-guided fuzzing of the parsers; see [Fuzz harnesses](testing.md#fuzz-harnesses) for its variables. | JDK, Maven |
| **`make oci`** | Builds the `riptide:local` image from the jar. | Docker |
| **`make packages`**, **`make packages-smoke`** | DEB and RPM from the jar through nfpm; install them in Debian and Rocky containers. | Docker |
| **`make compose-smoke`** | Starts the shipped compose stack and asserts its ClickHouse and Grafana wiring. | Docker |
| **`make docs`**, **`make docs-serve`** | Builds the site into `docs/build` and lints the rendered pages; serves it with live reload. | npm |
| **`make lint-actions`** | actionlint and zizmor over `.github/workflows`. | actionlint, zizmor |
| **`make nix`**, **`make nix-check`**, **`make nix-hash`** | Builds the flake package; runs the flake checks; regenerates `mvnHash` in `nix/package.nix` after a `pom.xml` change. | Nix |
| **`make clean`** | `mvn clean`. | JDK, Maven |

`make help` lists the release, benchmark and checker-fixture targets as well.

## Configure the IDE

Import the checkout as a Maven project.

- Enable annotation processing.
  Lombok, MapStruct, the JMH generator and Error Prone are declared on the compiler plugin's annotation processor path in `pom.xml`, so a Maven import picks them up.
- **`.mvn/jvm.config`** carries the `--add-exports` and `--add-opens` flags Error Prone needs on the `jdk.compiler` module.
  Maven reads the file on its own.
  An IDE build that compiles without Maven needs the same flags.
- Run configuration: main class `org.riptide.RiptideApplication`, program arguments from [Run and debug locally](run-and-debug.md#steps).

## Use the Nix shell

Run **`nix develop`** for the flake's devShell, or **`nix-shell`** for `shell.nix`, which the flake reuses so the two cannot drift.

```bash
nix develop
make
```

The shell provides bash, git, `jdk25_headless`, Maven, protobuf, `just` and Python 3.
Docker and npm are not part of it.
Running riptide on NixOS is on the [NixOS page](../deploy/nixos.md).

## Open questions

- `nix develop` and `nix-shell` were not run for this page; their output is not shown.
