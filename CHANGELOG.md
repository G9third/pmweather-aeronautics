# Changelog

All notable changes to **PMWeather Aeronautics** are documented here.

This changelog is written in a GitHub-style format inspired by [Keep a Changelog](https://keepachangelog.com/). Older entries before 0.7.0 are reconstructed from project release notes and development history, so some early versions are grouped by series instead of exact patch date.

## [0.7.0] - Full Surface Patch and Realistic Torque Rework

### Added

- Added `AeroSurfaceCache` to own Sable exterior surface patch detection, merging, caching, and invalidation.
- Added `WeatherWindField` to own PMWeather wind sampling, cache behavior, interpolation, sample budgeting, and fair use across active objects.
- Added `SableAeroSolver` to convert wind pressure into Sable force and torque.
- Added full exterior surface patch aerodynamics based on exposed Sable block faces instead of the old fixed aero-profile path.
- Added bottom/underside exterior patch handling so tornado updrafts and airborne wind can affect exposed undersides more naturally.
- Added local per-lift-provider wind sampling so lift/drag airflow uses wind near the actual lift block/provider position.
- Added aero cache dirty/revision tracking so Sable block changes can invalidate stale exterior patch caches.
- Added capped differential pressure torque:
  - `enableDifferentialPressureTorque`
  - `differentialPressureTorqueStrength`
  - `maxDifferentialTorqueImpulse`
- Added startup handling for old config files containing removed 0.5.x/0.6.x keys. Legacy TOML files are moved to a `.bak` file so NeoForge can generate a clean 0.7.0 config.

### Changed

- Split the wind/aero pipeline into separate surface cache, wind field, and force solver systems.
- Reworked body wind to sample real exterior patches and apply the result through Sable/Rapier.
- Restored realistic uneven-pressure torque while keeping uniform-pressure torque stabilized.
- Large sparse structures now prefer scanning loaded Sable plot block data instead of blindly scanning the full bounding box.
- Wind cache keys now include surface/profile state so cached samples do not apply to the wrong patch layout after a shape changes.
- Body wind now uses a PMWeather wind force group instead of pretending to be Sable drag.
- World exterior blocking checks now use collision shapes instead of treating every non-air block as a full wind blocker.
- Config values were renamed and sorted into clearer 0.7.0 sections.

### Removed

- Removed deprecated 0.5.x/0.6.x compatibility config values instead of preserving them:
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

### Renamed

- `aerodynamicProfileStrength` -> `aeroPatchPressureStrength`
- `surfaceAreaWeightStrength` -> `aeroPatchAreaWeightStrength`
- `maxSurfaceWindSamples` -> `maxFallbackSurfaceWindSamples`

### Notes

- This is a breaking config reset. Delete `config/pmweather_aeronautics-common.toml` if you want a fully fresh 0.7.0 config.
- The goal of the torque rework is to make smaller/basic airborne objects yaw, roll, and tumble more naturally without returning to the old unstable sparse-sample spin behavior.

## [0.6.0] - Full Exterior Surface Patch Aerodynamics

### Added

- Added a full-resolution exterior surface patch cache for Sable objects.
- Added exposed block-face detection from Sable object geometry.
- Added greedy merging of connected coplanar exposed faces into rectangular aerodynamic patches.
- Added support for exposed underside/bottom faces.
- Added runtime outside-world blocking checks for exterior patch samples.
- Added smart patch LOD for crowded PMWeather wind sample budgets.
- Added percentage-based detail preservation for large ships and structures.
- Added patch sampling controls:
  - `maxAeroPatchSamplesPerObject`
  - `minAeroPatchDetailPercent`
  - `minAeroPatchCount`
  - `maxCachedAeroPatches`

### Changed

- Replaced the old fixed `16x16x16` aerodynamic profile as the main body-wind model.
- Body wind now uses exposed exterior patches when possible.
- Large structures can use many more exterior wind samples when budget allows.
- Sampling is reduced through patch aggregation/LOD when overloaded instead of losing coverage randomly.
- Center wind remains fallback/debug behavior instead of the normal body wind model.

### Improved

- Improved wind behavior for enclosed/sealed structures so they no longer need roof holes or internal openings to catch wind.
- Improved large-structure wind response at walls, edges, roofs, and undersides.
- Improved tornado/updraft interaction with exposed underside surfaces.
- Preserved the 0.5.9 command cleanup and earlier stability fixes.

## [0.5.9] - Command Cleanup

### Changed

- Cleaned up the command system so all commands are under one root:
  - `/pmaero`

### Commands

- `/pmaero wind`
- `/pmaero samples`
- `/pmaero samples live on`
- `/pmaero samples live off`
- `/pmaero winddebug start`
- `/pmaero winddebug stop`
- `/pmaero winddebug status`

## [0.5.5-0.5.8] - Small Object Stability Fixes

### Fixed

- Used debug logs to fix remaining 1-block instability.
- Reduced velocity feedback problems on very small objects.
- Reduced pressure-line torque offsets that could cause unstable motion.
- Limited excessive air-relative impulse on low-mass objects.

### Improved

- Tiny objects are less likely to launch to extreme speeds.
- Tiny objects are less likely to glitch through the ground.
- Stability was improved without adding artificial small-object dampening or angular damping.

## [0.5.4] - Detailed Wind Debugging

### Added

- Added detailed CSV debugging for Sable wind interaction:
  - `/pmaero winddebug start`
  - `/pmaero winddebug stop`
  - `/pmaero winddebug status`

### Debug Output

- The debug CSV records object velocity, angular velocity, wind samples, pressure centers, net force, net torque, and sample budget behavior.

## [0.5.2-0.5.3] - Torque Stability Fixes

### Fixed

- Fixed artificial rotation caused by sparse exterior sample points.
- Fixed side-pressure imbalance that could create constant fake torque.
- Reduced Dzhanibekov-like flipping on small and medium objects.

### Changed

- Wind pressure is applied more cleanly through side pressure centers instead of letting small sample differences create constant fake torque.
- Stability was improved without relying on small-object damping.

## [0.5.1] - Sample Monitoring Commands

### Added

- Added live sample monitoring commands for testing performance and wind behavior:
  - `/pmaero samples`
  - `/pmaero samples live on`
  - `/pmaero samples live off`

### Improved

- Sample monitoring now helps show active objects, sample usage, cache hits, and whether the global wind sample budget is being reached.

## [0.4.7-0.5.0] - Sable Force Pipeline

### Changed

- Moved toward a cleaner Sable-style force pipeline.
- PMWeather Aeronautics calculates wind pressure, then hands force results to Sable.
- Sable/Rapier handles mass, inertia, rotation, collisions, and final motion.
- Raised the global wind sample budget to `512` by default.

## [0.4.6] - Fair Sample Budgeting

### Added

- Added fair-share wind sample budgeting across active Sable objects.

### Changed

- When many objects are active, the mod spreads the wind sample budget more evenly instead of allowing early-ticked objects to consume most of it.
- Lowered the default exterior sample count back to `12` while keeping high profile resolution.

## [0.4.5] - High-Detail Profile Tuning

### Changed

- Increased aerodynamic profile detail for more realistic shape-based wind response.
- Improved rotation/detail for larger objects.

### Known Behavior

- Small objects became easier to destabilize due to the increased profile detail.

## [0.4.x] - Stable Multi-Point Exterior Aerodynamics

### Added

- Added/refined the aerodynamic profile as the main wind model.
- Added exterior sample pressure points so movement and rotation come from multiple outside-facing samples.

### Fixed

- Fixed the local-space profile bug that caused unstable spinning.
- Added small-object stability protections.
- Restored realistic profile rotation after the local-space fix.

### Changed

- Wind force application changed to use exterior samples instead of center-of-mass wind.
- By 0.4.4, sealed houses, large structures, and irregular Sable creations could react more naturally to PMWeather wind.

## [0.3.x] - Optimization, Tornado Updraft, and Aero Profile

### Added

- Added cached wind sampling for TPS optimization.
- Added smoother cached wind interpolation.
- Added tornado updraft behavior.
- Added smooth coherent tornado gust behavior.
- Added bounded lift heights.
- Added wind debug commands.
- Introduced the first cached aerodynamic profile system so rotation could come from object shape instead of simple bounding-box sampling.

### Improved

- Made the wind system more practical for gameplay and server use.
- Improved tornado and wind behavior without requiring every tick to perform expensive fresh sampling.

## [0.2.x] - Sable 2 Rewrite

### Added

- Rebuilt the mod around the newer Sable 2 API.
- Added support for Sable 2 physics bodies/sublevels.
- Added multi-point raw PMWeather wind sampling.

### Changed

- Improved build compatibility with Sable 2.0.3.
- Strengthened tornado/body wind response.
- Later 0.2 versions improved surface-aware wind behavior so larger structures and closed shapes responded better to tornado wind.

## [0.1.x] - Initial PMWeather Aeronautics Prototype

### Added

- Added the first PMWeather Aeronautics bridge between ProtoManly's Weather wind and Sable/Create Aeronautics contraptions.
- Added early configurable wind threshold/influence behavior.
- Added initial wind force application for moving physics objects.

### Notes

- This was the early prototype era before the Sable 2 rewrite, cached aerodynamic profiles, full exterior patch sampling, fair budgeting, detailed debug commands, and the later Sable-style force pipeline.
