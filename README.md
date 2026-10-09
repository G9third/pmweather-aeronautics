# PMWeather Aeronautics 1.0

PMWeather Aeronautics applies source-native signed XYZ PMWeather wind to Sable rigid
bodies, including Create: Aeronautics vehicles, and exposes versioned body-pressure and
virtual lifting-surface APIs to compatible addons. Immersive Vehicles entities remain
the responsibility of PMWeather-IV; this mod does not add an IV vehicle force path.

This 1.0 update corrects aerodynamic pressure lines of action, accounts for angular
velocity at each pressure point when body-relative wind drag is enabled, and adds
conditional wind breakaway handling for audited tire and track contact paths. The
breakaway correction is activated only when the current weather-induced net body impulse
exceeds recorded contact grip; native spring/support impulses are preserved.

## Compatibility

- Minecraft 1.21.1 and NeoForge 21.1.234 or later
- Java 21
- PMWeather 0.17.14 or later and Sable 2.0.5 through 2.x
- Optional wheel-contact adapters are audited for Offroad 1.3.0 and 1.3.2, Create Tracks
  1.0.1, and Aeronautics No Horizon 1.0.3
- Aeronautics, Offroad, Tracks, and No Horizon remain separate optional mods

The whole-body aerodynamic pressure path uses Sable rigid-body sublevels. The tire
breakaway adapter observes the shared Offroad/Tracks `TireLike` wheel path. The No Horizon
adapter observes only its virtual-track helper when both native support and traction
impulses are queued. For that helper, its `tractionScale` is used as a conservative grip
coefficient proxy because its queue path does not expose a terrain friction measurement.
Branches without a captured support impulse are skipped. Other wheel addons can integrate
through [`WheelContactWindApi`](docs/WHEEL-CONTACT-API.md); no adapter coverage is claimed
for uninspected versions or independent wheel solvers.

The body `windInfluence` default and fallback remain `0.1`; `windThreshold` defaults to
`0.0`. Existing saved configuration values are retained by NeoForge.

## Public APIs

`PMWeatherWindApi`, `ExternalAirframeBodyApi`, and `ExternalLiftingSurfaceApi` remain at
API version 2 with unchanged packed layouts. The optional generic wheel-contact hook is
API version 1 and is separate from those packed APIs. See the [API contract](docs/EXTERNAL-API.md)
and [wheel-contact integration contract](docs/WHEEL-CONTACT-API.md).

## Build and license

Run `python3 tools/fetch_compile_dependencies.py` to fetch and SHA-256 verify the exact
compile-only dependencies, then build with Java 21 using `./gradlew jar`. Those dependency
JARs are not embedded in the mod JAR or public source archive. Sable supplies its embedded
Companion Common library at runtime; its extracted JAR is only a compile input.

The pre-existing `ExternalApiChecks` source is included because Gradle's `check` task calls
it. Running `./gradlew build` executes that dependency-free API check; the 1.0 release
verification used `./gradlew jar` and did not run it.

PMWeather Aeronautics source is MIT licensed; see [LICENSE](LICENSE). Third-party mod
binaries and content-pack assets retain their own licenses and are not included in this
source distribution.
