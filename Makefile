.DEFAULT_GOAL := jar

SHELL               := /bin/bash -o nounset -o pipefail -o errexit
VERSION             ?= $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
GIT_BRANCH          := $(shell git branch --show-current)
GIT_SHORT_HASH      := $(shell git rev-parse --short HEAD)
OCI_TAG             := riptide:local
DATE                := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ") # Date format RFC3339
JAVA_MAJOR_VERSION  := 23

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

.PHONY deps-build:
deps-jar:
	@command -v java
	@command -v javac
	@command -v mvn
	@echo Your Maven version
	@mvn --version
	@echo Your Java version
	@java --version
	@echo "Test Java $(JAVA_MAJOR_VERSION) requirement"
	@java --version | grep -E '$(JAVA_MAJOR_VERSION)\.(\d+)\.(\d+)' >/dev/null

.PHONY deps-oci:
deps-oci:
	command -v docker

.PHONY jar:
jar: deps-build
	mvn --batch-mode --update-snapshots verify

.PHONY oci: jar
oci: deps-oci
	docker build -t $(OCI_TAG) \
      --build-arg="VERSION=$(VERSION)" \
      --build-arg="GIT_SHORT_HASH"=$(GIT_SHORT_HASH) \
      --build-arg="DATE=$(DATE)" \
      --build-arg="REGISTRY_REPOSITORY=local-build" \
      .

.PHONY clean:
clean: deps-build
	mvn clean
