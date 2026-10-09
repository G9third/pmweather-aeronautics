# PMWeather Aeronautics 1.0 build summary

## Current 2026-10-09 audit-fix candidate

- Toolchain: Java 21, NeoForge 21.1.234; compile-only PMWeather 0.17.16 and Sable 2.0.5.
- Build: offline jar and jarDev tasks.
- Result: public and private development compilation and packaging succeeded.
- No check/regression task or game session was run for this candidate.
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
