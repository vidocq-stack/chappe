# syntax=docker/dockerfile:1.7
#
# Image dédiée aux benchmarks Chappe, exécutée sur Docker distant via
# `docker --context macuntu` pour s'isoler du bruit de la machine dev.
#
# Stage 1 : build Maven + JDK 25
# Stage 2 : run JDK 25 minimal avec classes + dépendances copiées

ARG MAVEN_TAG=4.0.0-rc-5-eclipse-temurin-25
ARG JDK_TAG=25-jdk-noble

FROM maven:${MAVEN_TAG} AS build
WORKDIR /chappe

# Copy uniquement les POMs d'abord pour mieux cacher la résolution de deps.
COPY pom.xml ./
COPY chappe-api/pom.xml                         chappe-api/pom.xml
COPY chappe-http/pom.xml                        chappe-http/pom.xml
COPY chappe-core/pom.xml                        chappe-core/pom.xml
COPY chappe-tests/pom.xml                       chappe-tests/pom.xml
COPY chappe-bench/pom.xml                       chappe-bench/pom.xml
COPY chappe-conformance/pom.xml                 chappe-conformance/pom.xml
COPY chappe-examples/pom.xml                    chappe-examples/pom.xml
COPY chappe-cli/pom.xml                         chappe-cli/pom.xml
COPY chappe-static-index-maven-plugin/pom.xml   chappe-static-index-maven-plugin/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl chappe-bench -am -DskipTests dependency:go-offline -B || true

# Sources
COPY chappe-api/src                        chappe-api/src
COPY chappe-http/src                       chappe-http/src
COPY chappe-core/src                       chappe-core/src
COPY chappe-bench/src                      chappe-bench/src
COPY chappe-static-index-maven-plugin/src  chappe-static-index-maven-plugin/src

# Build + copie des dépendances à côté du jar
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl chappe-bench -am -DskipTests package \
    && mvn -pl chappe-bench -DskipTests -DincludeScope=runtime \
         dependency:copy-dependencies \
         -DoutputDirectory=/chappe/chappe-bench/target/dependency


FROM eclipse-temurin:${JDK_TAG} AS run
WORKDIR /chappe

COPY --from=build /chappe/chappe-bench/target/classes     /chappe/classes
COPY --from=build /chappe/chappe-bench/target/dependency  /chappe/deps

# Entrypoint par défaut : micro-benchs JMH (OptimizationsRunner).
# Override via `docker run ... <class>` pour lancer p. ex. ServerComparison.
ENV CHAPPE_MAIN=fr.vidocq.chappe.bench.OptimizationsRunner

ENTRYPOINT ["sh", "-c", "java --enable-preview -cp \"/chappe/classes:/chappe/deps/*\" ${1:-$CHAPPE_MAIN}", "--"]
