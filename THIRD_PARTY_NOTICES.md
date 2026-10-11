# Third-party notices

## ProtoManly's Weather

ProtoManly's Weather is a separate runtime dependency by ProtoManly.
Official distribution: https://modrinth.com/mod/protomanlys-weather.
The inspected 0.16.4 and 0.17.16 metadata identify All Rights Reserved. Obtain its JAR separately;
PMAero's MIT license grants no rights to redistribute or relicense PMWeather.

PMAero does not include PMWeather classes, assets, dependency JARs or a reproduction
of its tornado combination formulas. For PMWeather 0.16.4, the version-selected adapter
calls its existing native wind API and retains the returned XYZ vector, including Y.
It does not copy or independently reimplement that version's weather calculations.
For PMWeather 0.17.14 through 0.17.16, it calls the native wind engine. A scoped
mixin observes the native supercell vectors already evaluated by that call,
returns them unchanged, and adds only their otherwise discarded Y contribution
to PMAero's result. Native horizontal wind and existing fire-whirl Y stay native.
The mixin does not alter wind returned to unrelated PMWeather consumers.

## Synthetic weather test

The operator-controlled test uses addon-defined analytic fields: prescribed gusts,
shear, radial circulation, vertical envelopes and phase transitions. It does not
instantiate native storms, copy native tornado profiles, or change PMWeather's weather.
Native weather sampling is bypassed only at points inside the active test field.
This documents the current technical design and source provenance; it does not
certify every historical authorship or distribution circumstance.

## Sable and optional wheel integrations

Sable, Create Aeronautics, Offroad, Create Tracks and No Horizon are separate mods
with their own licenses. PMAero observes their exposed poses, velocities and contact
impulses. Its Offroad visual hook reuses the native per-wheel rolling angle and
does not bundle an implementation of the wheel physics. These dependencies and
content-pack assets are excluded from the public source archive.

- **Sable 2.0.5**, RyanHCode and contributors: PolyForm Shield License 1.0.0,
  including its noncompete condition.
  https://github.com/ryanhcode/sable/blob/main/LICENSE.md
  PMAero requires the separately installed original Sable and extends weather
  integration through its APIs. It does not distribute a substitute for Sable
  or copy its physics/player-collision implementation or native libraries.
- **Sable Companion 1.6.0**, RyanHCode and contributors: MIT, identified in its
  separate JAR. That JAR and its classes are not bundled.
- **Aeronautics, Simulated and Offroad**, The Simulated Team / The Creators of
  Aeronautics: MIT code and All Rights Reserved designated assets.
  https://github.com/Creators-of-Aeronautics/Simulated-Project/blob/main/LICENSE.md
  Native wheel and lift implementations remain in their independently installed
  mods. PMAero's hooks observe native calls and supply weather inputs.
- **Create: Tracks**, qwxon: MIT as identified on its official project page:
  https://modrinth.com/mod/create-tracks. Its wheel/track code and assets are not
  bundled by PMAero.
- **Aeronautics: No Horizon**, MaxnessAWA: All Rights Reserved unless otherwise
  stated on its official license page:
  https://www.curseforge.com/minecraft/mc-mods/aeronautics-no-horizon/license.
  The optional adapter observes native final impulse calls; it does not include
  the mod's wheel/track implementation, models or textures.

PMAero's MIT license covers PMAero's own code. It does not relicense dependency
code/assets or authorize redistributing third-party mod JARs.

## Build tooling

The public source archive includes the Gradle wrapper, a build tool from the
Gradle project under Apache License 2.0. The matching license text is retained
in `gradle/wrapper/LICENSE`. The wrapper is not bundled in the mod JAR.
https://www.apache.org/licenses/LICENSE-2.0
