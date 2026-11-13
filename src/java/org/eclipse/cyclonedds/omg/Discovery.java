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
import org.eclipse.cyclonedds.internal.DiscoveryData;
import org.eclipse.cyclonedds.internal.Native;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.topic.*;

/** Owned implementations of the normative built-in discovery data interfaces. */
final class Discovery {
    private Discovery() {}
    static final List<String> NAMES = List.of("DCPSParticipant", "DCPSTopic", "DCPSPublication", "DCPSSubscription");
    static final List<Class<?>> TYPES = List.of(ParticipantBuiltinTopicData.class, TopicBuiltinTopicData.class,
        PublicationBuiltinTopicData.class, SubscriptionBuiltinTopicData.class);
    static int kind(Class<?> type) { return TYPES.indexOf(type) + 1; }
    static boolean isData(Object value) { return value != null && Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof Snapshot; }
    static Object copy(Object value) {
        Snapshot s = (Snapshot)Proxy.getInvocationHandler(value);
        return s.proxy(new LinkedHashMap<>(s.values));
    }
    static int[] key(Object value) {
        if (!isData(value)) throw new IllegalArgumentException("Expected discovery data from this provider");
        return ((BuiltinTopicKey)((Snapshot)Proxy.getInvocationHandler(value)).values.get("getKey")).getValue();
    }
    static Object copyKey(Object holder, Object source) {
        if (!isData(holder) || !isData(source)) throw new IllegalArgumentException("Expected provider-owned discovery data");
        Snapshot target = (Snapshot)Proxy.getInvocationHandler(holder), from = (Snapshot)Proxy.getInvocationHandler(source);
        if (target.api != from.api) throw new IllegalArgumentException("Mismatched discovery data kinds");
        Map<String, Object> values = new LinkedHashMap<>(target.values);
        values.put("getKey", ((BuiltinTopicKey)from.values.get("getKey")).clone()); target.values = Map.copyOf(values);
        return holder;
    }
    static Object withKey(ServiceEnvironment env, int kind, int[] key) {
        Object value = empty(env, kind);
        Snapshot snapshot = (Snapshot)Proxy.getInvocationHandler(value);
        Map<String, Object> fields = new LinkedHashMap<>(snapshot.values);
        fields.put("getKey", new Key(env, key)); snapshot.values = Map.copyOf(fields);
        return value;
    }
    static Object empty(ServiceEnvironment env, int kind) {
        long[] values = new long[33]; Arrays.fill(values, -1);
        return data(env, new DiscoveryData(kind, new int[4], new int[4], "", "", new Native.Qos(values, new byte[3][], null), null));
    }
    static Object data(ServiceEnvironment env, DiscoveryData data) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("getKey", new Key(env, data.key()));
        values.put("getParticipantKey", new Key(env, data.participantKey() == null ? new int[4] : data.participantKey()));
        values.put("getName", data.topicName() == null ? "" : data.topicName());
        values.put("getTopicName", data.topicName() == null ? "" : data.topicName());
        values.put("getTypeName", data.typeName() == null ? "" : data.typeName());
        values.put("getEquivalentTypeName", List.of()); values.put("getBaseTypeName", List.of());
        Map<Class<?>, QosPolicy> policies = policies(env, data.qos());
        for (Method method : TYPES.get(data.kind()-1).getMethods()) if (QosPolicy.class.isAssignableFrom(method.getReturnType())) {
            Class<?> api = method.getReturnType(); QosPolicy policy = policies.get(api);
            if (policy == null) policy = api == Reliability.class ? new Policies.Factory(env).Reliability()
                : api == History.class ? new Policies.Factory(env).History() : (QosPolicy)ExtraPolicies.create(env, api);
            values.put(method.getName(), policy);
        }
        return new Snapshot(env, TYPES.get(data.kind()-1), values).proxy(values);
    }
    private static Duration duration(ServiceEnvironment env, long nanos) { return new Values.DdsDuration(env, nanos, TimeUnit.NANOSECONDS); }
    static <T extends EntityQos<?>> T qos(ServiceEnvironment env, Class<T> api, Native.Qos snapshot) {
        T defaults = Policies.defaults(env, api, false);
        Map<Class<?>, QosPolicy> values = new LinkedHashMap<>(defaults);
        policies(env, snapshot).forEach((key, value) -> { if (values.containsKey(key)) values.put(key, value); });
        return Policies.view(env, api, values);
    }
    private static Map<Class<?>, QosPolicy> policies(ServiceEnvironment env, Native.Qos qos) {
        long[] v = qos.values(); Map<Class<?>, QosPolicy> result = new LinkedHashMap<>();
        if (v[0] >= 0) result.put(Reliability.class, new Policies.Factory(env).Reliability().withKind(Reliability.Kind.values()[(int)v[0]])
            .withMaxBlockingTime(duration(env, v[1])));
        if (v[2] >= 0) result.put(History.class, v[2] == 0 ? new Policies.Factory(env).History().withKeepAll() : new Policies.Factory(env).History().withKeepLast((int)v[2]));
        if (v[3] >= 0) put(env, result, Durability.class, Map.of("Kind", Durability.Kind.values()[(int)v[3]]));
        if (v[4] != -1 || v[5] != -1 || v[6] != -1) put(env, result, ResourceLimits.class, Map.of("MaxSamples", (int)v[4], "MaxInstances", (int)v[5], "MaxSamplesPerInstance", (int)v[6]));
        if (v[7] >= 0) put(env, result, Deadline.class, Map.of("Period", duration(env, v[7])));
        if (v[8] >= 0) put(env, result, Lifespan.class, Map.of("Duration", duration(env, v[8])));
        if (v[9] >= 0) put(env, result, Liveliness.class, Map.of("Kind", Liveliness.Kind.values()[(int)v[9]], "LeaseDuration", duration(env, v[10])));
        if (v[11] >= 0) put(env, result, Ownership.class, Map.of("Kind", Ownership.Kind.values()[(int)v[11]]));
        if (v[15] == 1) put(env, result, OwnershipStrength.class, Map.of("Value", (int)v[12]));
        if (v[13] >= 0) put(env, result, TimeBasedFilter.class, Map.of("MinimumSeparation", duration(env, v[13])));
        if (v[16] >= 0) put(env, result, DurabilityService.class, Map.of("ServiceCleanupDelay", duration(env, v[16]),
            "HistoryKind", History.Kind.values()[(int)v[17]], "HistoryDepth", (int)v[18], "MaxSamples", (int)v[19], "MaxInstances", (int)v[20], "MaxSamplesPerInstance", (int)v[21]));
        if (v[22] >= 0) put(env, result, DestinationOrder.class, Map.of("Kind", DestinationOrder.Kind.values()[(int)v[22]]));
        if (v[23] >= 0) put(env, result, LatencyBudget.class, Map.of("Duration", duration(env, v[23])));
        if (v[32] == 1) put(env, result, TransportPriority.class, Map.of("Value", (int)v[24]));
        if (v[25] >= 0) put(env, result, ReaderDataLifecycle.class, Map.of("AutoPurgeNoWriterSamplesDelay", duration(env, v[25]), "AutoPurgeDisposedSamplesDelay", duration(env, v[26])));
        if (v[27] >= 0) put(env, result, TypeConsistencyEnforcement.class, Map.of("Kind", v[27] == 0 ? TypeConsistencyEnforcement.Kind.EXACT_TYPE_TYPE_CONSISTENCY : TypeConsistencyEnforcement.Kind.ASSIGNABLE_TYPE_CONSISTENCY));
        if (v[28] > 0) {
            List<Short> ids = new ArrayList<>(); if ((v[28] & 1) != 0) ids.add((short)0); if ((v[28] & 4) != 0) ids.add((short)2);
            put(env, result, DataRepresentation.class, Map.of("Value", List.copyOf(ids)));
        }
        if (v[29] >= 0) put(env, result, Presentation.class, Map.of("AccessScope", Presentation.AccessScopeKind.values()[(int)v[29]], "CoherentAccess", v[30] != 0, "OrderedAccess", v[31] != 0));
        if (qos.partitions() != null) put(env, result, Partition.class, Map.of("Name", Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(qos.partitions())))));
        Class<?>[] binary = {UserData.class, GroupData.class, TopicData.class};
        for (int i = 0; i < binary.length; i++) if (qos.data()[i] != null) put(env, result, binary[i], Map.of("Value", qos.data()[i].clone()));
        return result;
    }
    private static void put(ServiceEnvironment env, Map<Class<?>, QosPolicy> values, Class<?> api, Map<String, Object> properties) {
        values.put(api, (QosPolicy)ExtraPolicies.proxy(env, api, properties));
    }
    private static final class Snapshot implements InvocationHandler, Serializable {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final Class<?> api;
        @SuppressWarnings("serial") private Map<String, Object> values;
        Snapshot(ServiceEnvironment env, Class<?> api, Map<String, Object> values) { this.env = env; this.api = api; this.values = Map.copyOf(values); }
        Object proxy(Map<String, Object> value) { return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, new Snapshot(env, api, value)); }
        public Object invoke(Object self, Method method, Object[] args) throws ReflectiveOperationException {
            String op = method.getName();
            if (op.equals("getEnvironment")) return env;
            if (op.equals("clone")) return proxy(values);
            if (op.equals("copyFrom")) {
                Object source = api.cast(Objects.requireNonNull(args[0]));
                Map<String, Object> replacement = new LinkedHashMap<>();
                for (Method getter : api.getMethods()) if (getter.getParameterCount() == 0 && getter.getName().startsWith("get") && !getter.getName().equals("getEnvironment")) {
                    Object value = getter.invoke(source);
                    if (value != null) replacement.put(getter.getName(), value instanceof BuiltinTopicKey key ? key.clone() : value);
                }
                values = Map.copyOf(replacement); return null;
            }
            if (op.equals("equals")) return args[0] != null && Proxy.isProxyClass(args[0].getClass())
                && Proxy.getInvocationHandler(args[0]) instanceof Snapshot other && api == other.api && values.equals(other.values);
            if (op.equals("hashCode")) return values.hashCode();
            if (op.equals("toString")) return api.getSimpleName() + values;
            Object value = values.get(op);
            return value instanceof BuiltinTopicKey key ? key.clone() : value;
        }
    }
    private static final class Key implements BuiltinTopicKey {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private int[] words;
        Key(ServiceEnvironment env, int[] words) { this.env = env; this.words = words.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int[] getValue() { return words.clone(); }
        public void copyFrom(BuiltinTopicKey source) { words = Objects.requireNonNull(source).getValue().clone(); }
        public BuiltinTopicKey clone() { return new Key(env, words); }
        public boolean equals(Object value) { return value instanceof BuiltinTopicKey key && Arrays.equals(words, key.getValue()); }
        public int hashCode() { return Arrays.hashCode(words); }
        public String toString() { return Arrays.toString(words); }
    }
}
