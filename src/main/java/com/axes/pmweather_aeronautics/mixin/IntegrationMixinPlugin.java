package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.IntegrationHealth;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Reports missed optional hooks once when their target class is actually transformed. */
public final class IntegrationMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        String shortName = mixin.substring(mixin.lastIndexOf('.') + 1);
        if (!shortName.contains("Wheel") && !shortName.contains("Track")
                && !shortName.equals("OffroadTerrainCastResultMixin")
                && !shortName.equals("NativeStormVectorMixin")
                && !shortName.equals("SableBuiltinWindMixin")
                && !shortName.equals("BlockSubLevelLiftProviderMixin")
                && !shortName.equals("SubLevelPhysicsSystemWindBatchMixin")) return;
        Set<String> ownCallbacks = info.getClassNode(0).methods.stream()
                .filter(method -> method.name.contains("pmaero$") || method.name.contains("pmweather_aeronautics$"))
                .map(method -> {
                    int index = method.name.indexOf("pmweather_aeronautics$");
                    if (index < 0) index = method.name.indexOf("pmaero$");
                    return method.name.substring(index);
                }).collect(java.util.stream.Collectors.toSet());
        int totalCalls = 0;
        java.util.List<String> missed = new java.util.ArrayList<>();
        String key = shortName + " -> " + target;
        for (String callback : ownCallbacks) {
            int calls = 0;
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(node.name) && call.name.endsWith(callback)) calls++;
                }
            }
            IntegrationHealth.applied(key + "/" + callback, calls);
            totalCalls += calls;
            if (calls == 0) missed.add(callback);
        }
        if (ownCallbacks.isEmpty() || !missed.isEmpty())
            LogUtils.getLogger().warn("PMAero compatibility hook incomplete: {} missing={}", key, missed);
        else LogUtils.getLogger().info("PMAero compatibility hook applied: {} ({} callsites)", key, totalCalls);

    }
}
