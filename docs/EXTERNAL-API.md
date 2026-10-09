# External PMWeather Aeronautics APIs - version 2

All entry points are public static. Body/lift calculations use SI units and the
caller's Cartesian frame. They do not query Minecraft, own bodies, advance position,
or add gravity. Callers must discard results when an API returns false.

## Wind

`PMWeatherWindApi.sampleAircraftAtmosphereInto(ServerLevel, double[] xyz, double[] output)`
uses XYZ position triples and 13 output values per position, preserving the existing
detailed atmosphere layout documented in `PMWeatherWindApi.java`. The first three
values are signed XYZ wind in mph: native combined X/Z and existing Y, plus the
native supercell Y discarded by the supported combined engine. PMIV converts mph to m/s once.
Query from the owning server tick and freeze the result for physics substeps.
This atmosphere batch excludes discrete terrain/shelter gating as in the baseline
aircraft atmosphere API. It does not cap or synthesize weather velocity.

sampleAircraftWindInto(ServerLevel, double[] xyz, double[] output) is an additive,
vector-only entry point with VECTOR_RESULT_STRIDE = 3. It uses caller-owned XYZ buffers
and avoids diagnostic nearest-storm scans. PMIV requires WIND_IMPLEMENTATION_REVISION = 3;
install its matching PMAero build even though the display version remains 1.0.

Detailed slot 3 identifies an added native supercell Y contribution. It does not mean
the full output equals an individual native tornado vector. Slot 11 is retained for
layout compatibility but is NaN for native samples, because PMWeather exposes no
authoritative combined influence radius. Synthetic test metadata keeps its zero/Infinity
sentinels. Ordinary no-storm metadata keeps its NaN/-1 sentinels.

## Body

`ExternalAirframeBodyApi.evaluatePackedInto` retains the 0.9.0d public layout:
11 input values per patch, a six-value aggregate force/moment header and five
output values per patch. See the source Javadoc for exact order and coefficients.
Forces and moments are about the caller's supplied center of mass. Input/output
buffers must be separate. Invalid patch/aggregate results fail the batch.

## Virtual lifting surfaces

`ExternalLiftingSurfaceApi.evaluatePackedInto(double[] input, double[] output)`
has API version 2, input stride 35 and output stride 15. Each input surface is:

| Offset | Value |
| --- | --- |
| 0–2 | Application point |
| 3–5 / 6–8 / 9–11 | Chord / span / normal axes |
| 12–15 | Area, span, chord length, aspect ratio |
| 16 | Authored control deflection, radians |
| 17–19 | Physical relative velocity |
| 20–22 / 23 | Adjusted relative velocity / enable flag |
| 24 | Prior separation; NaN initializes from physical target |
| 25–26 | Seconds elapsed / advance-state flag |
| 27–29 | Lift slope, maximum CL, profile/form CD |
| 30–31 | Reserved zero, wetted/reference area ratio |
| 32–34 | Air density, zero-lift CL, induced CD factor |

An induced factor of NaN derives it from aspect ratio; zero wetted ratio selects
the supplied legacy profile CD. Axes must form a nondegenerate right-handed,
orthogonal frame (chord cross span equals normal). Vectors share that caller frame.
Relative velocity is body-point velocity minus atmospheric velocity.

Output contains force XYZ (0–2), application point (3–5), CL/CD (6–7), physical
alpha (8), separation/target (9–10), physical chord/normal/span flow (11–13) and
attached-flow alpha (14). The coefficient calculation uses adjusted normal flow
only for attached lift; physical flow determines the separation target and clock.
The adjustment fades with separation. The input control angle is never modified.

The caller persists separation only on actual physics substeps. Preview/owner-tick
calls set advance-state false. PMIV uses the same point for airflow evaluation and
force application; changes to the separation-weighted point take effect next substep.
Both body and lift APIs reuse caller buffers without per-evaluation allocations.

## Version 2 validation

All three APIs export `API_VERSION = 2`; packed sizes remain unchanged. Consumers should
check API versions and strides before use.
Zero-sized lift and wind batches are valid. An empty body batch uses a six-value
zero header. Zero density is valid. Body patch area must be nonnegative, active
patch normals must be nonzero, and boolean flags must be exactly zero or one.
Lift reserved field 30 must be zero. Invalid nonaliased output buffers are cleared;
aliased input/output is rejected without modifying the input. Null output cannot
be cleared. Wind diagnostic metadata retains its documented no-storm NaN/sentinel
values; the actual XYZ wind must be finite. Provider failures may throw and callers
must reject that load update as well as false returns.

Quiet chord-normal flow suppresses lift but retains tangential skin friction and
profile/form drag, including pure spanwise flow. It still relaxes separation.
