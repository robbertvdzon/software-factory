# syntax=docker/dockerfile:1.7

# Eén deployable: de volledige Software Factory (tracker, pipeline, orchestrator, audits, Telegram,
# dashboard-API en Product Factory-integratie). Bouwt de hele Maven-reactor voor de module
# `softwarefactory` en levert een runtime-image met de CLI's die de factory aanroept:
# `gh` (GitHub), `kubectl` (ArgoCD-deploystatus) en `oc` (preview-namespaces opruimen).

FROM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY factory-common/pom.xml factory-common/pom.xml
COPY softwarefactory/pom.xml softwarefactory/pom.xml
RUN mvn -q -pl softwarefactory -am dependency:go-offline
COPY factory-common/src factory-common/src
COPY softwarefactory/src softwarefactory/src
RUN mvn -q -DskipTests -pl softwarefactory -am package

FROM eclipse-temurin:21-jre-jammy
ARG OC_VERSION=stable-4.19
ARG SF_BUILD_COMMIT=dev
ARG SF_BUILD_BRANCH=dev
ARG SF_BUILD_COMMIT_SUBJECT=
ARG SF_BUILD_COMMIT_DATE=
# gh via de officiële apt-repo; oc en kubectl uit één OpenShift-client-tarball zodat ze bij elkaar passen.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg tzdata; \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /usr/share/keyrings/githubcli-archive-keyring.gpg; \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends gh; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in amd64) oc_arch="" ;; arm64) oc_arch="-arm64" ;; *) echo "onbekende architectuur $arch" >&2; exit 1 ;; esac; \
    curl -fsSL "https://mirror.openshift.com/pub/openshift-v4/clients/ocp/${OC_VERSION}/openshift-client-linux${oc_arch}.tar.gz" \
      | tar -xz -C /usr/local/bin oc kubectl; \
    gh --version; oc version --client; kubectl version --client; \
    apt-get purge -y gnupg; apt-get autoremove -y; rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Versie-info voor het dashboard (er is geen .git in het image), zie FactoryVersionService.
ENV SF_BUILD_COMMIT=${SF_BUILD_COMMIT} \
    SF_BUILD_BRANCH=${SF_BUILD_BRANCH} \
    SF_BUILD_COMMIT_SUBJECT=${SF_BUILD_COMMIT_SUBJECT} \
    SF_BUILD_COMMIT_DATE=${SF_BUILD_COMMIT_DATE}
COPY --from=build /build/softwarefactory/target/softwarefactory-app-0.0.1-SNAPSHOT.jar /app/app.jar
# Gecommitte tuning-defaults; SecretsEnvLoader leest ze naast het (ontbrekende) /app/secrets.env.
COPY properties.default.env /app/properties.default.env
# Schrijfbare mappen voor logs en (zonder PVC) bijlagen, ook onder een willekeurige OpenShift-uid.
RUN mkdir -p /app/logs /app/attachments && chgrp -R 0 /app && chmod -R g+rwX /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
