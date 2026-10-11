# PMWeather Aeronautics 1.1

PMWeather Aeronautics applies signed XYZ PMWeather wind to Sable rigid
bodies, including Create: Aeronautics vehicles, and exposes versioned body-pressure and
virtual lifting-surface APIs to compatible addons. Immersive Vehicles entities remain
the responsibility of PMWeather-IV; this mod does not add an IV vehicle force path.

Version 1.1 retains Immersive Vehicles support through the separate [PMWeather-IV (PMIV) addon](https://github.com/G9third/pmweather-iv).
PMIV 0.12.1 shares PMAero's authoritative wind HUD and weather-test system when both
addons are installed. The [full weather-test cycle video](https://www.youtube.com/watch?v=pCM3C3d09k0)
shows the four-minute flight sequence.

Version 1.1 adds automatic compatibility with **PMWeather 0.16.4**, preserving its native
XYZ wind, including vertical tornado wind. Only that installed version uses the legacy
adapter. PMWeather 0.17.x (0.17.0 or later) retain the existing native wind integration.
PMIV **0.12.1 or later** also accepts PMWeather 0.16.4 through PMAero 1.1. Earlier
PMIV 0.12 builds keep their existing modern-weather dependency restriction.

The retained 1.0 features correct aerodynamic pressure lines of action, account for angular
velocity at each pressure point when body-relative wind drag is enabled, and add
conditional wind breakaway handling for audited tire and track contact paths. The
breakaway correction is activated only when the current weather-induced net body impulse
exceeds recorded contact grip; native spring/support impulses are preserved.

## Compatibility

- Minecraft 1.21.1 and NeoForge 21.1.234 or later
- Java 21
- PMWeather **0.16.4**, or **0.17.x (0.17.0 or later)**, and Sable 2.x (2.0.0 or later)
- Other PMWeather 0.16.x versions and 0.18 or later are not declared supported
- Optional wheel-contact adapters are audited for Offroad 1.3.0 and 1.3.2, Create Tracks
  1.0.1, and Aeronautics No Horizon 1.0.3
- Aeronautics, Offroad, Tracks, and No Horizon remain separate optional mods

With PMWeather 0.17.x (0.17.0 or later), PMAero suppresses PMWeather's separate built-in Sable
wind/inertia force callback by default, avoiding duplicate Sable wind forces without editing
PMWeather's config. PMWeather weather and wind sampling remain enabled. To restore that
callback, set `compatibility.suppressBuiltinSableWind = false` in
`pmweather_aeronautics-common.toml`. Removing PMAero removes the mixin, so PMWeather's
callback runs again without changing PMWeather's saved config. PMWeather 0.16.4 has no
built-in Sable callback, so this suppression hook is skipped for that version.

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

## Wind and weather commands

These public commands use the same `/pmaero` prefix with PMWeather Aeronautics alone or with PMWeather-IV installed.

| Command | Action |
| --- | --- |
| `/pmaero wind` | Print one server-sampled wind reading. |
| `/pmaero live on` | Show the live wind HUD. |
| `/pmaero live off` | Hide the live wind HUD. |
| `/pmaero test start [secondsPerPhase]` | Start the flight sequence; optionally set seconds per phase. |
| `/pmaero test stress [secondsPerPhase]` | Start the stress sequence; optionally set seconds per phase. |
| `/pmaero test status` | Show the active test's status. |
| `/pmaero test next` | Advance to the next phase. |
| `/pmaero test stop` | Stop the active test. |
| `/pmaero test list` | List the flight sequence's phases. |
| `/pmaero test list stress` | List the stress sequence's phases. |

The default flight sequence is four minutes and the complete test is capped at five
minutes. Test commands require operator permission on the server. PMWeather continues to
handle its own vanilla and Aeronautics particle wind.

Private development diagnostics are available under `/pmaero debug ...` in the separate
development artifact. They do not replace the public `wind`, `live`, or `test` commands.

## Public APIs

`PMWeatherWindApi`, `ExternalAirframeBodyApi`, and `ExternalLiftingSurfaceApi` remain at
API version 2 with unchanged packed layouts. The optional generic wheel-contact hook is
API version 1 and is separate from those packed APIs. See the [API contract](docs/EXTERNAL-API.md)
and [wheel-contact integration contract](docs/WHEEL-CONTACT-API.md).

## Build and license

Run `python3 tools/fetch_compile_dependencies.py` to fetch and SHA-256 verify the exact
compile-only dependencies, then build with Java 21 using `./gradlew jar`. Running
`./gradlew` with no task assembles the clean public variant. Those dependency
JARs are not embedded in the mod JAR or public source archive. Sable supplies its embedded
Companion Common library at runtime; its extracted JAR is only a compile input.

The current Java 21 candidate was compiled using the jar and jarDev tasks.
Both mods compiled against PMWeather 0.17.0 and Sable 2.0.0, and existing public regression
checks passed. No new Minecraft session was run. Other 0.17.x and 2.x versions are
allowed under the assumption of compatible integration APIs. See
[baseline compatibility](docs/BASELINE-COMPATIBILITY.md) and [BUILD-SUMMARY](docs/BUILD-SUMMARY.md).

The public JAR includes the shared `/pmaero` wind, live display, and weather-test commands. Full force recording, patch visualization,
profiling hooks, and diagnostic commands under `/pmaero debug` are in the separate private
dev artifact. Public source archives omit that dev implementation.

PMWeather Aeronautics source is MIT licensed; see [LICENSE](LICENSE). Third-party mod
binaries and content-pack assets retain their own licenses and are not included in this
source distribution.


## Wind integration

PMWeather 0.16.4 supplies the complete native XYZ result directly. PMAero retains its
vertical wind and adds no vertical correction. With PMWeather 0.17.x (0.17.0 or later),
native PMWeather owns horizontal combination and fire-whirl vertical motion; PMAero
restores the signed supercell Y already evaluated internally but discarded from the
combined output. It does not recreate native tornado formulas. Overlapping captured
supercell Y components add in this modern path.
See [native integration details](docs/NATIVE-WIND-INTEGRATION.md) and
[third-party notices](THIRD_PARTY_NOTICES.md).

Unpowered Offroad wheels use the native longitudinal rolling angle without residual
drive-spin blending. Sideways motion does not become forward wheel rotation.
This visual adapter is specific to the inspected Offroad path.
