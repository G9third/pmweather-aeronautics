package com.axes.pmweather_aeronautics;

import net.neoforged.fml.loading.LoadingModList;

/** Selects the native weather API from loader metadata before mixins are applied. */
public final class PMWeatherCompatibility {
    private static final String INSTALLED_VERSION = findInstalledVersion();
    private static final boolean LEGACY_0164 = "0.16.4".equals(INSTALLED_VERSION);

    private PMWeatherCompatibility() {}

    public static boolean isLegacy0164() {
        return LEGACY_0164;
    }

    public static String installedVersion() {
        return INSTALLED_VERSION;
    }

    private static String findInstalledVersion() {
        final LoadingModList mods = LoadingModList.get();
        if (mods == null) {
            throw new IllegalStateException("PMAero cannot select a weather API before mod discovery.");
        }
        return mods.getMods().stream()
                .filter(mod -> "pmweather".equals(mod.getModId()))
                .findFirst()
                .map(mod -> mod.getVersion().toString())
                .orElseThrow(() -> new IllegalStateException("PMAero requires ProtoManly's Weather."));
    }
}
