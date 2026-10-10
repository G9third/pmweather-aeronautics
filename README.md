# PMWeather Aeronautics 1.0

PMWeather Aeronautics applies signed XYZ PMWeather wind to Sable rigid
bodies, including Create: Aeronautics vehicles, and exposes versioned body-pressure and
virtual lifting-surface APIs to compatible addons. Immersive Vehicles entities remain
the responsibility of PMWeather-IV; this mod does not add an IV vehicle force path.

Version 1.0 supports Immersive Vehicles through the separate [PMWeather-IV (PMIV) addon](https://github.com/G9third/pmweather-iv).
PMIV 0.12.0-rc1 shares PMAero's authoritative wind HUD and weather-test system when both
addons are installed. The [full weather-test cycle video](https://streamable.com/fomjry)
shows the four-minute flight sequence.

This 1.0 update corrects aerodynamic pressure lines of action, accounts for angular
velocity at each pressure point when body-relative wind drag is enabled, and adds
conditional wind breakaway handling for audited tire and track contact paths. The
breakaway correction is activated only when the current weather-induced net body impulse
exceeds recorded contact grip; native spring/support impulses are preserved.

## Compatibility

- Minecraft 1.21.1 and NeoForge 21.1.234 or later
- Java 21
- PMWeather 0.17.14 through 0.17.16 and Sable 2.0.5 through 2.x
- Optional wheel-contact adapters are audited for Offroad 1.3.0 and 1.3.2, Create Tracks
  1.0.1, and Aeronautics No Horizon 1.0.3
- Aeronautics, Offroad, Tracks, and No Horizon remain separate optional mods

When PMWeather is installed, PMAero suppresses PMWeather's separate built-in Sable
wind/inertia force callback by default, avoiding duplicate Sable wind forces without editing
PMWeather's config. PMWeather weather and wind sampling remain enabled. To restore that
callback, set `compatibility.suppressBuiltinSableWind = false` in
`pmweather_aeronautics-common.toml`. Removing PMAero removes the mixin, so PMWeather's
callback runs again without changing PMWeather's saved config.

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

## Aerowind commands

Use `/aerowind wind` for a wind reading and `/aerowind live on|off` to toggle the small
live HUD. `/aerowind test start` runs the short flight sequence; use `stress`, `status`,
`next`, `stop`, or `list` under `/aerowind test` to manage or inspect it. The default
sequence is four minutes and the complete test is capped at five minutes. PMIV uses the
same test and HUD when installed, while PMAero works on its own for supported Sable bodies.
PMIV custom-particle wind is enabled by default and uses bounded cached samples. Disable it
in the client config under `particleWind.enabled`. PMWeather continues to handle its own
vanilla and Aeronautics particle wind.

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

The current Java 21 candidate was compiled using the jar and jarDev tasks.
No regression suite or game session was run for this candidate. Previous 1.0 helper checks
are historical evidence, as recorded in [BUILD-SUMMARY](docs/BUILD-SUMMARY.md).

The public JAR retains /aerowind live wind and the weather test. Full force recording,
patch visualization, profiling hooks and diagnostic commands are in the separate private
dev artifact. Public source archives omit that dev implementation.

PMWeather Aeronautics source is MIT licensed; see [LICENSE](LICENSE). Third-party mod
binaries and content-pack assets retain their own licenses and are not included in this
source distribution.


## Wind integration

Native PMWeather owns horizontal combination and fire-whirl vertical motion.
PMAero restores the signed supercell Y that the supported engine evaluates internally
but discards from its combined output. It does not recreate native tornado formulas.
Overlapping supercell Y components add; this differs numerically from older PMAero blends.
See [native integration details](docs/NATIVE-WIND-INTEGRATION.md) and
[third-party notices](THIRD_PARTY_NOTICES.md).

Unpowered Offroad wheels use the native longitudinal rolling angle without residual
drive-spin blending. Sideways motion does not become forward wheel rotation.
This visual adapter is specific to the inspected Offroad path.
