/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

package org.eclipse.cyclonedds.omg;

import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;

/** Immutable supported policies and QoS views implementing the unmodified OMG interfaces. */
final class Policies {
    private Policies() {}
    static UnsupportedOperationException unsupported(String feature) {
        return new UnsupportedOperationException(feature + " is not implemented by the Cyclone DDS Java provider");
    }
    static final class Factory extends PolicyFactory {
        private final ServiceEnvironment env;
        Factory(ServiceEnvironment env) { this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
        public Reliability Reliability() { return new ReliabilityPolicy(env, Reliability.Kind.BEST_EFFORT, new Values.DdsDuration(env, 100, TimeUnit.MILLISECONDS)); }
        public History History() { return new HistoryPolicy(env, History.Kind.KEEP_LAST, 1); }
        public Durability Durability() { return ExtraPolicies.create(env, Durability.class); }
        public Deadline Deadline() { return ExtraPolicies.create(env, Deadline.class); }
        public LatencyBudget LatencyBudget() { return ExtraPolicies.create(env, LatencyBudget.class); }
        public Liveliness Liveliness() { return ExtraPolicies.create(env, Liveliness.class); }
        public DestinationOrder DestinationOrder() { return ExtraPolicies.create(env, DestinationOrder.class); }
        public ResourceLimits ResourceLimits() { return ExtraPolicies.create(env, ResourceLimits.class); }
        public UserData UserData() { return ExtraPolicies.create(env, UserData.class); }
        public Ownership Ownership() { return ExtraPolicies.create(env, Ownership.class); }
        public TimeBasedFilter TimeBasedFilter() { return ExtraPolicies.create(env, TimeBasedFilter.class); }
        public ReaderDataLifecycle ReaderDataLifecycle() { return ExtraPolicies.create(env, ReaderDataLifecycle.class); }
        public DurabilityService DurabilityService() { return ExtraPolicies.create(env, DurabilityService.class); }
        public TransportPriority TransportPriority() { return ExtraPolicies.create(env, TransportPriority.class); }
        public Lifespan Lifespan() { return ExtraPolicies.create(env, Lifespan.class); }
        public OwnershipStrength OwnershipStrength() { return ExtraPolicies.create(env, OwnershipStrength.class); }
        public WriterDataLifecycle WriterDataLifecycle() { return ExtraPolicies.create(env, WriterDataLifecycle.class); }
        public Partition Partition() { return ExtraPolicies.create(env, Partition.class); }
        public GroupData GroupData() { return ExtraPolicies.create(env, GroupData.class); }
        public EntityFactory EntityFactory() { return ExtraPolicies.create(env, EntityFactory.class); }
        public DataRepresentation Representation() { return ExtraPolicies.create(env, DataRepresentation.class); }
        public TypeConsistencyEnforcement TypeConsistency() { return ExtraPolicies.create(env, TypeConsistencyEnforcement.class); }
    }
    private static final class ReliabilityPolicy implements Reliability {
        private final transient ServiceEnvironment owner;
        private final Reliability.Kind kind;
        private final Duration blocking;
        private static final long serialVersionUID = 1L;
        ReliabilityPolicy(ServiceEnvironment owner, Reliability.Kind kind, Duration blocking) {
            this.owner = owner; this.kind = Objects.requireNonNull(kind); this.blocking = Objects.requireNonNull(blocking);
        }
        @Override public boolean equals(Object other) { return other instanceof Reliability r && kind == r.getKind() && blocking.equals(r.getMaxBlockingTime()); }
        @Override public int hashCode() { return Objects.hash(kind, blocking); }
        public ServiceEnvironment getEnvironment() { return owner; }
        public Reliability.Kind getKind() { return kind; }
        public Duration getMaxBlockingTime() { return blocking; }
        public Reliability withKind(Reliability.Kind value) { return new ReliabilityPolicy(owner, value, blocking); }
        public Reliability withBestEffort() { return withKind(Reliability.Kind.BEST_EFFORT); }
        public Reliability withReliable() { return withKind(Reliability.Kind.RELIABLE); }
        public Reliability withMaxBlockingTime(Duration duration) {
            if (duration.getEnvironment() != owner) throw new IllegalArgumentException("Foreign duration");
            return new ReliabilityPolicy(owner, kind, duration);
        }
        public Reliability withMaxBlockingTime(long value, TimeUnit unit) { return withMaxBlockingTime(new Values.DdsDuration(owner, value, unit)); }
        public int compareTo(Reliability other) { return kind.compareTo(other.getKind()); }
        public Comparable<Reliability> requestedOfferedContract() { return this; }
    }
    private static final class HistoryPolicy implements History {
        private final transient ServiceEnvironment owner;
        private final History.Kind kind;
        private final int depth;
        private static final long serialVersionUID = 1L;
        HistoryPolicy(ServiceEnvironment owner, History.Kind kind, int depth) {
            if (depth < 1) throw new IllegalArgumentException("History depth must be positive");
            this.owner = owner; this.kind = Objects.requireNonNull(kind); this.depth = depth;
        }
        @Override public boolean equals(Object other) { return other instanceof History h && kind == h.getKind() && depth == h.getDepth(); }
        @Override public int hashCode() { return Objects.hash(kind, depth); }
        public ServiceEnvironment getEnvironment() { return owner; }
        public History.Kind getKind() { return kind; }
        public int getDepth() { return depth; }
        public History withKind(History.Kind value) { return new HistoryPolicy(owner, value, depth); }
        public History withDepth(int value) { return new HistoryPolicy(owner, kind, value); }
        public History withKeepAll() { return withKind(History.Kind.KEEP_ALL); }
        public History withKeepLast(int value) { return new HistoryPolicy(owner, History.Kind.KEEP_LAST, value); }
    }
    static <Q> Q defaults(ServiceEnvironment env, Class<Q> type, boolean reliable) {
        Map<Class<?>, QosPolicy> values = new LinkedHashMap<>();
        String name = type.getSimpleName();
        if (name.equals("DataReaderQos") || name.equals("DataWriterQos") || name.equals("TopicQos")) {
            values.put(Reliability.class, new ReliabilityPolicy(env, reliable ? Reliability.Kind.RELIABLE : Reliability.Kind.BEST_EFFORT,
                    new Values.DdsDuration(env, 100, TimeUnit.MILLISECONDS)));
            values.put(History.class, new HistoryPolicy(env, History.Kind.KEEP_LAST, 1));
        }
        for (Class<?> policy : ExtraPolicies.SUPPORTED) {
            Class<?> applies = name.equals("TopicQos") ? QosPolicy.ForTopic.class
                : name.equals("DataWriterQos") ? QosPolicy.ForDataWriter.class
                : name.equals("DataReaderQos") ? QosPolicy.ForDataReader.class
                : name.equals("PublisherQos") ? QosPolicy.ForPublisher.class
                : name.equals("SubscriberQos") ? QosPolicy.ForSubscriber.class
                : name.equals("DomainParticipantQos") ? QosPolicy.ForDomainParticipant.class
                : name.equals("DomainParticipantFactoryQos") ? QosPolicy.ForDomainParticipantFactory.class : null;
            if (applies != null && applies.isAssignableFrom(policy))
                values.put(policy, (QosPolicy) ExtraPolicies.create(env, policy));
        }
        return view(env, type, values);
    }
    static org.eclipse.cyclonedds.internal.Native.Qos nativeQos(EntityQos<?> qos) {
        validate(qos);
        var r = (Reliability) qos.get(Reliability.class);
        var h = (History) qos.get(History.class);
        var d = (Durability) qos.get(Durability.class);
        var limits = (ResourceLimits) qos.get(ResourceLimits.class);
        var deadline = (Deadline) qos.get(Deadline.class);
        var lifespan = (Lifespan) qos.get(Lifespan.class);
        var live = (Liveliness) qos.get(Liveliness.class);
        var ownership = (Ownership) qos.get(Ownership.class);
        var strength = (OwnershipStrength) qos.get(OwnershipStrength.class);
        var filter = (TimeBasedFilter) qos.get(TimeBasedFilter.class);
        var lifecycle = (WriterDataLifecycle) qos.get(WriterDataLifecycle.class);
        var service = (DurabilityService) qos.get(DurabilityService.class);
        long[] values = new long[33]; Arrays.fill(values, -1);
        if (r != null) {
        long[] endpoint = new long[]{r.getKind().ordinal(), r.getMaxBlockingTime().getDuration(TimeUnit.NANOSECONDS),
            h.getKind() == History.Kind.KEEP_ALL ? 0 : h.getDepth(), d.getKind().ordinal(),
            limits.getMaxSamples(), limits.getMaxInstances(), limits.getMaxSamplesPerInstance(),
            deadline.getPeriod().getDuration(TimeUnit.NANOSECONDS), lifespan == null ? -1 : lifespan.getDuration().getDuration(TimeUnit.NANOSECONDS),
            live.getKind().ordinal(), live.getLeaseDuration().getDuration(TimeUnit.NANOSECONDS), ownership.getKind().ordinal(),
            strength == null ? 0 : strength.getValue(), filter == null ? -1 : filter.getMinimumSeparation().getDuration(TimeUnit.NANOSECONDS),
            lifecycle == null ? -1 : lifecycle.isAutDisposeUnregisteredInstances() ? 1 : 0,
            strength == null ? 0 : 1, service == null ? -1 : service.getServiceCleanupDelay().getDuration(TimeUnit.NANOSECONDS),
            service == null ? 0 : service.getHistoryKind().ordinal(), service == null ? 1 : service.getHistoryDepth(),
            service == null ? -1 : service.getMaxSamples(), service == null ? -1 : service.getMaxInstances(),
            service == null ? -1 : service.getMaxSamplesPerInstance()};
        System.arraycopy(endpoint, 0, values, 0, endpoint.length);
        }
        DestinationOrder order = (DestinationOrder) qos.get(DestinationOrder.class);
        LatencyBudget latency = (LatencyBudget) qos.get(LatencyBudget.class);
        TransportPriority priority = (TransportPriority) qos.get(TransportPriority.class);
        ReaderDataLifecycle readerLife = (ReaderDataLifecycle) qos.get(ReaderDataLifecycle.class);
        TypeConsistencyEnforcement consistency = (TypeConsistencyEnforcement) qos.get(TypeConsistencyEnforcement.class);
        DataRepresentation representation = (DataRepresentation) qos.get(DataRepresentation.class);
        Presentation presentation = (Presentation) qos.get(Presentation.class);
        values[22] = order == null ? -1 : order.getKind().ordinal();
        values[23] = latency == null ? -1 : latency.getDuration().getDuration(TimeUnit.NANOSECONDS);
        values[24] = priority == null ? -1 : priority.getValue();
        values[25] = readerLife == null ? -1 : readerLife.getAutoPurgeNoWriterSamplesDelay().getDuration(TimeUnit.NANOSECONDS);
        values[26] = readerLife == null ? -1 : readerLife.getAutoPurgeDisposedSamplesDelay().getDuration(TimeUnit.NANOSECONDS);
        values[27] = consistency == null ? -1 : consistency.getKind() == TypeConsistencyEnforcement.Kind.EXACT_TYPE_TYPE_CONSISTENCY ? 0 : 1;
        values[28] = representation == null ? -1 : representation.getValue().stream().mapToInt(id -> 1 << id).reduce(0, (a, b) -> a | b);
        values[29] = presentation == null ? -1 : presentation.getAccessScope().ordinal();
        values[30] = presentation != null && presentation.isCoherentAccess() ? 1 : 0;
        values[31] = presentation != null && presentation.isOrderedAccess() ? 1 : 0;
        values[32] = priority == null ? 0 : 1;
        UserData user = (UserData) qos.get(UserData.class); GroupData group = (GroupData) qos.get(GroupData.class); TopicData topic = (TopicData) qos.get(TopicData.class);
        return new org.eclipse.cyclonedds.internal.Native.Qos(values, new byte[][]{
            user == null ? null : user.getValue(), group == null ? null : group.getValue(), topic == null ? null : topic.getValue()},
            qos.get(Partition.class) == null ? null : partitions(qos));
    }
    static void validate(EntityQos<?> qos) {
        ReaderDataLifecycle readerLife = (ReaderDataLifecycle) qos.get(ReaderDataLifecycle.class);
        if (readerLife != null && (!readerLife.getAutoPurgeNoWriterSamplesDelay().isInfinite() || !readerLife.getAutoPurgeDisposedSamplesDelay().isInfinite()))
            throw unsupported("Finite reader auto-purge timers in the Cyclone core");
        EntityFactory factory = (EntityFactory) qos.get(EntityFactory.class);
        if (factory != null && !factory.isAutoEnableCreatedEntities()) throw unsupported("Disabled entity creation in the Cyclone core");
        DataRepresentation representation = (DataRepresentation) qos.get(DataRepresentation.class);
        if (representation != null) {
            if (representation.getValue().isEmpty() || representation.getValue().stream().anyMatch(id -> id != 0 && id != 2))
                throw new IllegalArgumentException("Data representation must contain XCDR1 (0) or XCDR2 (2)");
            if (qos instanceof org.omg.dds.pub.DataWriterQos && representation.getValue().size() != 1)
                throw new IllegalArgumentException("A writer must select exactly one data representation");
        }
        TypeConsistencyEnforcement consistency = (TypeConsistencyEnforcement) qos.get(TypeConsistencyEnforcement.class);
        if (consistency != null && (consistency.getKind() == TypeConsistencyEnforcement.Kind.EXACT_NAME_TYPE_CONSISTENCY || consistency.getKind() == TypeConsistencyEnforcement.Kind.DECLARED_TYPE_CONSISTENCY))
            throw unsupported("Legacy exact-name/declared type consistency has no equivalent in the Cyclone XTypes core");
        Presentation presentation = (Presentation) qos.get(Presentation.class);
        if (presentation != null && (presentation.isCoherentAccess() || presentation.isOrderedAccess())) throw unsupported("Coherent/ordered presentation access");
    }
    static String[] partitions(EntityQos<?> qos) { return ((Partition) qos.get(Partition.class)).getName().toArray(String[]::new); }
    static EntityQos<?> copyFromTopic(ServiceEnvironment env, Object destination, Object topic) {
        requireOwned(env, destination); requireOwned(env, topic);
        QosView target = (QosView) Proxy.getInvocationHandler(destination);
        QosView source = (QosView) Proxy.getInvocationHandler(topic);
        Map<Class<?>, QosPolicy> changed = new LinkedHashMap<>(target.values);
        for (Class<?> key : changed.keySet()) if (source.values.containsKey(key)) changed.put(key, source.values.get(key));
        return (EntityQos<?>) view(env, target.type, changed);
    }
    static <Q> Q view(ServiceEnvironment env, Class<Q> type, Map<Class<?>, QosPolicy> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new QosView(env, type, values)));
    }
    static List<String> changedPolicies(EntityQos<?> before, EntityQos<?> after) {
        QosView a = (QosView) Proxy.getInvocationHandler(before);
        QosView b = (QosView) Proxy.getInvocationHandler(after);
        return a.values.keySet().stream().filter(key -> !Objects.equals(a.values.get(key), b.values.get(key)))
            .map(Class::getSimpleName).sorted().toList();
    }
    static void requireOwned(ServiceEnvironment env, Object qos) {
        if (!(qos instanceof DDSObject object) || object.getEnvironment() != env || !Proxy.isProxyClass(qos.getClass())
                || !(Proxy.getInvocationHandler(qos) instanceof QosView)) throw new IllegalArgumentException("Foreign QoS object");
    }
    private static final class QosView implements InvocationHandler, Serializable {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final Class<?> type;
        @SuppressWarnings("serial") // The immutable wrapper and every policy value are serializable.
        private final Map<Class<?>, QosPolicy> values;
        QosView(ServiceEnvironment env, Class<?> type, Map<Class<?>, QosPolicy> values) {
            this.env = env; this.type = type; this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            String name = method.getName();
            switch (name) {
                case "getEnvironment": return env;
                case "getPolicyFactory": return env.getSPI().getPolicyFactory();
                case "equals": return args[0] == proxy || args[0] != null && Proxy.isProxyClass(args[0].getClass()) && Proxy.getInvocationHandler(args[0]) instanceof QosView other && type == other.type && values.equals(other.values);
                case "hashCode": return values.hashCode();
                case "toString": return type.getSimpleName() + values;
                case "withPolicy": case "withPolicies": {
                    Object[] policies = name.equals("withPolicy") ? args : (Object[]) args[0];
                    Map<Class<?>, QosPolicy> changed = new LinkedHashMap<>(values);
                    for (Object value : policies) {
                        if (!(value instanceof QosPolicy policy) || policy.getEnvironment() != env) throw new IllegalArgumentException("Foreign policy");
                        Class<?> key = policy instanceof Reliability ? Reliability.class : policy instanceof History ? History.class : null;
                        if (key == null) for (Class<?> candidate : ExtraPolicies.SUPPORTED)
                            if (candidate.isInstance(policy)) { key = candidate; break; }
                        if (key == null || !changed.containsKey(key)) throw unsupported("Policy for " + type.getSimpleName());
                        changed.put(key, policy);
                    }
                    return view(env, type, changed);
                }
                case "getReliability": return values.get(Reliability.class);
                case "getHistory": return values.get(History.class);
                case "getRepresentation": return values.get(DataRepresentation.class);
                case "getTypeConsistency": return values.get(TypeConsistencyEnforcement.class);
                case "get": return values.get(args[0]);
                default:
                    if (name.startsWith("get") && args.length == 0) {
                        for (var entry : values.entrySet()) if (name.equals("get" + entry.getKey().getSimpleName())) return entry.getValue();
                        throw unsupported(name);
                    }
                    try { return method.invoke(values, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
            }
        }
    }
}
