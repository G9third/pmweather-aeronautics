package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds one same-tick wind request frame per Sable sub-level.
 *
 * Body exterior patches and lift-provider airflow share one query budget and are resolved in one
 * WeatherWindField batch. Lift providers are never removed from Sable's physics calculation: only
 * their PMWeather probes are regionized. Every provider is mapped to one component-local probe.
 *
 * The regionizer is generic. It uses connected lift-provider components and block-state/provider
 * signatures rather than aircraft names, so wings, control surfaces and third-party lift blocks can
 * all reduce redundant PMWeather queries. Balloon/envelope providers use the same adaptive budget
 * but probe just outside the object's bounds rather than inside the envelope.
 */
final class PhysicsTickWindBatch {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<String, FrameState> FRAMES = new HashMap<>();
    private static final Set<String> ACTIVE_SUBLEVELS_THIS_TICK = new HashSet<>();
    private static long activeTick = Long.MIN_VALUE;
    private static int previousTickActiveSubLevels = 1;
    private static long lastPruneTick = Long.MIN_VALUE;
    private static final double BALLOON_PROBE_MARGIN = 0.75D;

    private PhysicsTickWindBatch() {
    }

    static void begin(final SubLevelPhysicsSystem physicsSystem, final ServerSubLevel subLevel) {
        if (subLevel == null) {
            return;
        }
        final long tick = subLevel.getLevel().getGameTime();
        rollActiveTick(tick);
        final String subLevelKey = key(subLevel);
        ACTIVE_SUBLEVELS_THIS_TICK.add(subLevelKey);

        final FrameState existing = FRAMES.get(subLevelKey);
        if (existing != null && existing.tick == tick) {
            return;
        }

        final long collectionStarted = AeronauticsProfilerMetrics.timerStart();
        final FrameState frame = new FrameState(tick);
        if (Config.enableAirflowLift()) {
            int sourceId = 0;
            collectSource(frame, subLevel, null, sourceId++, subLevel.getPlot().getLiftProviders());
            if (!subLevel.getPlot().getContraptions().isEmpty()) {
                final Pose3d localPose = new Pose3d();
                for (final KinematicContraption contraption : subLevel.getPlot().getContraptions()) {
                    contraption.sable$getLocalPose(localPose, physicsSystem.getPartialPhysicsTick());
                    collectSource(frame, subLevel, localPose, sourceId++, contraption.sable$liftProviders().values());
                }
            }
            frame.components.addAll(buildComponents(frame.providerNodes));
            AeronauticsProfilerMetrics.addAirflowComponents(frame.components.size());
        }
        FRAMES.put(subLevelKey, frame);
        prune(tick);
        AeronauticsProfilerMetrics.recordCollectionTime(collectionStarted);
    }

    /**
     * Plans a fair body/airflow split for this sub-level. The returned body count is exterior body
     * patches only; the caller may additionally use its center fallback probe.
     */
    static Plan plan(final ServerSubLevel subLevel,
                     final int desiredBodyExterior,
                     final int minimumBodyExterior,
                     final boolean reserveBodyCenter) {
        final FrameState frame = currentFrame(subLevel);
        if (frame == null) {
            return new Plan(Math.max(0, desiredBodyExterior), List.of());
        }
        if (frame.planned) {
            return new Plan(frame.bodyExteriorTarget, List.copyOf(frame.requests.values()));
        }

        final int desiredBody = Math.max(0, desiredBodyExterior) + (reserveBodyCenter ? 1 : 0);
        final int minimumBody = Math.min(desiredBody,
                Math.max(0, minimumBodyExterior) + (reserveBodyCenter ? 1 : 0));

        final int providerCount = frame.providerNodes.size();
        final int componentCount = frame.components.size();
        final int regionEdge = Math.max(1, Config.airflowRegionEdgeBlocks());
        final int fineRegions = fineRegionCount(frame.components, regionEdge);
        final int configuredAirflowMax = Math.max(0, Config.maxAirflowSamplesPerObject());
        final int perComponentMinimum = Math.max(1, Config.minAirflowSamplesPerComponent());
        final int componentMinimumTarget = providerCount <= 0 ? 0 : Math.min(
                providerCount,
                componentCount * perComponentMinimum
        );
        // Keep airflow-region LOD from becoming too coarse on ordinary aircraft. The region grid
        // is still allowed to merge nearby providers, but by default it may not reduce the desired
        // spatial probe count below half of the discovered providers unless the global hard budget
        // itself requires a lower allocation. This preserves more spanwise/tail differential wind
        // while retaining the query-sharing benefit.
        final int ratioMinimumTarget = providerCount <= 0 ? 0 : Math.min(
                providerCount,
                (int) Math.ceil(providerCount * Math.max(0.0D, Math.min(1.0D, Config.minAirflowSampleRatio())))
        );
        final int desiredAirflow = providerCount <= 0 ? 0 : Math.min(
                providerCount,
                Math.max(
                        Math.max(componentMinimumTarget, ratioMinimumTarget),
                        Math.min(fineRegions, configuredAirflowMax)
                )
        );
        final int minimumAirflow = Math.min(desiredAirflow, componentMinimumTarget);

        final int hardBudget = Math.max(1, Config.maxWindSamplesPerTick());
        final int activeEstimate = Math.max(1, Math.max(previousTickActiveSubLevels, ACTIVE_SUBLEVELS_THIS_TICK.size()));
        final int fairShare = Math.max(1, hardBudget / activeEstimate);

        final Allocation allocation = allocate(
                fairShare,
                desiredBody,
                minimumBody,
                desiredAirflow,
                minimumAirflow
        );

        int bodyExteriorTarget = Math.max(0, allocation.body() - (reserveBodyCenter ? 1 : 0));
        if (bodyExteriorTarget > minimumBodyExterior && (bodyExteriorTarget & 1) != 0) {
            bodyExteriorTarget--;
        }
        frame.bodyExteriorTarget = Math.min(Math.max(0, desiredBodyExterior), bodyExteriorTarget);

        final int airflowTarget = Math.min(desiredAirflow, Math.max(0, allocation.airflow()));
        buildAdaptiveProviderRequests(frame, subLevel, airflowTarget);
        AeronauticsProfilerMetrics.recordAllocation(
                desiredBodyExterior,
                frame.bodyExteriorTarget,
                providerCount,
                desiredAirflow,
                airflowTarget
        );
        frame.planned = true;

        return new Plan(frame.bodyExteriorTarget, List.copyOf(frame.requests.values()));
    }

    static Plan planProviderOnly(final ServerSubLevel subLevel) {
        return plan(subLevel, 0, 0, false);
    }

    static void publishProviderWinds(final ServerSubLevel subLevel,
                                     final List<PendingRequest> requests,
                                     final List<Vec3> winds) {
        final FrameState frame = currentFrame(subLevel);
        if (frame == null) {
            return;
        }
        final int count = Math.min(requests.size(), winds.size());
        for (int i = 0; i < count; i++) {
            frame.requestWinds.put(requests.get(i).requestId(), winds.get(i));
        }
        frame.resolved = true;
    }

    static void ensureResolved(final ServerSubLevel subLevel) {
        final FrameState frame = currentFrame(subLevel);
        if (frame == null || frame.resolved) {
            return;
        }
        final Plan plan = frame.planned
                ? new Plan(frame.bodyExteriorTarget, List.copyOf(frame.requests.values()))
                : planProviderOnly(subLevel);
        if (plan.providerRequests().isEmpty()) {
            frame.resolved = true;
            return;
        }
        AeronauticsProfilerMetrics.recordProviderOnlyBatch();
        final List<Vec3> winds = WeatherWindField.samplePendingProviderWindBatch(subLevel, plan.providerRequests());
        publishProviderWinds(subLevel, plan.providerRequests(), winds);
    }

    @Nullable
    static Vec3 windForProvider(final BlockSubLevelLiftProvider provider,
                                final BlockSubLevelLiftProvider.LiftProviderContext context,
                                final ServerSubLevel subLevel,
                                @Nullable final Pose3d localPose,
                                final Vec3 samplePosition) {
        final FrameState frame = currentFrame(subLevel);
        if (frame == null) {
            return null;
        }

        // Sable may recreate LiftProviderContext objects between collection and force application,
        // and kinematic local poses can advance between the collection pass and the actual provider
        // callback.  Therefore the primary fallback key must NOT contain transformed/localPose or
        // world-space coordinates.  The provider's source BlockPos + provider signature remain stable
        // across those physics substeps.  If multiple contraptions happen to expose the same source
        // position/signature, choose the collected assignment whose world position is nearest to the
        // provider's current world position.
        Long requestId = frame.providerRequestIdsByContext.get(context);
        if (requestId == null) {
            requestId = resolveInvocationAssignment(
                    frame.providerAssignmentsByInvocationKey.get(providerInvocationKey(provider, context)),
                    samplePosition
            );
        }
        if (requestId == null) {
            // Some Sable/Aeronautics paths expose the provider context in a different coordinate
            // frame at callback time.  The provider/block signature is still stable.  Resolve the
            // nearest collected member of the same provider type before considering a legacy raw
            // query.  This keeps all normal providers inside the regional batch even when their
            // context object or source BlockPos representation changes between physics substeps.
            requestId = resolveInvocationAssignment(
                    frame.providerAssignmentsBySignature.get(providerLookupSignature(provider, context)),
                    samplePosition
            );
        }
        if (requestId == null) {
            // Exact collected world-position compatibility path.
            requestId = frame.providerRequestIds.get(PositionKey.of(samplePosition));
        }
        if (requestId == null) {
            // Last authoritative regional mapping for providers belonging to this Sable sub-level.
            // Sable/Create Aeronautics can recreate both context objects and provider wrappers, and
            // some kinematic paths expose the callback through a coordinate/provider representation
            // that does not match collection-time metadata.  At that point the provider is still a
            // member of this sub-level's already-collected lift-provider set, so map it to the nearest
            // collected member rather than escaping the global sample budget with a fresh PMWeather
            // query.  The collected member already points at its allocated component-local region.
            requestId = resolveInvocationAssignment(frame.allProviderAssignments, samplePosition);
        }
        return requestId == null ? null : frame.requestWinds.get(requestId);
    }

    static boolean wasCollectedProvider(final BlockSubLevelLiftProvider provider,
                                        final BlockSubLevelLiftProvider.LiftProviderContext context,
                                        final ServerSubLevel subLevel,
                                        @Nullable final Pose3d localPose,
                                        final Vec3 samplePosition) {
        final FrameState frame = currentFrame(subLevel);
        return frame != null && (frame.collectedProviderContexts.contains(context)
                || frame.collectedProviderInvocationKeys.contains(providerInvocationKey(provider, context))
                || frame.collectedProviderSignatures.contains(providerLookupSignature(provider, context))
                || frame.collectedProviderPositions.contains(PositionKey.of(samplePosition))
                // Once this frame has collected lift providers, an otherwise-unmatched callback for
                // the same ServerSubLevel must not bypass the authoritative regional budget.
                || (frame.planned && !frame.providerNodes.isEmpty()));
    }

    static boolean isBalloonProvider(final BlockSubLevelLiftProvider provider,
                                     final BlockSubLevelLiftProvider.LiftProviderContext context) {
        return isBalloon(provider, context);
    }

    private static void collectSource(final FrameState frame,
                                      final ServerSubLevel subLevel,
                                      @Nullable final Pose3d localPose,
                                      final int sourceId,
                                      final Collection<BlockSubLevelLiftProvider.LiftProviderContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return;
        }
        for (final BlockSubLevelLiftProvider.LiftProviderContext context : contexts) {
            if (!(context.state().getBlock() instanceof BlockSubLevelLiftProvider provider)) {
                continue;
            }

            final Vector3d subLevelLocal = new Vector3d(
                    context.pos().getX() + 0.5D,
                    context.pos().getY() + 0.5D,
                    context.pos().getZ() + 0.5D
            );
            if (localPose != null) {
                localPose.transformPosition(subLevelLocal);
            }
            final Vector3d world = new Vector3d(subLevelLocal);
            subLevel.logicalPose().transformPosition(world);
            final Vec3 worldPosition = new Vec3(world.x, world.y, world.z);
            final PositionKey worldKey = PositionKey.of(worldPosition);
            final boolean balloon = isBalloon(provider, context);
            final String signature = balloon ? "balloon" : providerSignature(provider, context);
            final String lookupSignature = balloon ? "balloon" : providerIdentitySignature(provider, context);
            final ProviderInvocationKey invocationKey = new ProviderInvocationKey(
                    context.pos().asLong(),
                    lookupSignature
            );
            frame.collectedProviderContexts.add(context);
            frame.collectedProviderInvocationKeys.add(invocationKey);
            frame.collectedProviderSignatures.add(lookupSignature);
            frame.collectedProviderPositions.add(worldKey);
            frame.providerNodes.add(new ProviderNode(
                    sourceId,
                    context,
                    context.pos().immutable(),
                    new Vec3(subLevelLocal.x, subLevelLocal.y, subLevelLocal.z),
                    worldPosition,
                    worldKey,
                    invocationKey,
                    balloon,
                    signature,
                    lookupSignature
            ));
            if (!balloon) {
                AeronauticsProfilerMetrics.addNonBalloonProvider();
            }
        }
    }

    private static List<ProviderComponent> buildComponents(final List<ProviderNode> nodes) {
        if (nodes.isEmpty()) {
            return List.of();
        }
        final Map<ComponentKey, Map<BlockPos, ProviderNode>> groups = new LinkedHashMap<>();
        for (final ProviderNode node : nodes) {
            groups.computeIfAbsent(
                    new ComponentKey(node.sourceId(), node.balloon(), node.signature()),
                    ignored -> new HashMap<>()
            ).put(node.sourcePosition(), node);
        }

        final List<ProviderComponent> result = new ArrayList<>();
        int componentId = 0;
        for (final Map.Entry<ComponentKey, Map<BlockPos, ProviderNode>> groupEntry : groups.entrySet()) {
            final Map<BlockPos, ProviderNode> byPosition = groupEntry.getValue();
            final Set<BlockPos> remaining = new HashSet<>(byPosition.keySet());
            while (!remaining.isEmpty()) {
                final BlockPos seed = remaining.stream().min(POSITION_ORDER).orElseThrow();
                final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                final List<ProviderNode> componentNodes = new ArrayList<>();
                queue.add(seed);
                remaining.remove(seed);
                while (!queue.isEmpty()) {
                    final BlockPos position = queue.removeFirst();
                    final ProviderNode node = byPosition.get(position);
                    if (node != null) {
                        componentNodes.add(node);
                    }
                    for (final Direction direction : DIRECTIONS) {
                        final BlockPos adjacent = position.relative(direction);
                        if (remaining.remove(adjacent)) {
                            queue.addLast(adjacent);
                        }
                    }
                }
                componentNodes.sort(NODE_ORDER);
                result.add(new ProviderComponent(componentId++, groupEntry.getKey(), componentNodes));
            }
        }
        result.sort(Comparator.comparingInt(ProviderComponent::id));
        return result;
    }

    private static int fineRegionCount(final List<ProviderComponent> components, final int edge) {
        int count = 0;
        for (final ProviderComponent component : components) {
            if (component.nodes().isEmpty()) {
                continue;
            }
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (final ProviderNode node : component.nodes()) {
                minX = Math.min(minX, node.sourcePosition().getX());
                minY = Math.min(minY, node.sourcePosition().getY());
                minZ = Math.min(minZ, node.sourcePosition().getZ());
            }
            final Set<RegionKey> regions = new HashSet<>();
            for (final ProviderNode node : component.nodes()) {
                regions.add(new RegionKey(
                        Math.floorDiv(node.sourcePosition().getX() - minX, edge),
                        Math.floorDiv(node.sourcePosition().getY() - minY, edge),
                        Math.floorDiv(node.sourcePosition().getZ() - minZ, edge)
                ));
            }
            count += Math.max(1, regions.size());
        }
        return count;
    }

    private static Allocation allocate(final int budget,
                                       final int desiredBody,
                                       final int minimumBody,
                                       final int desiredAirflow,
                                       final int minimumAirflow) {
        int remaining = Math.max(0, budget);
        int body = 0;
        int airflow = 0;

        if (desiredBody > 0 && remaining > 0) {
            body++;
            remaining--;
        }
        if (desiredAirflow > 0 && remaining > 0) {
            airflow++;
            remaining--;
        }

        while (remaining > 0 && (body < minimumBody || airflow < minimumAirflow)) {
            final double bodyDeficit = body < minimumBody
                    ? (minimumBody - body) / (double) Math.max(1, minimumBody)
                    : -1.0D;
            final double airflowDeficit = airflow < minimumAirflow
                    ? (minimumAirflow - airflow) / (double) Math.max(1, minimumAirflow)
                    : -1.0D;
            if (bodyDeficit >= airflowDeficit && body < minimumBody) {
                body++;
            } else if (airflow < minimumAirflow) {
                airflow++;
            } else {
                break;
            }
            remaining--;
        }

        while (remaining > 0 && (body < desiredBody || airflow < desiredAirflow)) {
            final double bodyDeficit = body < desiredBody
                    ? (desiredBody - body) / (double) Math.max(1, desiredBody)
                    : -1.0D;
            final double airflowDeficit = airflow < desiredAirflow
                    ? (desiredAirflow - airflow) / (double) Math.max(1, desiredAirflow)
                    : -1.0D;
            if (bodyDeficit >= airflowDeficit && body < desiredBody) {
                body++;
            } else if (airflow < desiredAirflow) {
                airflow++;
            } else {
                break;
            }
            remaining--;
        }
        return new Allocation(body, airflow);
    }

    private static void buildAdaptiveProviderRequests(final FrameState frame,
                                                      final ServerSubLevel subLevel,
                                                      final int target) {
        frame.requests.clear();
        frame.providerRequestIdsByContext.clear();
        frame.providerAssignmentsByInvocationKey.clear();
        frame.providerAssignmentsBySignature.clear();
        frame.allProviderAssignments.clear();
        frame.providerRequestIds.clear();
        if (target <= 0 || frame.components.isEmpty()) {
            return;
        }

        final Map<Integer, Integer> allocations = allocateAcrossComponents(
                frame.components,
                target,
                Math.max(1, Config.minAirflowSamplesPerComponent())
        );
        for (final ProviderComponent component : frame.components) {
            final int probes = allocations.getOrDefault(component.id(), 0);
            if (probes <= 0) {
                continue;
            }
            final List<ProviderCluster> clusters = cluster(component.nodes(), probes);
            int clusterIndex = 0;
            for (final ProviderCluster cluster : clusters) {
                final ProviderNode seed = cluster.seed();
                final Vec3 probePosition;
                if (component.key().balloon()) {
                    final Vec3 localProbe = exteriorProbe(cluster.localCentroid(), localBounds(component.nodes()));
                    final Vector3d world = new Vector3d(localProbe.x, localProbe.y, localProbe.z);
                    subLevel.logicalPose().transformPosition(world);
                    probePosition = new Vec3(world.x, world.y, world.z);
                    AeronauticsProfilerMetrics.addBalloonRegion(cluster.members().size());
                } else {
                    probePosition = seed.worldPosition();
                }

                AeronauticsProfilerMetrics.addAirflowRegion(cluster.members().size());
                final long requestId = frame.nextRequestId++;
                final int role = stableRegionRole(component, clusterIndex++, seed.sourcePosition(), probePosition);
                frame.requests.put(requestId, new PendingRequest(requestId, probePosition, role));
                for (final ProviderNode member : cluster.members()) {
                    frame.providerRequestIdsByContext.put(member.context(), requestId);
                    final ProviderAssignment assignment = new ProviderAssignment(requestId, member.worldPosition());
                    frame.providerAssignmentsByInvocationKey
                            .computeIfAbsent(member.invocationKey(), ignored -> new ArrayList<>())
                            .add(assignment);
                    frame.providerAssignmentsBySignature
                            .computeIfAbsent(member.lookupSignature(), ignored -> new ArrayList<>())
                            .add(assignment);
                    frame.allProviderAssignments.add(assignment);
                    frame.providerRequestIds.put(member.providerKey(), requestId);
                }
            }
        }
    }

    private static Map<Integer, Integer> allocateAcrossComponents(final List<ProviderComponent> components,
                                                                  final int target,
                                                                  final int minimumPerComponent) {
        final Map<Integer, Integer> allocation = new HashMap<>();
        if (target <= 0 || components.isEmpty()) {
            return allocation;
        }
        final List<ProviderComponent> ordered = new ArrayList<>(components);
        ordered.sort(Comparator
                .comparingInt((ProviderComponent component) -> -component.nodes().size())
                .thenComparingInt(ProviderComponent::id));

        int remaining = target;
        final int minimumRounds = Math.max(1, minimumPerComponent);
        for (int round = 0; round < minimumRounds && remaining > 0; round++) {
            for (final ProviderComponent component : ordered) {
                if (remaining <= 0) {
                    break;
                }
                final int current = allocation.getOrDefault(component.id(), 0);
                if (current >= component.nodes().size()) {
                    continue;
                }
                allocation.put(component.id(), current + 1);
                remaining--;
            }
        }

        final int totalNodes = ordered.stream().mapToInt(component -> component.nodes().size()).sum();
        while (remaining > 0) {
            ProviderComponent best = null;
            double bestDeficit = Double.NEGATIVE_INFINITY;
            for (final ProviderComponent component : ordered) {
                final int current = allocation.getOrDefault(component.id(), 0);
                if (current >= component.nodes().size()) {
                    continue;
                }
                final double ideal = target * component.nodes().size() / (double) Math.max(1, totalNodes);
                final double deficit = ideal - current;
                if (deficit > bestDeficit) {
                    bestDeficit = deficit;
                    best = component;
                }
            }
            if (best == null) {
                break;
            }
            allocation.put(best.id(), allocation.getOrDefault(best.id(), 0) + 1);
            remaining--;
        }
        return allocation;
    }

    private static List<ProviderCluster> cluster(final List<ProviderNode> nodes, final int requested) {
        if (nodes.isEmpty() || requested <= 0) {
            return List.of();
        }
        final int count = Math.min(nodes.size(), requested);
        if (count >= nodes.size()) {
            final List<ProviderCluster> exact = new ArrayList<>(nodes.size());
            for (final ProviderNode node : nodes) {
                exact.add(new ProviderCluster(node, new ArrayList<>(List.of(node))));
            }
            return exact;
        }

        final List<ProviderNode> seeds = new ArrayList<>(count);
        seeds.add(medoid(nodes));
        while (seeds.size() < count) {
            ProviderNode best = null;
            double bestDistance = Double.NEGATIVE_INFINITY;
            for (final ProviderNode candidate : nodes) {
                if (seeds.contains(candidate)) {
                    continue;
                }
                double nearest = Double.POSITIVE_INFINITY;
                for (final ProviderNode seed : seeds) {
                    nearest = Math.min(nearest, distanceSquared(candidate.subLevelLocalCenter(), seed.subLevelLocalCenter()));
                }
                if (nearest > bestDistance + 1.0e-12D
                        || (Math.abs(nearest - bestDistance) <= 1.0e-12D && (best == null || NODE_ORDER.compare(candidate, best) < 0))) {
                    bestDistance = nearest;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            seeds.add(best);
        }

        final List<ProviderCluster> clusters = new ArrayList<>(seeds.size());
        for (final ProviderNode seed : seeds) {
            clusters.add(new ProviderCluster(seed, new ArrayList<>()));
        }
        for (final ProviderNode node : nodes) {
            int bestIndex = 0;
            double bestDistance = distanceSquared(node.subLevelLocalCenter(), seeds.get(0).subLevelLocalCenter());
            for (int i = 1; i < seeds.size(); i++) {
                final double distance = distanceSquared(node.subLevelLocalCenter(), seeds.get(i).subLevelLocalCenter());
                if (distance < bestDistance - 1.0e-12D) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
            clusters.get(bestIndex).members().add(node);
        }
        return clusters;
    }

    private static ProviderNode medoid(final List<ProviderNode> nodes) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (final ProviderNode node : nodes) {
            x += node.subLevelLocalCenter().x;
            y += node.subLevelLocalCenter().y;
            z += node.subLevelLocalCenter().z;
        }
        final double count = Math.max(1, nodes.size());
        final Vec3 centroid = new Vec3(x / count, y / count, z / count);
        ProviderNode best = nodes.get(0);
        double bestDistance = distanceSquared(best.subLevelLocalCenter(), centroid);
        for (int i = 1; i < nodes.size(); i++) {
            final ProviderNode candidate = nodes.get(i);
            final double distance = distanceSquared(candidate.subLevelLocalCenter(), centroid);
            if (distance < bestDistance - 1.0e-12D
                    || (Math.abs(distance - bestDistance) <= 1.0e-12D && NODE_ORDER.compare(candidate, best) < 0)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Vec3 exteriorProbe(final Vec3 centroid, final LocalBounds bounds) {
        final double west = bounds.minX() - BALLOON_PROBE_MARGIN;
        final double east = bounds.maxX() + 1.0D + BALLOON_PROBE_MARGIN;
        final double bottom = bounds.minY() - BALLOON_PROBE_MARGIN;
        final double top = bounds.maxY() + 1.0D + BALLOON_PROBE_MARGIN;
        final double north = bounds.minZ() - BALLOON_PROBE_MARGIN;
        final double south = bounds.maxZ() + 1.0D + BALLOON_PROBE_MARGIN;
        final double x = clamp(centroid.x, bounds.minX() + 0.5D, bounds.maxX() + 0.5D);
        final double y = clamp(centroid.y, bounds.minY() + 0.5D, bounds.maxY() + 0.5D);
        final double z = clamp(centroid.z, bounds.minZ() + 0.5D, bounds.maxZ() + 0.5D);

        double bestDistance = Math.abs(x - west);
        Vec3 best = new Vec3(west, y, z);
        final double eastDistance = Math.abs(east - x);
        if (eastDistance < bestDistance) {
            bestDistance = eastDistance;
            best = new Vec3(east, y, z);
        }
        final double bottomDistance = Math.abs(y - bottom);
        if (bottomDistance < bestDistance) {
            bestDistance = bottomDistance;
            best = new Vec3(x, bottom, z);
        }
        final double topDistance = Math.abs(top - y);
        if (topDistance < bestDistance) {
            bestDistance = topDistance;
            best = new Vec3(x, top, z);
        }
        final double northDistance = Math.abs(z - north);
        if (northDistance < bestDistance) {
            bestDistance = northDistance;
            best = new Vec3(x, y, north);
        }
        final double southDistance = Math.abs(south - z);
        if (southDistance < bestDistance) {
            best = new Vec3(x, y, south);
        }
        return best;
    }

    private static LocalBounds localBounds(final List<ProviderNode> nodes) {
        if (nodes.isEmpty()) {
            return new LocalBounds(0, 0, 0, 0, 0, 0);
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (final ProviderNode node : nodes) {
            minX = Math.min(minX, node.subLevelLocalCenter().x);
            minY = Math.min(minY, node.subLevelLocalCenter().y);
            minZ = Math.min(minZ, node.subLevelLocalCenter().z);
            maxX = Math.max(maxX, node.subLevelLocalCenter().x);
            maxY = Math.max(maxY, node.subLevelLocalCenter().y);
            maxZ = Math.max(maxZ, node.subLevelLocalCenter().z);
        }
        return new LocalBounds(
                (int) Math.floor(minX - 0.5D),
                (int) Math.floor(minY - 0.5D),
                (int) Math.floor(minZ - 0.5D),
                (int) Math.floor(maxX - 0.5D),
                (int) Math.floor(maxY - 0.5D),
                (int) Math.floor(maxZ - 0.5D)
        );
    }

    private static String providerLookupSignature(
            final BlockSubLevelLiftProvider provider,
            final BlockSubLevelLiftProvider.LiftProviderContext context
    ) {
        return isBalloon(provider, context) ? "balloon" : providerIdentitySignature(provider, context);
    }

    private static ProviderInvocationKey providerInvocationKey(
            final BlockSubLevelLiftProvider provider,
            final BlockSubLevelLiftProvider.LiftProviderContext context
    ) {
        return new ProviderInvocationKey(context.pos().asLong(), providerLookupSignature(provider, context));
    }

    @Nullable
    private static Long resolveInvocationAssignment(@Nullable final List<ProviderAssignment> assignments,
                                                    final Vec3 currentWorldPosition) {
        if (assignments == null || assignments.isEmpty()) {
            return null;
        }
        long requestId = assignments.get(0).requestId();
        boolean oneRequest = true;
        for (int i = 1; i < assignments.size(); i++) {
            if (assignments.get(i).requestId() != requestId) {
                oneRequest = false;
                break;
            }
        }
        if (oneRequest) {
            return requestId;
        }

        ProviderAssignment best = assignments.get(0);
        double bestDistance = distanceSquared(best.collectedWorldPosition(), currentWorldPosition);
        for (int i = 1; i < assignments.size(); i++) {
            final ProviderAssignment candidate = assignments.get(i);
            final double distance = distanceSquared(candidate.collectedWorldPosition(), currentWorldPosition);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best.requestId();
    }

    private static String providerIdentitySignature(final BlockSubLevelLiftProvider provider,
                                                    final BlockSubLevelLiftProvider.LiftProviderContext context) {
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(context.state().getBlock());
        return provider.getClass().getName() + '|' + (id == null ? "unknown" : id.toString());
    }

    private static String providerSignature(final BlockSubLevelLiftProvider provider,
                                            final BlockSubLevelLiftProvider.LiftProviderContext context) {
        return providerIdentitySignature(provider, context) + '|' + context.state();
    }

    private static boolean isBalloon(final BlockSubLevelLiftProvider provider,
                                     final BlockSubLevelLiftProvider.LiftProviderContext context) {
        final String className = provider.getClass().getName().toLowerCase(Locale.ROOT);
        if (containsBalloonWord(className)) {
            return true;
        }
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(context.state().getBlock());
        return id != null && containsBalloonWord(id.getPath().toLowerCase(Locale.ROOT));
    }

    private static boolean containsBalloonWord(final String value) {
        return value.contains("balloon") || value.contains("envelope") || value.contains("buoy");
    }

    private static int stableRegionRole(final ProviderComponent component,
                                        final int clusterIndex,
                                        final BlockPos seedPosition,
                                        final Vec3 probePosition) {
        int hash = 0x55d3014b;
        hash = 31 * hash + component.key().sourceId();
        hash = 31 * hash + component.id();
        hash = 31 * hash + clusterIndex;
        hash = 31 * hash + seedPosition.getX();
        hash = 31 * hash + seedPosition.getY();
        hash = 31 * hash + seedPosition.getZ();
        hash = 31 * hash + Long.hashCode(Math.round(probePosition.x * 1_000_000.0D));
        hash = 31 * hash + Long.hashCode(Math.round(probePosition.y * 1_000_000.0D));
        hash = 31 * hash + Long.hashCode(Math.round(probePosition.z * 1_000_000.0D));
        return 0x40000000 | (hash & 0x0fffffff);
    }

    private static double distanceSquared(final Vec3 a, final Vec3 b) {
        final double dx = a.x - b.x;
        final double dy = a.y - b.y;
        final double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void rollActiveTick(final long tick) {
        if (activeTick == tick) {
            return;
        }
        if (activeTick != Long.MIN_VALUE) {
            previousTickActiveSubLevels = Math.max(1, ACTIVE_SUBLEVELS_THIS_TICK.size());
        }
        ACTIVE_SUBLEVELS_THIS_TICK.clear();
        activeTick = tick;
    }

    @Nullable
    private static FrameState currentFrame(final ServerSubLevel subLevel) {
        if (subLevel == null) {
            return null;
        }
        final FrameState frame = FRAMES.get(key(subLevel));
        return frame != null && frame.tick == subLevel.getLevel().getGameTime() ? frame : null;
    }

    private static String key(final ServerSubLevel subLevel) {
        return String.valueOf(subLevel.getUniqueId());
    }

    private static void prune(final long tick) {
        if (lastPruneTick == tick || tick % 200L != 0L) {
            return;
        }
        lastPruneTick = tick;
        FRAMES.entrySet().removeIf(entry -> tick - entry.getValue().tick > 20L);
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    record PendingRequest(long requestId, Vec3 samplePosition, int cacheRole) {
    }

    record Plan(int bodyExteriorSamples, List<PendingRequest> providerRequests) {
    }

    private record Allocation(int body, int airflow) {
    }

    private record ComponentKey(int sourceId, boolean balloon, String signature) {
    }

    private record RegionKey(int x, int y, int z) {
    }

    private record LocalBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    private record ProviderInvocationKey(long sourcePosition, String signature) {
    }

    private record ProviderAssignment(long requestId, Vec3 collectedWorldPosition) {
    }

    private record ProviderNode(int sourceId,
                                BlockSubLevelLiftProvider.LiftProviderContext context,
                                BlockPos sourcePosition,
                                Vec3 subLevelLocalCenter,
                                Vec3 worldPosition,
                                PositionKey providerKey,
                                ProviderInvocationKey invocationKey,
                                boolean balloon,
                                String signature,
                                String lookupSignature) {
    }

    private record ProviderComponent(int id, ComponentKey key, List<ProviderNode> nodes) {
    }

    private record ProviderCluster(ProviderNode seed, List<ProviderNode> members) {
        Vec3 localCentroid() {
            double x = 0.0D;
            double y = 0.0D;
            double z = 0.0D;
            for (final ProviderNode member : this.members) {
                x += member.subLevelLocalCenter().x;
                y += member.subLevelLocalCenter().y;
                z += member.subLevelLocalCenter().z;
            }
            final double count = Math.max(1, this.members.size());
            return new Vec3(x / count, y / count, z / count);
        }
    }

    private record PositionKey(long x, long y, long z) {
        static PositionKey of(final Vec3 position) {
            return new PositionKey(
                    Math.round(position.x * 1_000_000.0D),
                    Math.round(position.y * 1_000_000.0D),
                    Math.round(position.z * 1_000_000.0D)
            );
        }
    }

    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(pos -> pos.getY())
            .thenComparingInt(pos -> pos.getZ());

    private static final Comparator<ProviderNode> NODE_ORDER = Comparator
            .comparingInt((ProviderNode node) -> node.sourcePosition().getX())
            .thenComparingInt(node -> node.sourcePosition().getY())
            .thenComparingInt(node -> node.sourcePosition().getZ());

    private static final class FrameState {
        final long tick;
        final List<ProviderNode> providerNodes = new ArrayList<>();
        final List<ProviderComponent> components = new ArrayList<>();
        final Map<Long, PendingRequest> requests = new LinkedHashMap<>();
        final Map<BlockSubLevelLiftProvider.LiftProviderContext, Long> providerRequestIdsByContext = new IdentityHashMap<>();
        final Map<ProviderInvocationKey, List<ProviderAssignment>> providerAssignmentsByInvocationKey = new HashMap<>();
        final Map<String, List<ProviderAssignment>> providerAssignmentsBySignature = new HashMap<>();
        final List<ProviderAssignment> allProviderAssignments = new ArrayList<>();
        final Map<PositionKey, Long> providerRequestIds = new HashMap<>();
        final Set<BlockSubLevelLiftProvider.LiftProviderContext> collectedProviderContexts = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ProviderInvocationKey> collectedProviderInvocationKeys = new HashSet<>();
        final Set<String> collectedProviderSignatures = new HashSet<>();
        final Set<PositionKey> collectedProviderPositions = new HashSet<>();
        final Map<Long, Vec3> requestWinds = new HashMap<>();
        long nextRequestId = 1L;
        int bodyExteriorTarget;
        boolean planned;
        boolean resolved;

        FrameState(final long tick) {
            this.tick = tick;
        }
    }
}
