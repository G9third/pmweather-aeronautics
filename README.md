# PMWeather Aeronautics 0.8.0

PMWeather Aeronautics connects ProtoManly's Weather wind with Sable/Create Aeronautics physics objects.

## 0.8.0 focus

0.8.0 turns the 0.7.x wind experiments into the new default body-wind model. Body push now uses quadratic, area-weighted exterior surface pressure instead of the older mostly-linear feel, so tornado wind ramps up much harder than normal 10-15 mph wind and larger exposed faces catch more total force.

Main systems:

- `AeroSurfaceCache` builds and caches exposed exterior Sable surface patches.
- `WeatherWindField` owns PMWeather wind sampling, interpolation, caching, fair sample budgets, and internal mph-style to block/second physics conversion.
- `SableAeroSolver` turns wind pressure into Sable force/torque with safety caps.
- Body wind uses quadratic exterior surface pressure, real represented patch area, and air-relative wind.

## Main defaults

```toml
windThreshold = 8.0
windInfluence = 0.06
aeroPatchPressureStrength = 1.0
aeroPatchAreaWeightStrength = 1.0
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
tornadoUpdraftStrength = 0.35
tornadoUpdraftPressureStrength = 1.0
```

The temporary ground-contact drag / grounded wind-resistance system is removed. There is no `ground_drag` section in the fresh generated config.

## Notes

- Body wind now uses a dedicated PMWeather force group instead of showing as Sable drag.
- Lift/drag airflow samples wind at each lift provider's actual world position instead of using the strongest object-wide wind sample. By default this refreshes every 5 ticks, matching body wind.
- PMWeather wind speeds are treated as mph-style values for thresholds/debug display, then converted internally to block/second physics speed before Sable force/lift is applied.
- `windInfluence = 0.06` is the default for the quadratic body-pressure solver. Tornado wind still scales much harder than 10-15 mph wind because force grows with wind speed squared instead of mostly linearly.
- `aeroPatchAreaWeightStrength = 1.0` means exposed surface area is preserved by default. Bigger exposed faces catch more total wind force.
- `massScaling = 0.0` remains the clean default because Sable/Rapier already applies the object's real mass when impulses are applied.
- Aero surface caches are dirtied when Sable plot blocks change, so same-bounds shape edits update faster.
- Sparse structures are scanned from loaded Sable plot chunks instead of blindly scanning the entire bounding box.
- Differential pressure torque is capped and only adds the residual uneven-pressure torque after the stable center-of-pressure line has removed uniform-pressure fake spin.
- Tornado updraft/bottom-surface pressure is not diluted by unrelated side-wall wind samples, so larger objects with exposed undersides can lift more consistently. The extra tornado updraft-pressure boost defaults to `1.0` so lift uses the same quadratic body function instead of a low-gravity-feeling multiplier.

0.8.0 can back up and regenerate configs that clearly look like older 0.5.x-0.7.5e generated configs. It does not create any extra sentinel files in the config folder.

If an old config needs to be reset, it is moved to a backup next to the config with a name like:

```text
pmweather_aeronautics-common.pre-0_8_0.toml.bak
```

Normal user edits are preserved on later launches.
