# Native wind integration revision 3

## Native engine

PMAero 1.1 supports exactly PMWeather 0.16.4 in addition to the existing
0.17.14 through 0.17.16 range. Compilation uses 0.17.16. Loader metadata selects
0.16.4 before mixin application; other versions do not select its adapter.
The declared range excludes unaudited releases. Sable remains 2.0.5 through 2.x.

### PMWeather 0.16.4

Each uncached real-weather point calls the existing native `WindEngine.getWind`
overload without a cached-terrain-height argument. Its native XYZ output already
includes vertical tornado wind and is retained directly. No vertical correction,
tornado reconstruction or additional native weather query is applied. Native
weather owns the magnitude, direction, storm combination and vertical component.

The newer storm-vector observation and built-in Sable suppression hooks are skipped
because their targets are absent in 0.16.4. Modern API calls live in a separate
nested adapter class, so the legacy path does not resolve the newer overload.
Synthetic weather tests, shelter options, sample caching, wheel integration and
public command paths remain shared. The older engine resolves its own terrain
height because its API cannot accept the cached height used by the modern engine.

### PMWeather 0.17.14 through 0.17.16

Each uncached real-weather point makes one WindEngine.getWind call. PMWeather owns storm
selection, influence radii, horizontal combination, maximum-speed decisions and fire-whirl Y.
NativeStormVectorMixin observes the existing vector call inside Storm.getTornadicWind
and returns it unchanged. A scoped thread-local capture is active only for PMAero's query
and is removed in a finally scope. Only finite supercell vector Y is collected.

PMAero adds that missing Y to native combined wind. It does not add another horizontal
tornado vector, normalize to a maximum storm speed, reproduce native fire-whirl formulas,
or perform a second tornado-vector evaluation.

Overlapping captured supercell Y adds algebraically. Opposing vertical flows may cancel.
This is an independently chosen superposition rule; PMWeather does not expose a combined
XYZ API retaining this Y. Older PMAero used its own direction/speed blending, so wind
magnitudes and vehicle response may change.

## Scheduling and profiling

Synthetic test samples resolve first; mixed-batch points outside the radius use real weather.
Ordinary source sampling applies shelter once and retains the below-heightmap policy.
Aircraft atmosphere sampling bypasses discrete shelter.

Exact position/option matches share a level/tick cache across consumers. Cache memory
is bounded to 8192 entries; eviction causes a fresh query and never substitutes calm
or stale wind. Distinct aircraft points are not subject to a physics-dropping hard cap.
Large fleets still need live profiling.

Vector-only consumers avoid nearest-storm scans, snapshot copies and diagnostic records.
Private recording includes native query, cache, synthetic, invalid-vector and timing counts,
plus accepted/rejected wheel contacts.

## Hooks and distribution

On the modern weather path, native vector observation and native Sable suppression
require matching injections. Sable lift and the early wind frame apply to both paths. Optional wheel adapters remain optional. Applied callsite
counts are logged once when their target classes transform; a missed optional callsite warns.

The public JAR keeps gameplay, PMIV custom-particle wind, live wind and the test. Full recorders, writers,
impulse profiling mixins, patch visualization and diagnostic commands stay in the private
dev artifact. Observer failures disable diagnostics rather than gameplay.

This candidate has been compiled and packaged. Live mixin, flight and contact behavior
has not yet been measured. See THIRD_PARTY_NOTICES.md for provenance and dependency rights.
