# PMWeather Aeronautics 0.7.0

## 0.7.0 - Split Aero Systems and Real Differential Torque

Split the wind/aero pipeline into clearer systems. `AeroSurfaceCache` now handles Sable exterior surface patches, `WeatherWindField` handles PMWeather wind sampling and budgets, and `SableAeroSolver` handles the final Sable force/torque conversion.

Added capped differential pressure torque. Uniform side pressure still uses a stable center-of-pressure line through the center of mass, but uneven patch pressure can now add realistic residual torque. This should make airborne contraptions, especially smaller or simpler shapes, look less stale while avoiding the old uncontrolled sparse-sample spin.

Lift-provider airflow now samples wind at the actual lift block/provider position instead of using the strongest object-wide wind. Aero surface caches are dirtied when Sable plot blocks change, and sparse structures are scanned from loaded plot chunks instead of the whole bounding box.


Config was also cleaned up as a breaking 0.7.0 reset. Deprecated compatibility keys were removed instead of preserved, and active settings were sorted into clearer TOML sections. Old configs may be corrected/regenerated; deleting `config/pmweather_aeronautics-common.toml` forces a clean 0.7.0 config.


The mod now moves legacy TOML files containing removed 0.5.x/0.6.x config keys to a `.bak` file on startup, then lets NeoForge generate a fresh sorted 0.7.0 config.
