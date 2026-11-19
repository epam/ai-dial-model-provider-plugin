# `make lint` and `make test` are the contract called by epam/ai-dial-ci.
# Everything else is developer convenience. The build needs a JDK 25 as JAVA_HOME (Gradle 9.9).
# The plugin module compiles against DIAL Core's plugin API from GitHub Packages: GPR_USERNAME and GPR_PASSWORD
# (a token with the read:packages scope) must be set for `lint`, `test`, `build-plugin-jar` (README "Building the plugin jar").

PORT ?= 5000
IMAGE_NAME ?= ai-dial-model-provider
PLATFORM ?= linux/amd64
ARGS ?=
PACKAGE_VERSION ?=

.PHONY: all lint format install_git_hooks test integration-tests integration-tests-smoke compile-http-tests build build-plugin-jar bump-ai-dial-plugin-api check-gpr-creds serve docker help

all: lint test

# compileJava and compileTestJava of every module are listed, so the plugin module is checked to compile against DIAL Core's
# plugin API.
lint: check-gpr-creds
	./gradlew compileJava compileTestJava checkstyleMain checkstyleTest pmdMain pmdTest spotlessCheck

format:
	./gradlew spotlessApply

install_git_hooks:
	git config core.hooksPath .githooks

test: check-gpr-creds
	./gradlew test $(ARGS)

# Live tests against AWS, GCP and Azure: they need cloud logins and settings (integration-tests/README.md), so they are
# not part of `test`. The smoke report first: it says which login or model is the problem.
integration-tests-smoke:
	./gradlew :integration-tests:smoke --console=plain -q

integration-tests:
	./gradlew :integration-tests:integrationTest --console=plain $(ARGS)

compile-http-tests:
	./gradlew :provider:compileHttpTestCases $(ARGS)

build:
	./gradlew :app:installDist

# GPR_USERNAME and GPR_PASSWORD: set, accepted by GitHub, with the read:packages scope (scripts/check_gpr_creds.py).
check-gpr-creds:
	@python3 scripts/check_gpr_creds.py

# DIAL Core's plugin: one fat jar, plugin/build/plugins/aidial-model-provider-plugin-<version>.jar, with the plugin's own
# dependencies and nothing DIAL Core provides (Vert.x, Netty, Jackson, SLF4J). Copy it into a plugin directory of
# DIAL Core of its own: <plugins.dir>/<group>/<plugin>, e.g. /app/plugins/custom/model-provider in its image.
build-plugin-jar: check-gpr-creds
	rm -rf plugin/build/plugins
	./gradlew :plugin:pluginJar
	@echo 'DIAL Core plugin jar:' plugin/build/plugins/*.jar

# make bump-ai-dial-plugin-api PACKAGE_VERSION=0.51.0: pins plugin_api_version in gradle.properties, then runs
# build-plugin-jar; a version that fails to build is rolled back (scripts/bump_plugin_api.py).
bump-ai-dial-plugin-api: check-gpr-creds
	@python3 scripts/bump_plugin_api.py "$(PACKAGE_VERSION)"

serve: build
	PORT=$(PORT) ./app/build/install/model-provider/bin/model-provider

docker:
	docker build --platform $(PLATFORM) -t $(IMAGE_NAME):dev .
	docker run --platform $(PLATFORM) --env-file ./.env --rm -p $(PORT):5000 $(IMAGE_NAME):dev

help:
	@echo 'lint    - compilation of every module (the plugin against the DIAL Core plugin API), Checkstyle, PMD and the formatting check'
	@echo 'format  - format the Java sources (palantir-java-format)'
	@echo 'install_git_hooks - check the formatting before each git push (.githooks/pre-push)'
	@echo 'test    - unit tests and the declarative HTTP cases of http-tests/ (needs python3 with pip; ARGS=... passed to gradle)'
	@echo 'integration-tests-smoke - availability, authorization and permissions report: one question to every model on AWS, GCP and Azure (integration-tests/README.md)'
	@echo 'integration-tests - live tests: a simple question and a tool call, to every model, through both APIs (ARGS=... passed to gradle)'
	@echo 'compile-http-tests - compile http-tests/cases into provider/build/generated/http-tests (needs python3 with pip)'
	@echo 'build   - the distribution in app/build/install/model-provider'
	@echo 'build-plugin-jar - the DIAL Core plugin as one fat jar in plugin/build/plugins (needs GPR_USERNAME and GPR_PASSWORD)'
	@echo 'check-gpr-creds - check GPR_USERNAME and GPR_PASSWORD (the read:packages scope) that the plugin module needs'
	@echo 'bump-ai-dial-plugin-api - pin the DIAL Core plugin API to PACKAGE_VERSION=<version> and run build-plugin-jar'
	@echo 'serve   - run the service locally on PORT'
	@echo 'docker  - build and run the image'
