# PMWeather Aeronautics 1.1

- Adds automatic support for exactly PMWeather 0.16.4 alongside 0.17.x (0.17.0 or later).
- Uses 0.16.4's native wind API and preserves its XYZ output, including vertical tornado wind. No additional Y correction or copied tornado calculation is used.
- Skips newer PMWeather hooks when 0.16.4 is installed, preventing missing-method and missing-target failures.
- Retains the modern wind integration, aerodynamic and wheel behavior, shared `/pmaero` commands, clean default build and separate development trace recorder.
- Retains packed API version 2 and modern wind implementation revision 3 for PMIV. PMIV 0.12.1 accepts PMWeather 0.16.4 through PMAero 1.1; earlier PMIV builds retain their modern-weather restriction.

Install PMWeather and Sable separately. Dependency binaries are not included in PMAero or its source archive.

Clean and development compilation succeeded. In-game loading and weather behavior still require confirmation; this release note does not claim a completed runtime test.
