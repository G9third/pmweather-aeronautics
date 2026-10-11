# PMWeather Aeronautics 1.1 build summary

## Current 1.1 compatibility candidate

- Java 21 offline `jar jarDev` compilation succeeded against PMWeather 0.17.16.
- The supplied PMWeather 0.16.4 API was inspected for native method descriptors, parameter semantics and vertical output availability.
- Version selection uses loader metadata. Exactly 0.16.4 skips the modern tornado-vector and built-in Sable hooks.
- Modern wind implementation revision 3 and packed API layouts remain unchanged for PMIV compatibility.
- Clean is the default build. Development recording remains in the separate dev artifact.
- No regression suite or Minecraft session was run for this candidate. Runtime loading and weather behavior still need in-game confirmation on both supported paths.

## Historical 2026-10-10 direct integration review

- Java 21 offline `check checkDev jar jarDev` passed.
- The packaged public source ZIP built and passed its checks independently. The default Gradle command assembles only the clean variant.
- External aerodynamic API checks passed 488 assertions. Actual packet codecs passed 65 assertions, including all one-to-eight-point particle batches, malformed array lengths/counts, defensive array copies and HUD/test snapshots.
- Particle candidate-set and wind-budget regressions passed. The private raw-query budget passed 10,011 assertions, including concurrent claims, decimation and session reset.
- Private trace sampling checks its budget before gathering nearest-storm metadata. Captured raw-query rows use the actual server tick; ordinary native wind queries and vectors are unchanged.
- Public live wind uses `/pmaero live on` and `/pmaero live off`; `/aerowind` remains a legacy alias. PMIV supplies its own aliases when installed.
- Public and private shared classes and public-source archive hashes were verified. Dependencies and private recorders are excluded from public distributions.
- No new Minecraft session was run for this candidate. HUD appearance, loaded mixin behavior, multiplayer and vehicle handling still need in-game confirmation.

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
