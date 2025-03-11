.DEFAULT_GOAL := jar

SHELL               := /bin/bash -o nounset -o pipefail -o errexit
VERSION             ?= $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
GIT_BRANCH          := $(shell git branch --show-current)
GIT_SHORT_HASH      := $(shell git rev-parse --short HEAD)
RELEASE_VERSION     := UNSET
MAJOR_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f1)
MINOR_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f2)
PATCH_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f3)
SNAPSHOT_VERSION    := $(MAJOR_VERSION).$(MINOR_VERSION).$(shell expr $(PATCH_VERSION) + 1)-SNAPSHOT
OCI_TAG             := riptide:local
DATE                := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ") # Date format RFC3339
JAVA_MAJOR_VERSION  := 23
RELEASE_LOG         := target/release.log
OK                  := "[ 👍 ]"
SKIP                := "[ ⏭️ ]"

$(subst $e ,_,$(ITEM))

.PHONY help:
help:
	@echo ""
	@echo "Build Riptide from source"
	@echo "Goals:"
	@echo "  help:         Show this help with explaining the build goals"
	@echo "  jar:          Compile Riptide from source with tests and generate a runnable jar file in the target directory"
	@echo "  oci:          Build OCI container image"
	@echo "  clean:        Clean the build artifacts"
	@echo ""

.PHONY deps-jar:
deps-jar:
	@command -v java
	@command -v javac
	@command -v mvn
	@echo Your Maven version
	@mvn --version
	@echo Your Java version
	@java --version
	@echo "Test Java $(JAVA_MAJOR_VERSION) requirement"
	@java --version | grep '$(JAVA_MAJOR_VERSION)\.[[:digit:]]*\.[[:digit:]]*' >/dev/null

.PHONY deps-oci:
deps-oci:
	command -v docker

.PHONY jar:
jar: deps-jar
	mvn --batch-mode --update-snapshots verify

.PHONY oci: jar
oci: deps-oci
	docker build -t $(OCI_TAG) \
      --build-arg="VERSION=$(VERSION)" \
      --build-arg="GIT_SHORT_HASH"=$(GIT_SHORT_HASH) \
      --build-arg="DATE=$(DATE)" \
      --build-arg="REGISTRY_REPOSITORY=local-build" \
      .

.PHONY release:
release:
	@mkdir -p target
	@echo ""
	@echo "Release Riptide version:  $(RELEASE_VERSION)"
	@echo "New snapshot version:     $(SNAPSHOT_VERSION)"
	@echo "Git version tag:          v$(RELEASE_VERSION)"
	@echo "Release log:              $(RELEASE_LOG)"
	@echo ""
	@echo -n "Check main branch:           "
	@if [ "$(GIT_BRANCH)" != "main" ]; then echo "Releases are made from the main branch, your branch is $(GIT_BRANCH)."; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check main branch in sync    "
	@if [ "$(git rev-parse HEAD)" != "$(git rev-parse @{u})" ]; then echo "Main branch not in sync with remote origin."; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check uncommited changes     "
	@if git status --porcelain | grep -q .; then echo "There are uncommited changes in your repository."; exit 1; fi
	@echo "$(OK)"
	@echo -n "Check release version:       "
	@if [ "$(RELEASE_VERSION)" = "UNSET" ]; then echo "Set a release version, e.g. make release RELEASE_VERSION=1.0.0"; exit 1; fi
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
	@if [ "PUSH_RELEASE" == "true" ]; then \
		echo -n "Push commits                     "; \
  		git push >>$(RELEASE_LOG) 2>&1; \
		echo "$(OK)"; \
		echo -n "Push tags                        "; \
  		git push --tags >>$(RELEASE_LOG) 2>&1; \
  		echo "$(OK)"; \
  	else \
  		echo "Push commits and tags:       $(SKIP)"; \
  	fi

.PHONY clean:
clean: deps-jar
	mvn clean
