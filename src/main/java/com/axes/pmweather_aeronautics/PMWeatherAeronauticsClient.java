package com.axes.pmweather_aeronautics;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = PMWeatherAeronautics.MODID, dist = Dist.CLIENT)
public final class PMWeatherAeronauticsClient {
    public PMWeatherAeronauticsClient(final ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new ConfigurationScreen(container, parent));
        NeoForge.EVENT_BUS.addListener(LiveWindMonitor::registerCommands);
        NeoForge.EVENT_BUS.addListener(LiveWindMonitor::registerGuiLayer);
        NeoForge.EVENT_BUS.addListener(LiveWindMonitor::onClientTick);
    }
}
