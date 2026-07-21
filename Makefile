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
	@echo "  nix:          Build the flake package from source (requires Nix)"
	@echo "  nix-check:    Run the flake checks incl. the NixOS module eval (requires Nix)"
	@echo "  coverage:     Run the unit test suite and render the JaCoCo coverage report"
	@echo "  e2e:          Run integration and e2e tests (*IT, requires Docker) in addition to the unit suite"
	@echo "  fuzz:         Coverage-guided fuzzing of the flow parsers (Jazzer); FUZZ_TIME=<seconds> per target"
	@echo "  lint-actions: Lint the GitHub Actions workflows (actionlint + zizmor)"
	@echo "  docs:         Build the Docusaurus documentation site into docs/build"
	@echo "  docs-serve:   Run the documentation site locally with live reload"
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
FUZZ_TIME   ?= 120
FUZZ_TARGET ?= *FuzzTest
.PHONY: fuzz
fuzz: deps-jar
	JAZZER_FUZZ=1 mvn $(BUILD_OPTS) --batch-mode surefire:test \
		-Dtest='org.riptide.flows.fuzz.$(FUZZ_TARGET)' -DfailIfNoSpecifiedTests=false \
		-Djazzer.max_total_time=$(FUZZ_TIME)

.PHONY: deps-lint-actions
deps-lint-actions:
	command -v actionlint
	command -v zizmor

.PHONY: lint-actions
lint-actions: deps-lint-actions
	actionlint
	zizmor --persona=regular .github/workflows

.PHONY: deps-docs
deps-docs:
	command -v npm

.PHONY: docs
docs: deps-docs
	cd docs && npm ci && npm run build

.PHONY: docs-serve
docs-serve: deps-docs
	cd docs && npm ci && npm run start

.PHONY: oci
oci: deps-oci jar
	docker build -t $(OCI_TAG) \
      --build-arg="VERSION=$(VERSION)" \
      --build-arg="GIT_SHORT_HASH"=$(GIT_SHORT_HASH) \
      --build-arg="DATE=$(DATE)" \
      .

.PHONY: packages
packages: deps-oci
	@test -f target/riptide-flows-$(VERSION).jar || { echo "target/riptide-flows-$(VERSION).jar missing — run make jar first"; exit 1; }
	mkdir -p target/package
	cp target/riptide-flows-$(VERSION).jar target/package/riptide.jar
	VERSION=$(PKG_VERSION) $(NFPM) package -f nfpm.yaml -p deb -t target/
	VERSION=$(PKG_VERSION) $(NFPM) package -f nfpm.yaml -p rpm -t target/

.PHONY: packages-smoke
packages-smoke: deps-oci
	deployment/package/smoke-test.sh $(PKG_VERSION)

.PHONY: deps-nix
deps-nix:
	@command -v nix >/dev/null || { echo "Please install Nix — https://nixos.org/download"; exit 1; }

.PHONY: nix
nix: deps-nix
	nix build .#default --print-build-logs

.PHONY: nix-check
nix-check: deps-nix
	nix flake check --print-build-logs

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
	@echo -n "Check release branch in sync "
	@if [ "$$(git rev-parse HEAD)" != "$$(git rev-parse @{u})" ]; then echo "Release branch not in sync with remote origin."; exit 1; fi
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
