# PMWeather Aeronautics

PMWeather Aeronautics is a NeoForge addon bridge that connects ProtoManly’s Weather wind with Sable/Create Aeronautics physics contraptions.

Instead of simply pushing a contraption from its center point, the mod builds a cached exterior surface patch profile from the real exposed faces of a Sable object. It samples PMWeather wind across those surfaces, calculates exterior wind pressure, updraft, and capped differential torque, then passes the result into Sable so Sable/Rapier handles movement, mass, inertia, rotation, collisions, and final physics behavior.

## Features

* PMWeather wind affects Sable/Create Aeronautics contraptions.
* Full exterior surface patch aerodynamics for sealed builds, large ships, irregular shapes, roofs, walls, edges, and exposed undersides.
* Cached exterior patch profiles reduce repeated shape calculations.
* Realistic capped differential pressure torque lets objects yaw, roll, and tumble from uneven wind pressure without returning to unstable fake spin.
* Local airflow lift sampling checks wind at the actual lift-provider position instead of using one object-wide wind value.
* Fair wind-sample budgeting keeps many active Sable objects from overloading the server.
* Tornado wind, suction, configurable updraft behavior, and smooth gust variation.
* Sable-based force pipeline for natural mass, inertia, rotation, and collision handling.
* Debug commands for checking wind, sample usage, cache behavior, and detailed object behavior.

## Requirements

PMWeather Aeronautics is built for:

* Minecraft 1.21.1
* NeoForge
* ProtoManly’s Weather / PMWeather
* Sable 2
* Create Aeronautics / Sable-compatible physics stack

Required runtime dependencies:

* PMWeather
* Sable

For local development, the required dependency jars should be placed in the local `libs/` folder.

Dependency jars are not included in this repository.

## What This Mod Does

PMWeather Aeronautics does not directly set object velocity or force contraptions into fake tornado paths.

Instead, it:

1. Finds exposed exterior faces on a Sable physics object.
2. Merges those faces into aerodynamic surface patches.
3. Samples PMWeather wind around those patches.
4. Calculates wind pressure, updraft, and uneven-pressure torque.
5. Sends the resulting force and torque into Sable.
6. Lets Sable/Rapier handle the final physics result.

This means mass, inertia, rotation, collision response, and motion remain controlled by the Sable physics pipeline.

## Useful Config Settings

The generated config file is:

```text
config/pmweather_aeronautics-common.toml
```

If objects are not reacting to wind, check that PMWeather wind speed is above the configured threshold:

```toml
[general]
windThreshold = 8.0
```

General gameplay defaults should usually be fine:

```toml
[body_wind]
windInfluence = 2.0
aeroPatchPressureStrength = 1.0
maxImpulsePerSubstep = 900.0
maxAirRelativeVelocityCorrectionPerSubstep = 0.25

[differential_torque]
enableDifferentialPressureTorque = true
differentialPressureTorqueStrength = 0.45
maxDifferentialTorqueImpulse = 180.0

[aero_patch_sampling]
maxAeroPatchSamplesPerObject = 512
minAeroPatchDetailPercent = 0.05
minAeroPatchCount = 6
maxCachedAeroPatches = 4096

[performance]
bodyWindSampleIntervalTicks = 5
maxWindSamplesPerTick = 512
```

If small or basic airborne objects look too stiff and do not rotate enough, try:

```toml
[differential_torque]
differentialPressureTorqueStrength = 0.6
maxDifferentialTorqueImpulse = 240.0
```

If many active Sable objects cause TPS drops, try:

```toml
[performance]
maxWindSamplesPerTick = 256
bodyWindSampleIntervalTicks = 10

[aero_patch_sampling]
maxAeroPatchSamplesPerObject = 128
```

If you want more detailed wind interaction for testing, try:

```toml
[performance]
maxWindSamplesPerTick = 1024

[aero_patch_sampling]
maxAeroPatchSamplesPerObject = 1024
minAeroPatchCount = 12
```

## Config Reset Notes

Version 0.7.0 is a breaking config cleanup.

Older 0.5.x and 0.6.x config values were removed or renamed. If an old config is detected, the mod may back it up and regenerate a clean 0.7.0 config.

If needed, manually delete:

```text
config/pmweather_aeronautics-common.toml
```

The mod will regenerate it with the latest settings.

## Commands

All commands are under:

```text
/pmaero
```

Check PMWeather wind at your current position:

```text
/pmaero wind
```

Check current wind sample usage:

```text
/pmaero samples
```

Enable live sample monitoring:

```text
/pmaero samples live on
```

Disable live sample monitoring:

```text
/pmaero samples live off
```

Start detailed wind debugging:

```text
/pmaero winddebug start
```

Stop detailed wind debugging:

```text
/pmaero winddebug stop
```

Check whether detailed wind debugging is currently running:

```text
/pmaero winddebug status
```

## Debugging

Use live sample monitoring when testing many contraptions:

```text
/pmaero samples live on
```

Use detailed wind debugging only when needed, because the CSV can grow quickly:

```text
/pmaero winddebug start
```

The debug file is written to:

```text
logs/pmweather_aeronautics_sable_wind_debug.csv
```

Recommended debugging process:

1. Start wind debug.
2. Reproduce the issue for 5–20 seconds.
3. Stop wind debug.
4. Check or share the generated CSV file.

## Building From Source

Clone the repository:

```powershell
git clone https://github.com/G9third/pmweather-aeronautics.git
cd pmweather-aeronautics
```

Place required local dependency jars in:

```text
libs/
```

Then build:

```powershell
.\gradlew.bat clean build
```

The built jar will be in:

```text
build/libs/
```

## Changelog

See `CHANGELOG.md` for version history.

## License

See `LICENSE`.
