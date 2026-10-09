# Third-party notices

## ProtoManly's Weather

ProtoManly's Weather is a separate runtime dependency by ProtoManly.
Official distribution: https://modrinth.com/mod/protomanlys-weather.
Its 0.17.16 metadata identifies All Rights Reserved. Obtain its JAR separately;
PMAero's MIT license grants no rights to redistribute or relicense PMWeather.

PMAero does not include PMWeather classes, assets, dependency JARs or a reproduction
of its tornado combination formulas. It calls the native wind engine. A scoped
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
