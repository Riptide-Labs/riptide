# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

.DEFAULT_GOAL := jar

SHELL               := bash -o nounset -o pipefail -o errexit
# Lazy one-shot: targets that never expand VERSION pay no mvn call; the first
# expansion runs mvn once and caches (env/CLI overrides still win).
ifeq ($(origin VERSION),undefined)
VERSION              = $(eval VERSION := $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout))$(VERSION)
endif
GIT_BRANCH          := $(shell git branch --show-current)
GIT_SHORT_HASH      := $(shell git rev-parse --short HEAD)
RELEASE_VERSION     := UNSET.0.0
PUSH_RELEASE        := false
MAJOR_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f1)
MINOR_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f2)
PATCH_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f3)
SNAPSHOT_VERSION    := $(MAJOR_VERSION).$(MINOR_VERSION).$(shell expr $(PATCH_VERSION) + 1)-SNAPSHOT
OCI_TAG             := riptide:local
DATE                := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ") # Date format RFC3339
JAVA_MAJOR_VERSION  := 25
# Pinned like actionlint and zizmor: an unpinned `npx all-contributors-cli` would
# let a new release rewrite README.md and turn the sync check red on its own
ALL_CONTRIBUTORS_VERSION := 6.26.1
RELEASE_LOG         := target/release.log
OK                  := "[ 👍 ]"
SKIP                := "[ ⏭️ ]"
FAIL                := "[ ❌ ]"
BUILD_OPTS          := "-DskipTests=false"
# rpm forbids '-' in versions; tilde sorts before the release (correct upgrade semantic)
PKG_VERSION          = $(subst -SNAPSHOT,~SNAPSHOT,$(VERSION))
# nfpm runs via its OCI image; the pin lives in Dockerfile.nfpm so Dependabot bumps it
NFPM_IMAGE           = $(shell awk '/^FROM/ {print $$2; exit}' deployment/package/Dockerfile.nfpm)
# --user: without it, rootful Docker on Linux leaves root-owned files in target/
NFPM                 = docker run --rm --user "$(shell id -u):$(shell id -g)" -v $(CURDIR):/work -w /work -e VERSION $(NFPM_IMAGE)

REQUIRED_BINS := java javac mvn
$(foreach bin,$(REQUIRED_BINS),\
    $(if $(shell command -v $(bin) 2> /dev/null),$(info Found `$(bin)`),$(error Please install `$(bin)`)))

$(subst $e ,_,$(ITEM))

.PHONY: help
help:
	@echo ""
	@echo "Build Riptide from source"
	@echo "Goals:"
	@echo "  help:         Show this help with explaining the build goals"
	@echo "  jar:          Compile Riptide from source with tests and generate a runnable jar file in the target directory"
	@echo "  oci:          Build OCI container image"
	@echo "  packages:     Build DEB and RPM packages from the jar (requires Docker)"
	@echo "  packages-smoke: Install the packages in Debian and Rocky containers and smoke-test them (requires Docker)"
	@echo "  compose-smoke: Bring up the shipped compose stack and assert its ClickHouse and Grafana wiring (requires Docker)"
	@echo "  sbom-assert:  Assert license facts in a release SBOM; SBOM=<path to .spdx.json>"
	@echo "  sbom-assert-test: Run the SBOM assertion script's fixture tests"
	@echo "  nix:          Build the flake package from source (requires Nix)"
	@echo "  nix-check:    Run the flake checks incl. the NixOS module eval (requires Nix)"
	@echo "  nix-hash:     Regenerate nix/package.nix's mvnHash after a pom change (requires Nix)"
	@echo "  coverage:     Run the unit test suite and render the JaCoCo coverage report"
	@echo "  e2e:          Run integration and e2e tests (*IT, requires Docker) in addition to the unit suite"
	@echo "  fuzz:         Coverage-guided fuzzing of the flow parsers (Jazzer); FUZZ_TIME=<seconds> per target"
	@echo "  bench:        Run the FR-1 budget benchmarks (src/bench) with ratio assertions"
	@echo "  bench-jmh:    Run the JMH microbenchmarks; BENCH_TARGET=<regex> to narrow, BENCH_OPTS=<jmh flags>"
	@echo "  lint-actions: Lint the GitHub Actions workflows (actionlint + zizmor)"
	@echo "  contributors: Regenerate the README contributor badge and table from .all-contributorsrc"
	@echo "  contributors-check: Fail if the README contributor section is out of sync with .all-contributorsrc"
	@echo "  docs:         Build the Docusaurus documentation site into docs/build"
	@echo "  docs-serve:   Run the documentation site locally with live reload"
	@echo "  landing-serve: Serve the landing page locally for preview"
	@echo "  clean:        Clean the build artifacts"
	@echo ""

.PHONY: deps-jar
deps-jar:
	@echo Your Maven version
	@mvn --version
	@echo Your Java version
	@java --version
	@echo "Test Java $(JAVA_MAJOR_VERSION) requirement"
	@java --version | grep '$(JAVA_MAJOR_VERSION)\.[[:digit:]]*\.[[:digit:]]*' >/dev/null

.PHONY: deps-oci
deps-oci:
	command -v docker

.PHONY: jar
jar: deps-jar
	mvn $(BUILD_OPTS) --batch-mode --update-snapshots verify

.PHONY: coverage
coverage: deps-jar
	mvn $(BUILD_OPTS) --batch-mode test jacoco:report
	@echo "Coverage report: target/site/jacoco/index.html"

.PHONY: e2e
e2e: deps-jar deps-oci
	mvn $(BUILD_OPTS) --batch-mode --update-snapshots verify -Pe2e
	@echo "Coverage report (incl. e2e): target/site/jacoco/index.html"

# Coverage-guided fuzzing of the flow parsers. JAZZER_FUZZ=1 flips jazzer-junit from regression
# mode (the seed corpus replays as ordinary tests in `make jar`) into fuzzing mode. FUZZ_TIME is
# the per-target budget in seconds; FUZZ_TARGET narrows to one harness (the nightly matrix passes
# it per job); the corpus persists in .cifuzz-corpus so coverage compounds.
# test-compile is part of the invocation because deps-jar only checks tool versions: on a fresh
# checkout (i.e. CI) surefire:test alone has no target/test-classes to select a harness from.
FUZZ_TIME   ?= 120
FUZZ_TARGET ?= *FuzzTest
.PHONY: fuzz
fuzz: deps-jar
	JAZZER_FUZZ=1 mvn $(BUILD_OPTS) --batch-mode test-compile surefire:test \
		-Dtest='org.riptide.flows.fuzz.$(FUZZ_TARGET)' \
		-Djazzer.max_duration=$(FUZZ_TIME)s

# FR-1 budget benchmarks (src/bench/java, standalone main()s kept outside the Maven source roots;
# see src/bench/README.md). Not part of `make jar`: opt-in, never a build gate. Absolute numbers are
# informational; only same-run ratios are asserted, so results stay meaningful on any machine. A
# failed assertion exits nonzero naming the measured and required values. BENCH_FULL=1 currently
# changes nothing: its only sweeps measured the legacy binder shape, retired in 0.9. The flag
# plumbing stays for the next harness that needs a slow mode.
# --release pins the bench compile to the same language level Maven targets, so a PATH javac older
# than the project JDK fails loudly instead of emitting a wrong-class-file-version error later.
.PHONY: bench
bench: deps-jar
	mvn $(BUILD_OPTS) --batch-mode compile \
		dependency:build-classpath -Dmdep.outputFile=target/bench-cp.txt
	mkdir -p target/bench-classes
	javac --release $(JAVA_MAJOR_VERSION) -encoding UTF-8 \
		-cp "target/classes:$$(cat target/bench-cp.txt)" -d target/bench-classes src/bench/java/*.java
	java -Xmx4g -cp "target/classes:target/bench-classes:$$(cat target/bench-cp.txt)" \
		BenchSuite $(if $(filter-out 0,$(BENCH_FULL)),--full)
	@echo "Budget report: target/bench-report.json"

# JMH microbenchmarks (src/test/java/org/riptide/benchmarks). Not part of `make jar`: a benchmark
# run takes minutes and its numbers are meaningless on a loaded machine, so it is opt-in and never
# a build gate. BENCH_TARGET is a JMH regex over benchmark class names.
#
# BENCH_OPTS overrides the per-class @Fork/@Warmup/@Measurement annotations, which are all set to
# JMH-light values (1 fork, 2 warmup, 5 measurement) for a quick single run. Two forks and ten
# iterations is the setting worth quoting a number from: these decode benchmarks sit around 20us/op,
# and a single noisy fork has been observed moving the mean by 2x on a loaded machine. This is still
# lighter on forks than JMH's own default of 5. Check `uptime` before trusting any result.
#
# dependency:build-classpath is what lets JMH run from the test classpath without a shade/uber jar.
BENCH_TARGET ?= .*Benchmark
BENCH_OPTS   ?= -wi 3 -i 10 -f 2
.PHONY: bench-jmh
bench-jmh: deps-jar
	mvn $(BUILD_OPTS) --batch-mode test-compile \
		dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
	java -cp "target/classes:target/test-classes:$$(cat target/test-cp.txt)" \
		org.riptide.benchmarks.Benchmarks '$(BENCH_TARGET)' $(BENCH_OPTS) \
		-rf json -rff target/jmh-result.json
	@echo "JMH result: target/jmh-result.json"

.PHONY: deps-lint-actions
deps-lint-actions:
	command -v actionlint
	command -v zizmor

.PHONY: lint-actions
lint-actions: deps-lint-actions
	actionlint
	zizmor --persona=regular .github/workflows

.PHONY: deps-contributors
deps-contributors:
	command -v npx

# The badge count and the credit table are literals in README.md, rewritten only
# when the generator runs. .all-contributorsrc is the source of truth; edit it,
# then run this.
.PHONY: contributors
contributors: deps-contributors
	npx -y all-contributors-cli@$(ALL_CONTRIBUTORS_VERSION) generate

# Fails when the committed README.md no longer matches .all-contributorsrc.
# Drift is silent otherwise — the badge read 1 while the rc file listed two
# contributors, and nothing caught it.
.PHONY: contributors-check
contributors-check: contributors
	@git diff --exit-code -- README.md \
		|| { echo "$(FAIL) README.md is out of sync with .all-contributorsrc — run 'make contributors' and commit the result"; exit 1; }
	@echo "$(OK) README.md matches .all-contributorsrc"

.PHONY: deps-docs
deps-docs:
	command -v npm

.PHONY: docs
docs: deps-docs
	cd docs && npm ci && npm run build

.PHONY: docs-serve
docs-serve: deps-docs
	cd docs && npm ci && npm run start

# Mirrors the %%VERSION%% substitution docs.yml performs, so local preview shows
# what the deployed site shows instead of a raw token. Two deliberate differences
# from CI: sed writes through a redirect rather than -i (GNU and BSD sed disagree
# on -i, and this target only ever runs on a developer machine), and a missing
# stable tag falls back to "dev" instead of failing the way a deploy must.
.PHONY: landing-serve
landing-serve:
	@rm -rf build/landing
	@mkdir -p build/landing
	@cp -R landing/. build/landing/
	@VERSION=$$(git tag --list 'v*' --sort=-version:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$$' | head -n1 | sed 's/^v//'); \
		VERSION=$${VERSION:-dev}; \
		sed "s/%%VERSION%%/$${VERSION}/g" landing/index.html > build/landing/index.html; \
		echo "Serving landing page (version $${VERSION}) on http://localhost:8080"
	@python3 -m http.server 8080 --directory build/landing

.PHONY: oci
oci: deps-oci jar
	docker build -t $(OCI_TAG) \
      --build-arg="VERSION=$(VERSION)" \
      --build-arg="GIT_SHORT_HASH"=$(GIT_SHORT_HASH) \
      --build-arg="DATE=$(DATE)" \
      .

.PHONY: packages
packages: deps-oci
	@test -f "target/riptide-flows-$(VERSION).jar" || { echo "target/riptide-flows-$(VERSION).jar missing — run make jar first"; exit 1; }
	mkdir -p target/package
	cp "target/riptide-flows-$(VERSION).jar" target/package/riptide.jar
	VERSION=$(PKG_VERSION) $(NFPM) package -f nfpm.yaml -p deb -t target/
	VERSION=$(PKG_VERSION) $(NFPM) package -f nfpm.yaml -p rpm -t target/

.PHONY: packages-smoke
packages-smoke: deps-oci
	deployment/package/smoke-test.sh "$(PKG_VERSION)"

# The only gate on the compose stack: every *IT class builds its own bare
# GenericContainer, so nothing else mounts users.xml or config.xml and the
# properties #670 established by hand would regress green (#672).
.PHONY: compose-smoke
compose-smoke: deps-oci
	deployment/riptide/smoke-test.sh

# Sets licenseDeclared on the SBOM entries syft cannot fill for us (the deb and
# the document root, issue #406) and licenseConcluded on the reviewed allowlist
# of third-party packages syft cannot identify (issue #405). Runs in release.yml
# between SBOM generation and the HTML report render; fails if the SBOM shape
# drifted or an allowlist entry went stale.
.PHONY: sbom-assert
sbom-assert:
	@test -n "$(SBOM)" || { echo "usage: make sbom-assert SBOM=target/riptide-<version>.spdx.json"; exit 1; }
	python3 deployment/sbom/assert_licenses.py "$(SBOM)"

.PHONY: sbom-assert-test
sbom-assert-test:
	python3 -m unittest discover -s deployment/sbom

.PHONY: deps-nix
deps-nix:
	@command -v nix >/dev/null || { echo "Please install Nix — https://nixos.org/download"; exit 1; }

.PHONY: nix
nix: deps-nix
	nix build .#default --print-build-logs

.PHONY: nix-check
nix-check: deps-nix
	nix flake check --print-build-logs

# Regenerate nix/package.nix's mvnHash after a pom change. Forces the fixed-output maven-deps
# derivation to the fake-hash sentinel so the build reports the real hash, then writes it back.
# Idempotent: run it whether or not the hash is stale. sed -i.bak works on both GNU and BSD sed.
NIX_FAKE_HASH := sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
.PHONY: nix-hash
nix-hash: deps-nix
	@echo "Forcing the sentinel hash to read back the real one..."
	@sed -i.bak -E 's|mvnHash = "sha256-[^"]*";|mvnHash = "$(NIX_FAKE_HASH)";|' nix/package.nix
	@got=$$(nix build .#default --no-link 2>&1 | grep -oE 'sha256-[A-Za-z0-9+/=]{44}' | grep -v '$(NIX_FAKE_HASH)' | head -1); \
	if [ -z "$$got" ]; then \
		echo "No hash reported — the build did not fail on a mismatch. Restoring."; \
		mv nix/package.nix.bak nix/package.nix; exit 1; \
	fi; \
	sed -i.bak2 -E "s|mvnHash = \"sha256-[^\"]*\";|mvnHash = \"$$got\";|" nix/package.nix; \
	rm -f nix/package.nix.bak nix/package.nix.bak2; \
	echo "mvnHash = $$got"

.PHONY: release
release:
	@mkdir -p target
	@echo ""
	@echo "Release Riptide version:  $(RELEASE_VERSION)"
	@echo "New snapshot version:     $(SNAPSHOT_VERSION)"
	@echo "Git version tag:          v$(RELEASE_VERSION)"
	@echo "Release log:              $(RELEASE_LOG)"
	@echo ""
	@echo -n "Check release branch:        "
	@if [ "$(GIT_BRANCH)" != "release" ]; then echo "Releases are made from the release branch, your branch is $(GIT_BRANCH)."; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check upstream configured    "
	@if ! git rev-parse --abbrev-ref @{u} >/dev/null 2>&1; then echo "No upstream for the release branch — run: git push -u origin release"; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check release branch in sync "
	@if [ "$$(git rev-parse HEAD)" != "$$(git rev-parse @{u})" ]; then echo "Release branch not in sync with its upstream."; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check uncommited changes     "
	@if git status --porcelain | grep -q .; then echo "There are uncommited changes in your repository."; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check release version:       "
	@if [ "$(RELEASE_VERSION)" = "UNSET.0.0" ]; then echo "Set a release version, e.g. make release RELEASE_VERSION=1.0.0"; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check version tag available: "
	@if git rev-parse v$(RELEASE_VERSION) >$(RELEASE_LOG) 2>&1; then echo "Tag v$(RELEASE_VERSION) already exists"; exit 1; fi
	@echo "$(OK)"
	@echo -n "Set Maven release version:   "
	@mvn versions:set -DnewVersion=$(RELEASE_VERSION) >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "Verify build with tests:     "
	@$(MAKE) jar >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "Git commit new release:      "
	@git commit --signoff -am "release: Riptide version $(RELEASE_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "Set Git version tag:         "
	@git tag -a "v$(RELEASE_VERSION)" -m "Release Riptide version $(RELEASE_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "Set Maven snapshot version:  "
	@mvn versions:set -DnewVersion=$(SNAPSHOT_VERSION) >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "Git commit snapshot release: "
	@git commit --signoff -am "release: Set new snapshot version $(SNAPSHOT_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@if [ "$(PUSH_RELEASE)" = "true" ]; then \
		echo -n "Push commits                 "; \
  		{ git push origin HEAD >>$(RELEASE_LOG) 2>&1 && echo "$(OK)"; } || { echo "$(FAIL)"; exit 1; }; \
		echo -n "Push tag                     "; \
  		{ git push origin v$(RELEASE_VERSION) >>$(RELEASE_LOG) 2>&1 && echo "$(OK)"; } || { echo "$(FAIL)"; exit 1; }; \
  	else \
  		echo "Push commits and tags:       $(SKIP)"; \
  	fi;

.PHONY: clean
clean: deps-jar
	mvn clean
