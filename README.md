# PMWeather Aeronautics Bridge

Starter NeoForge 1.21.1 addon that applies ProtoManly's Weather wind to Sable/Create Aeronautics physics sub-levels.

## What it does

### v0.1 airflow path

This version adds the deeper aerodynamic integration. It mixins into:

```text
dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider#sable$contributeLiftAndDrag
```

Sable computes each lift-provider / wing surface's local velocity and then calculates drag and lift from that velocity. The mixin injects immediately after Sable transforms that velocity into sub-level-local space, samples ProtoManly's Weather wind at the lift provider's world position, converts the wind into the same local space, and subtracts it from Sable's `LIFT_VELO`.

That means Sable's existing wing math naturally sees:

- headwind as increased airflow,
- tailwind as reduced airflow,
- crosswind as sideways airflow over fins/rudders/stabilizers,
- storm/tornado wind as stronger local airflow if PMWeather reports it.

### Optional body-push path

The original center-of-mass push system is still included and now defaults to enabled. Set `enableBodyPush` to `false` in the config if you want pure lift/drag airflow integration without pushing the whole sub-level.

When body push is enabled, the mod:

- Registers a Sable `SablePrePhysicsTickEvent` listener.
- Iterates all loaded `ServerSubLevel`s in the current Sable physics system.
- Samples `dev.protomanly.pmweather.weather.WindEngine.getWind(...)` at the sub-level center of mass.
- Converts the resulting world-space wind impulse into Sable local/plot space.
- Queues the impulse through `ServerSubLevel#getOrCreateQueuedForceGroup(ForceGroups.DRAG.get())`.
- Adds optional high-wind angular turbulence.

## Build notes

The `build.gradle` is set up for local jars. Create a `libs/` folder and place matching 1.21.1 jars there, using these filenames or update the dependency names:

```text
libs/pmweather-0.16.4-1.21.1-alpha.jar
libs/sable-neoforge-1.21.1-1.2.2.jar
libs/create-aeronautics-bundled-1.21.1-1.2.1.jar
```

Then run:

```bash
./gradlew build
```

If Sable/Aeronautics jars use different filenames, update the `compileOnly name:` entries in `build.gradle`.

## Important tuning

The default config is conservative. Increase `windInfluence` slowly. Sable applies these during physics substeps, so a value that looks small can still be strong.

The first config file appears under:

```text
config/pmweather_aeronautics-common.toml
```

## Current caveats

- This source has not been verified against compiled Sable/Aeronautics jars in this environment. The source zips were available, but the exact published dependency jars were not.
- The mixin target is intentionally narrow, but it is still a mixin into Sable internals. If Sable changes `BlockSubLevelLiftProvider#sable$contributeLiftAndDrag`, this may need updating.
- It affects Sable lift-provider blocks. Balloon/static lift and propeller thrust are not modified directly.
- Sampling PMWeather per lift provider is the most physically accurate approach, but very large contraptions could need caching/tuning later.
