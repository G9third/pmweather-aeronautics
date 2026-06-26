# PMWeather Aeronautics 0.7.0 changes

0.7.0 is a source-level rework of the 0.6 exterior patch system.

## Added

- Added `AeroSurfaceCache` for Sable exterior patch detection/cache ownership.
- Added `WeatherWindField` for PMWeather wind sampling, interpolation, budgets, and wind cache ownership.
- Added `SableAeroSolver` for stable pressure-line force and capped differential torque.
- Added Sable plot block-change invalidation for aero surface caches.
- Added local per-lift-provider PMWeather wind sampling.
- Added capped differential pressure torque config:
  - `enableDifferentialPressureTorque = true`
  - `differentialPressureTorqueStrength = 0.45`
  - `maxDifferentialTorqueImpulse = 180.0`

## Changed

- Body wind now uses a PMWeather Wind force group instead of Sable Drag.
- Aero patch cache keys now include a profile salt based on shape revision/fingerprint.
- Exterior surface scanning now prefers loaded Sable plot chunks for sparse-build performance.
- World exterior blocking checks now use collision shapes instead of treating all non-air blocks as full wind blockers.
- Airflow lift no longer uses the strongest object-wide profile sample.

## Important behavior change

0.6 removed most residual point torque to stop small-object instability. 0.7 keeps the stable uniform-pressure line, then adds only the measured uneven-pressure residual back through a configurable cap. This should improve realistic yaw/tumble without returning to the old fake spin problem.

## Config cleanup

0.7.0 intentionally removes the old deprecated config compatibility entries instead of preserving them. Existing 0.5.x/0.6.x TOML files may be corrected by NeoForge or should be deleted so the mod can generate the new sorted 0.7.0 config.

Removed/renamed old config names include:

- `turbulenceMultiplier`
- `surfaceShearFactor`
- `surfaceTorqueFactor`
- `surfaceDifferentialThresholdRatio`
- `aerodynamicProfileResolution`
- `aerodynamicProfileFullTorqueInertia`
- `aerodynamicProfileMinTorqueScale`
- `aerodynamicProfileMaxTorqueImpulse`
- `aerodynamicProfileMinTorqueInertia`
- `minSurfaceWindSamplesWhenBudgeted`
- `aerodynamicProfileStrength` -> `aeroPatchPressureStrength`
- `surfaceAreaWeightStrength` -> `aeroPatchAreaWeightStrength`
- `maxSurfaceWindSamples` -> `maxFallbackSurfaceWindSamples`

0.7.0 also moves old 0.5.x/0.6.x configs containing removed keys to a `.bak` file on startup so NeoForge can generate a clean sorted config.
