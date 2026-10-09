package com.axes.pmweather_aeronautics;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-only scheduler for bounded particle wind batches. */
@EventBusSubscriber(modid = PMWeatherAeronautics.MODID, value = Dist.CLIENT)
public final class ParticleWindClientEvents {
    private ParticleWindClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ParticleWindClient.flushPendingSamples();
    }
}
