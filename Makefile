.DEFAULT_GOAL := build

SHELL               := /bin/bash -o nounset -o pipefail -o errexit
VERSION             ?= $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

.PHONY help:
help:
	@echo ""
	@echo "Build Riptide from source"
	@echo "Goals:"
	@echo "  help:         Show this help with explaining the build goals"
	@echo "  build:        Compile Riptide from source"
	@echo "  oci:          Build OCI container image"
	@echo "  clean:        Clean the build artifacts"
	@echo ""

.PHONY deps-build:
deps-build:
	command -v java
	command -v javac
	command -v mvn

.PHONY deps-oci:
deps-oci:
	command -v docker

.PHONY build:
build: deps-build
	mvn --batch-mode --update-snapshots verify

.PHONY oci:
	mvn --batch-mode --update-snapshots jib:dockerBuild

.PHONY clean:
clean: deps-build
	mvn clean