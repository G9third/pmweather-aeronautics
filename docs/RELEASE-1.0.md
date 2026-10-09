# PMWeather Aeronautics 1.0

This update keeps the public release version at 1.0 and preserves the three existing
packed external APIs at API version 2.

## Release scope

- Use the finite exposed-face pressure-profile centroid as the aerodynamic line of action,
  with center-of-mass fallback only when the centroid is invalid.
- Include angular point velocity in body-relative wind drag at each pressure application
  point.
- Add conditional wind-induced traction breakaway correction for the audited Offroad,
  Create Tracks, and No Horizon contact paths. Correction uses the current weather-pressure
  impulse relative to a zero-weather baseline at the same point motion. It preserves native
  spring/support impulse and applies only when projected weather demand exceeds recorded
  grip.
- Add public `WheelContactWindApi` version 1 for compatible independent Sable wheel solvers.
- Keep Immersive Vehicles entities under PMWeather-IV so the two mods do not apply
  duplicate wind forces to IV vehicles.
- Retain `windInfluence` default/fallback `0.1` and `windThreshold` default `0.0`.
- Preserve public API version 2 and all three packed data contracts.

## Audited adapter coverage

Offroad 1.3.0 and 1.3.2 plus Create Tracks 1.0.1 share the inspected `TireLike` wheel
contact path. Aeronautics No Horizon 1.0.3 queues virtual-track support and traction
impulses through a separate inspected helper; its exposed `tractionScale` is used as a
grip-coefficient proxy. The No Horizon hook records a contact only when both support and
traction calls occur. No Horizon 1.0.4 and independent wheel implementations have not been
audited. The generic API permits an addon to provide its own contact values.

Minecraft 1.21.1, NeoForge 21.1.234 or later, Java 21, PMWeather 0.17.14 or later, and
Sable 2.0.5 through 2.x. Aeronautics and the wheel addons are optional integrations.

## Verification

The release source and exact optional adapter targets were reviewed, then the mod JAR was
compiled with Java 21 using `./gradlew jar`. No executable API-check task, Java test suite,
game launch, or in-game physics test was run. The compile result verifies source compilation
and JAR packaging only; it does not establish runtime mixin application or physics behavior.

The public source archive contains the MIT license, current source/docs, build files, and
dependency download metadata. It excludes compile-only dependency JARs and internal review
or historical working notes.

It includes the pre-existing `ExternalApiChecks` source required by the Gradle `check`
task. That executable API check was not run for this release; `./gradlew build` will invoke it.
