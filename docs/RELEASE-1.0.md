# PMWeather Aeronautics 1.0

## Now supports Immersive Vehicles

Create Aeronautics remains the original integration. Version 1.0 also supplies the shared
wind APIs, live HUD and weather test used by [PMIV](https://github.com/G9third/pmweather-iv).
The [full weather-test cycle video](https://streamable.com/fomjry) shows the four-minute flight sequence.

## Current update

- Use native horizontal tornado combination and fire-whirl Y. Restore discarded
  supercell Y from native vectors already evaluated during that query.
- Remove copied native tornado blending and unnecessary repeated vector evaluation.
- Resolve test samples before real weather and apply ordinary shelter gating once.
- Share exact same-tick wind queries and add a vector-only API for PMIV force sampling.
- Keep API version 2 and packed layouts. PMIV uses wind implementation revision 3.
- Preserve conditional wind breakaway, native support impulses and friction coefficients.
- Correct unpowered Offroad visual spin using its per-wheel longitudinal rolling angle.
- Report applied compatibility hooks and missed optional adapters.
- Move full diagnostics to a private dev JAR. Live wind, weather test and bounded
  PMIV custom-particle wind remain in the public JAR.
- Retain body wind default 0.1, threshold 0.0, and the four-minute default weather cycle.

Support covers PMWeather 0.17.14 through 0.17.16; compilation uses 0.17.16.
Inspected force adapters cover Offroad 1.3.0/1.3.2, Create Tracks 1.0.1 and No Horizon 1.0.3.
The visual hook was inspected against Offroad 1.3.2. Unknown/overridden wheel paths need
their own adapter or WheelContactWindApi; universal runtime coverage is not claimed.

## Build evidence

Java 21 offline compilation and packaging use the jar and jarDev tasks.
No regression suite or game session was run for this update. Earlier helper results are
historical and do not validate these new runtime hooks or numerical wind behavior.
See [BUILD-SUMMARY](BUILD-SUMMARY.md) and [integration details](NATIVE-WIND-INTEGRATION.md).

Matching public source includes gameplay, build files, notices and dependency metadata.
It excludes private dev implementation and third-party JARs.
