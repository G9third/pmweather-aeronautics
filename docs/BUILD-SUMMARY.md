# PMWeather Aeronautics 1.0 build summary

## Current 2026-10-09 HUD and command integration candidate

- Toolchain: Java 21, NeoForge 21.1.234; compile-only PMWeather 0.17.16 and Sable 2.0.5.
- Build: Java 21, offline `jar` and `jarDev` tasks.
- Result: public and private development compilation and packaging succeeded.
- No check, test, or regression task was run. No game session was run for this candidate.
- The wind monitor shows explicit waiting, missing-channel, unavailable, stale, and no-response states rather than rendering a calm reading when no server sample exists. PMAero logs a bounded first server sampling failure with its cause and clears that log guard on recovery or server stop. This instrumentation does not establish the cause of a particular unavailable sample without runtime log evidence.
- Public wind and test controls use `/pmaero` and `/pmiv`; former `/aerowind` and `/pmiv weather test` forms remain aliases. Private diagnostics use `/pmaero debug` so they do not occupy the public `wind` or `live` paths.
- Running `./gradlew` without a task assembles the clean public variant; `./gradlew jarDev` is the explicit private artifact task.
- Public and dev gameplay classes share the same source hash in their manifests.
- Current hashes and matching source archives are recorded in the supplied artifact manifest.
- Public JAR omits the dev provider, recorders, writers, full diagnostic commands,
  visualization, impulse profiling hooks and dev mixin configuration.
- Dependencies are not embedded in either JAR or public source archive.
- Optional integration application and numerical handling remain to be measured in game.

## Historical build evidence

Earlier 1.0 helper checks reported 488 ExternalApiChecks assertions and passing
ParticleCandidateSetRegression/ParticleWindBudgetRegression checks. Those results concern
the earlier source state and do not validate this candidate's native observation hook,
new contact behavior or private packaging.

The earlier public artifact hash was
ab17d0b84b0376e72567c80f75ef9581021590161acc78aa9f2e048d58dfa6a1.
The later local suppression candidate hash was
4d06d5ed6f3db1b3bd5f110adaa2c86de19d2dd25dbe9811a86ae912034850e8.
Current same-version rebuilds must be identified by their artifact and gameplay source hashes.
