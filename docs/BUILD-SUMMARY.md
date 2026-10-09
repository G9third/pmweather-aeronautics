# PMWeather Aeronautics 1.0 build summary

- Build: `./gradlew jar`
- Toolchain: Java 21
- Result: successful; Gradle completed `compileJava`, `processResources`, `classes`, and `jar`
- Mod JAR SHA-256: `131ec35d2560aeb0f981d065efffcc513b985330f892efa8ceda7b8b37021bbd`
- API bytecode inspection: all three existing packed APIs report API version 2 and their
  unchanged stride constants; the optional `WheelContactWindApi` reports API version 1.
- Packaging inspection: the mod JAR contains its source resources and compiled classes;
  compile-only dependency JARs are not embedded.
- Not run: executable API-check task, Java test suite, game launch, or in-game physics
  verification.

The public source ZIP includes the pre-existing `src/test/.../ExternalApiChecks.java`
because Gradle's `check` task references it. It was not added or executed for this release;
running `./gradlew build` invokes that dependency-free API check.

The successful compile verifies source compilation and JAR packaging only. It does not
verify that optional mixins apply in a running game or establish physics behavior.
