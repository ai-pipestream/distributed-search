# Quarkus removal plan (parked until the project lands)

Decision (2026-07-22): remove Quarkus from knn-node and make the engine a
plain-Java standalone build, AFTER the current project lands. No Spring
exists anywhere in the repo. The embeddings composite is already plain Java.

## Inventory (what Quarkus provides today)

- ~18 beans (13 `@ApplicationScoped`, 5 `@Singleton`), 31 `@Inject` points
- 17 `@ConfigProperty` reads over `application.properties` + env overrides
- 5 `@GrpcService` registrations; generated Mutiny stubs (quarkusGenerateCode)
- Mutiny types at the gRPC edges of 9 files; protocol logic underneath is
  imperative and framework-free
- 1 `@Scheduled` task, 5 startup/shutdown `@Observes`
- REST: `KnnResource` (benchmark/search HTTP API, used by manage_cluster.py),
  `DjlService` + 2 `@RestClient` interfaces
- Stork service discovery (custom ScaleCube provider) for gRPC clients
- smallrye-health, micrometer, unified HTTP+gRPC port (48100),
  gRPC reflection + health enabled in prod
- 8 test classes use `@QuarkusTest` (container-injected wiring)

## Replacement map

| Today | After |
| --- | --- |
| CDI graph | one composition root class, explicit construction order |
| Mutiny gRPC stubs | protobuf-gradle-plugin + grpc-java (`*ImplBase`, `StreamObserver`); the frame protocols read more naturally imperative |
| `@ConfigProperty` | small properties+env config wrapper |
| `@Scheduled` / `@Observes` | `ScheduledExecutorService` / `main()` lifecycle |
| REST resources | `com.sun.net.httpserver` + Jackson (keep the benchmark API) |
| `@RestClient` | `java.net.http.HttpClient` |
| Stork | direct `ManagedChannel`s resolved from ScaleCube membership (ShardRouter already owns routing) |
| smallrye-health | standard gRPC health service (`grpc-services`), plus reflection service |
| Quarkus BOM versions | explicit versions in `gradle/libs.versions.toml` |
| `@QuarkusTest` | plain JUnit with composition-root wiring (8 files) |

## Sequencing

1. Build swap: drop the quarkus plugin/BOM; add protobuf-gradle-plugin,
   grpc-netty, grpc-protobuf, grpc-stub, grpc-services,
   proto-google-common-protos; pin every version formerly supplied by the BOM.
2. Convert the 5 services Mutiny -> StreamObserver (edges only).
3. Composition root + config + lifecycle + scheduler; gRPC server with
   health + reflection on 48100.
4. REST + HTTP clients; drop Stork.
5. Convert the 8 `@QuarkusTest` classes; full suite green (153+ tests as of
   this writing).

## What we gain / lose

Gain: no annotation processing or reflective startup, no enforced-platform
version fights (e.g. the protobuf 4.35.0-vs-gencode-4.35.1 force in
build.gradle becomes unnecessary), smaller CVE surface, plain `main()` +
Netty + ScaleCube + Lucene that drops onto protomolt's server-netty
scaffolding if the engine is ever folded in.

Lose: dev-mode hot reload. Native image was never viable (Lucene needs the
incubator vector API on a real JVM).
