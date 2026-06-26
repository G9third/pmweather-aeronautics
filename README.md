# PMWeather Aeronautics Sable2 0.7.0

PMWeather Aeronautics connects ProtoManly's Weather wind with Sable/Create Aeronautics physics objects.

## 0.7.0 focus

0.7.0 splits the mod into clearer aero systems and restores realistic uneven-pressure torque without returning to the old unstable sparse-sample spin path.

Main systems:

- `AeroSurfaceCache` builds and caches exposed exterior Sable surface patches.
- `WeatherWindField` owns PMWeather wind sampling, interpolation, caching, and fair sample budgets.
- `SableAeroSolver` turns wind pressure into Sable force/torque with safety caps.

## Main defaults

```toml
windThreshold = 8.0
windInfluence = 2.0
aeroPatchPressureStrength = 1.0
aeroPatchAreaWeightStrength = 0.65
maxWindSamplesPerTick = 512
maxAeroPatchSamplesPerObject = 512
minAeroPatchDetailPercent = 0.05
minAeroPatchCount = 6
maxCachedAeroPatches = 4096
enableDifferentialPressureTorque = true
differentialPressureTorqueStrength = 0.45
maxDifferentialTorqueImpulse = 180.0
```

## Notes

- Body wind now uses a dedicated PMWeather force group instead of showing as Sable drag.
- Lift/drag airflow now samples wind at each lift provider's actual world position instead of using the strongest object-wide wind sample.
- Aero surface caches are dirtied when Sable plot blocks change, so same-bounds shape edits update faster.
- Sparse structures are scanned from loaded Sable plot chunks instead of blindly scanning the entire bounding box.
- Differential pressure torque is capped and only adds the residual uneven-pressure torque after the stable center-of-pressure line has removed uniform-pressure fake spin.

0.7.0 intentionally removes the old/deprecated config names. If you update from an older version and the TOML gets corrected or regenerated, that is expected. Deleting `config/pmweather_aeronautics-common.toml` is still the cleanest way to force a fresh 0.7.0 config.


On startup, 0.7.0 checks for older config keys and moves the old TOML to a `.bak` file before NeoForge registers the new config. This intentionally forces a clean sorted 0.7.0 config instead of preserving deprecated values.
