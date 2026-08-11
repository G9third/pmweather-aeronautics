# PMWeather Aeronautics 0.9.0
PMWeather Aeronautics connects **ProtoManly's Weather / PMWeather** wind with **Sable / Create Aeronautics** physics objects.
It lets Sable physics objects and Create Aeronautics contraptions react to PMWeather wind, storms, and tornadoes using exterior surface pressure. PMWeather Aeronautics samples wind from PMWeather, converts it into physics wind, applies pressure across exposed exterior faces, and passes the resulting force and torque into Sable so Sable handles movement, rotation, collisions, mass, and inertia.
## 0.9.0 focus
0.9.0 promotes the proven 0.8.2f baseline and keeps 0.8.2e's corrected pre-lift same-tick batching and native PMWeather 3D tornado vector, increases default airflow spatial detail, and removes exact per-query terrain-height overhead without reducing sample frequency. Body exterior patches and lift-provider airflow now share one same-tick global budget. Nearby wing/control/balloon providers are represented by component-local airflow probe regions while every provider still performs its own Sable lift/drag calculation. All active sub-levels are collected before resolution; their body and airflow requests are flattened into one same-tick level-wide batch, exact duplicate world coordinates are collapsed even across different objects, terrain height is cached per X/Z column and passed through PMWeather's public terrain-height overload directly, and the PMWeather storm list is snapshotted once per level/tick for native tornado recomposition.
Main systems:
- `AeroSurfaceCache` builds and caches exposed exterior Sable surface patches.
- `WeatherWindField` handles batched PMWeather wind sampling, exact-coordinate deduplication, native 3D tornado-vector recomposition, interpolation, caching, and mph-style to block/second physics conversion.
- `PhysicsTickWindBatch` discovers lift-provider components, builds adaptive airflow regions, and splits the global query budget between body pressure and local airflow without removing any Sable lift providers.
- `SableAeroSolver` turns exterior wind pressure into Sable force and torque.
- Body wind uses air-relative wind, quadratic pressure, real represented patch area, and Sable force groups.
- Tornado vertical airflow now comes directly from PMWeather; PMAero no longer adds synthetic updraft, gust noise, altitude fade, lift ceilings, or a tornado-only bottom-surface pressure multiplier.
## Requirements
- Minecraft 1.21.1
- NeoForge
- ProtoManly's Weather / PMWeather
- Sable
- Create Aeronautics / Create Aeronautics Bundled
- Create Aeronautics Lift Patch 1.1.0 is supported but optional.
## Commands
All commands are under:
```text
/pmaero
```
| Command | What it does |
| --- | --- |
| `/pmaero wind` | Shows the sampled PMWeather wind at your position. |
| `/pmaero samples` | Shows current wind sample usage and budget information. |
| `/pmaero samples live on` | Enables live sample monitoring. |
| `/pmaero samples live off` | Disables live sample monitoring. |
| `/pmaero winddebug start` | Starts detailed wind debug logging. |
| `/pmaero winddebug stop` | Stops detailed wind debug logging. |
| `/pmaero winddebug status` | Shows whether wind debug logging is active. |
## Default config
A fresh 0.8.x config is generated at:
```text
config/pmweather_aeronautics-common.toml
```
Default values:
```toml
[general]
windThreshold = 8.0
enableTornadoSuction = true
[airflow_lift]
enableAirflowLift = true
airflowInfluence = 1.0
airflowWindSampleIntervalTicks = 2
airflowRegionEdgeBlocks = 4
maxAirflowSamplesPerObject = 32
minAirflowSamplesPerComponent = 1
minAirflowSampleRatio = 0.5
[body_wind]
enableBodyPush = true
enableBodyRelativeWindDrag = true
windInfluence = 0.06
massScaling = 0.0
aeroPatchPressureStrength = 1.0
aeroPatchAreaWeightStrength = 1.0
maxImpulsePerSubstep = 2000.0
maxAirRelativeVelocityCorrectionPerSubstep = 0.5
[differential_torque]
enableDifferentialPressureTorque = true
differentialPressureTorqueStrength = 0.6
maxDifferentialTorqueImpulse = 1000.0
[aero_patch_sampling]
maxAeroPatchSamplesPerObject = 32
minAeroPatchDetailPercent = 0.05
minAeroPatchCount = 6
maxCachedAeroPatches = 4096
[performance]
bodyWindSampleIntervalTicks = 1
maxWindSamplesPerTick = 128
maxFallbackSurfaceWindSamples = 12
enableEdgeWindSampling = true
edgeWindSampleMargin = 2.0
[debug]
debugLogging = false
```
## Config reference
### `[general]`
| Setting | Default | What it does |
| --- | ---: | --- |
| `windThreshold` | `8.0` | Minimum PMWeather wind-vector magnitude before the mod applies effects. This value stays in PMWeather/mph-style units even though physics wind is converted internally. Raising it makes weak wind do nothing. Lowering it lets lighter wind affect contraptions. |
| `enableTornadoSuction` | `true` | Allows PMWeather tornado/suction behavior to be included in sampled wind. Disable this if tornado wind from PMWeather itself is causing unwanted behavior. |
### `[airflow_lift]`
These settings affect Sable lift/drag providers, such as parts that use local airflow.
| Setting | Default | What it does |
| --- | ---: | --- |
| `enableAirflowLift` | `true` | Injects local PMWeather wind into Sable lift/drag calculations so each lift provider sees wind at its own block position. Disable this if you only want whole-body wind pressure and not local lift-provider airflow. |
| `airflowInfluence` | `1.0` | Multiplier for PMWeather wind used as aerodynamic airflow for lift providers. `1.0` means the internally converted PMWeather wind speed is used directly. Higher values make wings/lift providers feel stronger in weather. |
| `airflowWindSampleIntervalTicks` | `2` | How often an airflow region asks PMWeather for fresh wind, in ticks. Every lift provider mapped to that region still performs its own Sable lift/drag calculation. |
| `airflowRegionEdgeBlocks` | `4` | Base spatial detail for component-local airflow regions. `1` approaches per-provider probing; larger values combine nearby wing/balloon providers before global budget LOD is applied. |
| `maxAirflowSamplesPerObject` | `32` | Maximum target PMWeather airflow probes for one Sable sub-level before the global budget is shared with body patches. This caps queries, not lift providers or their forces. |
| `minAirflowSamplesPerComponent` | `1` | Minimum target probes per connected, compatible lift-provider component while budget permits, so separate wing/control/balloon components are represented before extra detail is allocated. |
| `minAirflowSampleRatio` | `0.5` | Minimum desired airflow probe ratio before the hard global budget applies. With 10 providers, the allocator now targets at least 5 probes instead of allowing a 10→3 reduction. Set `1.0` for one desired PMWeather probe per provider. |
### `[body_wind]`
These settings control the main exterior pressure force applied to the whole Sable sub-level.
| Setting | Default | What it does |
| --- | ---: | --- |
| `enableBodyPush` | `true` | Enables whole-body exterior wind pressure. Disable this for airflow lift integration only, with no whole-contraption wind push. |
| `enableBodyRelativeWindDrag` | `true` | Uses air-relative wind by subtracting the Sable body's current linear velocity from PMWeather wind. This prevents objects from being accelerated endlessly past the local air speed in one direction. |
| `windInfluence` | `0.06` | Main body wind strength after PMWeather mph-style wind is converted to block/second physics speed. This is the main value to tune if all body wind feels too weak or too strong. With the 0.8.x quadratic solver, tornado wind scales much harder than light wind. |
| `massScaling` | `0.0` | Optional extra mass-based damping. `0.0` disables it. `1.0` is strongest. Sable/Rapier already accounts for object mass, so `0.0` is the clean default. Use this only as a gameplay helper if huge heavy builds move too easily. |
| `aeroPatchPressureStrength` | `1.0` | Multiplier for the quadratic exterior pressure solver. This is a lower-level pressure multiplier than `windInfluence`. Usually keep this at `1.0` and tune `windInfluence` first. |
| `aeroPatchAreaWeightStrength` | `1.0` | Controls how strongly exposed patch area affects force. `1.0` means physical area weighting, so larger exposed faces catch more total wind. `0.0` makes patches more equal-weighted and less physically realistic. |
| `maxImpulsePerSubstep` | `2000.0` | High safety cap for linear wind impulse per Sable physics substep. This is meant to catch extreme spikes, not tune normal wind strength. Lower it only if wind creates violent one-frame launches. |
| `maxAirRelativeVelocityCorrectionPerSubstep` | `0.5` | Limits how much of the current air-relative normal velocity body wind may correct in one Sable physics substep. Lower values make wind acceleration smoother and less flingy. Higher values let wind grab objects more aggressively. |
### `[differential_torque]`
These settings control uneven-pressure torque, which makes wind hit different parts of the contraption differently.
| Setting | Default | What it does |
| --- | ---: | --- |
| `enableDifferentialPressureTorque` | `true` | Enables extra torque from uneven exterior pressure. This helps airborne objects yaw, roll, and tumble instead of looking stiff. |
| `differentialPressureTorqueStrength` | `0.6` | Strength of uneven-pressure torque. `0.0` disables the extra torque. `1.0` uses the full measured residual patch torque. Higher values make objects rotate more easily in uneven wind. |
| `maxDifferentialTorqueImpulse` | `1000.0` | High safety cap for uneven-pressure torque impulse per Sable physics substep. Lower it if objects spin violently from wind spikes. |
### `[aero_patch_sampling]`
These settings control the exterior surface patch model used by body wind.
| Setting | Default | What it does |
| --- | ---: | --- |
| `maxAeroPatchSamplesPerObject` | `32` | Maximum full-surface aero patches from one Sable object that may request fresh PMWeather wind during one body wind update when budget allows. Higher values preserve more detail on large structures but cost more samples. |
| `minAeroPatchDetailPercent` | `0.05` | Minimum fraction of a full-resolution patch set to preserve when sample budget forces smart patch LOD. `0.05` means a 400-patch ship may merge down to about 20 representative patches. |
| `minAeroPatchCount` | `6` | Absolute minimum representative patch count for compact objects when smart LOD is used. `6` keeps simple cube-like objects represented by their main pressure directions. |
| `maxCachedAeroPatches` | `4096` | Maximum full-resolution exterior patches cached per Sable object after greedy face merging. Higher values preserve more tiny surface regions but use more memory and processing. |
### `[performance]`
These settings control PMWeather wind query cost and fallback sampling behavior.
| Setting | Default | What it does |
| --- | ---: | --- |
| `bodyWindSampleIntervalTicks` | `1` | How often each Sable sub-level asks PMWeather for body wind samples, in ticks. `1` means every game tick. Cached wind is reused between Sable physics substeps. |
| `maxWindSamplesPerTick` | `128` | Global PMWeather query budget shared by body patches and airflow regions across all active Sable sub-levels. Active objects are collected first, receive fair detail allocations, and are then resolved together in one level-wide same-tick batch. |
| `maxFallbackSurfaceWindSamples` | `12` | Maximum exterior fallback wind samples used by legacy compatibility paths. The main 0.8.x body wind path primarily uses `maxAeroPatchSamplesPerObject`. |
| `enableEdgeWindSampling` | `true` | Enables extra fallback wind samples around the sub-level roof and edges for compatibility sampling paths. The main exterior patch system still handles normal body wind. |
| `edgeWindSampleMargin` | `2.0` | Distance outside the sub-level bounding box used for fallback roof and edge wind samples. |
### `[debug]`
| Setting | Default | What it does |
| --- | ---: | --- |
| `debugLogging` | `false` | Enables occasional wind and impulse logging. This can be very noisy, so leave it off unless debugging. |
## Tuning tips
For most gameplay testing, tune these first:
```toml
[body_wind]
windInfluence = 0.06
maxAirRelativeVelocityCorrectionPerSubstep = 0.5
```
If regular wind is too strong, lower `windInfluence`.
If tornadoes fling objects too sharply instead of carrying them, try lowering `maxAirRelativeVelocityCorrectionPerSubstep` before lowering all wind strength.
Tornado vertical strength is no longer separately tunable in PMWeather Aeronautics; it follows PMWeather's native tornadic wind vector.
## Config reset behavior
0.8.2a backs up the previous generated 0.8.2 config once so the removed synthetic tornado-updraft options disappear from disk, then generates the native-tornado-vector config. It does not create extra sentinel files in the config folder.
If an old config needs to be reset, it is moved to a backup next to the config with a name like:
```text
pmweather_aeronautics-common.pre-0_8_2a.toml.bak
```
Normal user edits are preserved on later launches after the fresh 0.8.x config has been generated.
## Compatibility notes
PMWeather Aeronautics records its wind forces using a registered Sable force group so Create Aeronautics / Simulated contraption diagrams and physics goggles can encode diagram data safely.
Thanks to **@michardy** for the detailed simulated contraption diagram crash report in issue **#2**.
