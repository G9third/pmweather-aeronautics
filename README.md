# PMWeather Aeronautics Sable2 0.7.3

PMWeather Aeronautics connects ProtoManly's Weather wind with Sable/Create Aeronautics physics objects.

## 0.7.x focus

0.7.0 split the mod into clearer aero systems and restores realistic uneven-pressure torque without returning to the old unstable sparse-sample spin path.

Main systems:

- `AeroSurfaceCache` builds and caches exposed exterior Sable surface patches.
- `WeatherWindField` owns PMWeather wind sampling, interpolation, caching, fair sample budgets, and internal mph-style to block/second physics conversion.
- `SableAeroSolver` turns wind pressure into Sable force/torque with safety caps.

## Main defaults

```toml
windThreshold = 8.0
windInfluence = 0.1
aeroPatchPressureStrength = 1.0
aeroPatchAreaWeightStrength = 0.65
bodyWindSampleIntervalTicks = 5
airflowWindSampleIntervalTicks = 5
maxWindSamplesPerTick = 512
maxAeroPatchSamplesPerObject = 512
minAeroPatchDetailPercent = 0.05
minAeroPatchCount = 6
maxCachedAeroPatches = 4096
enableDifferentialPressureTorque = true
differentialPressureTorqueStrength = 0.6
maxDifferentialTorqueImpulse = 1000.0
maxImpulsePerSubstep = 2000.0
maxAirRelativeVelocityCorrectionPerSubstep = 0.5
```

## Notes

- Body wind now uses a dedicated PMWeather force group instead of showing as Sable drag.
- Lift/drag airflow samples wind at each lift provider's actual world position instead of using the strongest object-wide wind sample. By default this refreshes every 5 ticks, matching body wind.
- PMWeather wind speeds are treated as mph-style values for thresholds/debug display, then converted internally to block/second physics speed before Sable force/lift is applied.
- `windInfluence = 0.1` is the default because Sable block masses are lightweight gameplay values. Raise it for stronger, more arcade-like wind.
- Aero surface caches are dirtied when Sable plot blocks change, so same-bounds shape edits update faster.
- Sparse structures are scanned from loaded Sable plot chunks instead of blindly scanning the entire bounding box.
- Differential pressure torque is capped and only adds the residual uneven-pressure torque after the stable center-of-pressure line has removed uniform-pressure fake spin.

0.7.3 can back up and regenerate configs that clearly look like older 0.5.x-0.7.2 configs. It does not create any extra sentinel files in the config folder.

If an old config needs to be reset, it is moved to a backup next to the config with a name like:

```text
pmweather_aeronautics-common.pre-0_7_3-wind-0_1-reset.toml.bak
```

Normal user edits are preserved on later launches.
