# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Java 21 service using Quarkus REST and CDI. Develop new features using hexagonal architecture (ports and adapters), with independent responsibilities and explicit boundaries.

- `src/main/java/com/timereport/`: application code; currently contains the `/hello` example.
- `src/main/resources/application.properties`: runtime configuration.
- `src/test/java/com/timereport/`: tests.
- `src/main/docker/`: Dockerfiles for JVM and native deployments.
- `pom.xml`: dependencies and build configuration.
- `target/`: generated output.

## Architecture & Dependency Rules

Organize new code by business capability, for example `com.timereport.timeentry`, with these packages as needed:

- `domain`: entities, value objects, and business rules; plain Java without Quarkus, CDI, HTTP, or persistence dependencies.
- `application.port.in`: use-case interfaces, such as `RegisterTimeEntryUseCase`.
- `application.port.out`: interfaces for external capabilities, such as `TimeEntryRepository`.
- `application.service`: use-case implementations coordinating domain logic through ports.
- `adapter.in.rest`: REST resources and transport DTOs; invoke input ports.
- `adapter.out`: persistence, messaging, and external API implementations of output ports.
- `configuration`: Quarkus/CDI wiring; construct application services through producer methods.

Dependencies point inward: adapters depend on application contracts; application depends on domain. Keep application code framework-independent. Map transport DTOs and persistence entities at adapter boundaries; never expose them through ports. Keep business rules in the domain. Introduce ports only for use cases and external dependencies.

Each microservice owns its data and business capability. Communicate across services through explicit API or event contracts; avoid shared databases, cross-service table access, and shared domain implementations. Keep this repository one deployable service unless a split is explicitly requested.

## Build, Test, and Development Commands

Use JDK 21 and the checked-in Maven wrapper (`mvnw.cmd` on Windows).

- `./mvnw quarkus:dev`: live-reload development server.
- `./mvnw test`: run tests through Surefire.
- `./mvnw package`: test and package the JVM application.
- `java -jar target/quarkus-app/quarkus-run.jar`: run the packaged application.
- `./mvnw verify -DskipITs=false`: include packaged integration tests; skipped by default.

## Coding Style & Naming Conventions

Use four-space indentation, same-line Java braces, PascalCase classes, and camelCase methods/fields. Name REST classes `*Resource` and declare response media types. No formatter or lint plugin is configured.

## Immutable Models & Builders

Use immutable Java `record` types for REST request/response objects and domain models. Name REST DTOs `*Request` and `*Response`; keep them separate from domain records and map them in adapters.

Use Lombok `@Builder` on domain and REST records to generate builders; do not write manual builders or use mutable DTOs, setters, or `@Data`. For example: `@Builder public record CreateTimeEntryRequest(String description) {}`. Lombok is a compile-time dependency allowed in the domain; configure its dependency and annotation processing in `pom.xml` when introducing these records.

Records are only shallowly immutable: use immutable component types and defensively copy collections with `List.copyOf`, `Set.copyOf`, or `Map.copyOf` in compact constructors. Avoid exposing mutable nested objects or arrays. Enforce domain invariants in record constructors so both direct construction and builders validate them. Represent domain changes by returning new records.

## Testing Guidelines

Use plain JUnit Jupiter for domain and use-case tests, replacing output ports with fakes or mocks. Use `@QuarkusTest` and REST Assured for adapter/HTTP tests. Name tests `*Test`; packaged tests use `*IT` and `@QuarkusIntegrationTest`. Mirror production packages. Verify business rules, port interactions, boundary mappings, and HTTP responses. No coverage threshold is configured.

## Commit & Pull Request Guidelines

Git history is unavailable; use imperative subjects, such as `Add time entry endpoint`. Keep changes focused. PRs should describe behavior and boundary changes, list validation results, link relevant issues, and include examples for API changes.
