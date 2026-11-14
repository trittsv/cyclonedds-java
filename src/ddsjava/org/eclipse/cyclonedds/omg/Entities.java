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

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.DdsException;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.internal.WireSample;
import org.eclipse.cyclonedds.internal.DiscoveryData;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.*;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;
import org.omg.dds.topic.*;

/** Entity implementations exposed through the exact normative interfaces.
 * Overloads are checked explicitly; unsupported features never report success.
 */
final class Entities {
    private Entities() {}
    private enum Kind { PARTICIPANT, TOPIC, FILTER_TOPIC, PUBLISHER, SUBSCRIBER, WRITER, READER }
    static void validateListenerArguments(Object listener, Object statuses) {
        if (statuses != null && !(statuses instanceof Collection<?>)) throw new IllegalArgumentException("Invalid status collection");
        if (statuses != null) for (Object status : (Collection<?>) statuses)
            if (!Conditions.STATUS_TYPES.contains(Objects.requireNonNull(status))) throw new IllegalArgumentException("Unknown status class");
    }
    private static State state(Object entity) {
        if (entity == null || !Proxy.isProxyClass(entity.getClass()) || !(Proxy.getInvocationHandler(entity) instanceof State))
            throw new IllegalArgumentException("Foreign DDS entity");
        return (State) Proxy.getInvocationHandler(entity);
    }
    static boolean isOpen(Object entity) { State s = state(entity); synchronized (s.root) { return s.handle != 0 && s.root.handle != 0; } }
    static DomainParticipant participant(ServiceEnvironment env, int domain, DomainParticipantQos qos, Object listener, Object statuses) {
        State s;
        try { s = new State(env, Kind.PARTICIPANT, null, Native.participantConfigured(domain, Policies.nativeQos(qos)), qos, null, null); }
        catch (DdsException error) {
            Throwable mapped = Errors.map(env, error);
            if (mapped instanceof RuntimeException runtime) throw runtime;
            throw new Errors.Failure(env, error);
        }
        s.domain = domain;
        DomainParticipant result = s.as(DomainParticipant.class);
        s.initializeListener(listener, statuses);
        return result;
    }
    private static final class State implements InvocationHandler {
        final ServiceEnvironment env;
        final Kind kind;
        final State parent, root;
        final String name;
        final CycloneTypeSupport<?> type;
        final List<State> children = new ArrayList<>();
        final List<Conditions.Read<?>> conditions = new ArrayList<>();
        Conditions.EntityStatus<?> statusCondition;
        Object listener;
        int listenerMask;
        long listenerContext;
        final ListenerDispatch dispatch;
        final Map<Class<?>, EntityQos<?>> defaults = new HashMap<>();
        EntityQos<?> qos;
        State topic;
        State builtinSubscriber;
        boolean borrowed;
        int builtinKind;
        final Map<Long, int[]> builtinKeys = new HashMap<>();
        MutableFilter contentFilter;
        long filterContext;
        Object proxy;
        volatile int handle;
        int domain, references = 1, loans;
        State(ServiceEnvironment env, Kind kind, State parent, int handle, EntityQos<?> qos, String name, CycloneTypeSupport<?> type) {
            this.env = env; this.kind = kind; this.parent = parent; this.root = parent == null ? this : parent.root;
            this.dispatch = new ListenerDispatch(parent == null ? null : root.dispatch);
            this.handle = handle; this.qos = qos; this.name = name; this.type = type;
            if (parent != null) parent.children.add(this);
        }
        @SuppressWarnings("unchecked") <T> T as(Class<T> api) {
            if (proxy == null) proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, this);
            return (T) proxy;
        }
        void requireOpen() { if (handle == 0 || root.handle == 0) throw new Errors.Closed(env); }
        @SuppressWarnings("unchecked")
        @Override public Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            String op = method.getName();
            if (method.getDeclaringClass() == Object.class) return switch (op) {
                case "equals" -> object == args[0];
                case "hashCode" -> System.identityHashCode(object);
                case "toString" -> "CycloneDDS " + kind + (name == null ? "" : " " + name);
                default -> throw new AssertionError(op);
            };
            if (op.equals("getEnvironment")) return env;
            if (op.equals("waitForAcknowledgments") || op.equals("waitForHistoricalData")) {
                int entity;
                synchronized (root) { requireOpen(); entity = handle; }
                try {
                    if (op.equals("waitForAcknowledgments")) Native.waitForAcks(entity, duration(args));
                    else Native.historicalData(entity, duration(args));
                } catch (DdsException error) { throw Errors.map(env, error); }
                return null;
            }
            synchronized (root) {
                try {
                    if (op.equals("close")) { close(false); return null; }
                    requireOpen();
                    if (kind == Kind.READER && topic.contentFilter != null) topic.contentFilter.current.checkFailure();
                    if (op.startsWith("getDefault") && op.endsWith("Qos")) return defaultQos(method.getReturnType());
                    if (op.startsWith("setDefault") && op.endsWith("Qos")) {
                        Policies.requireOwned(env, args[0]);
                        Class<?> api = method.getParameterTypes()[0];
                        defaults.put(api, (EntityQos<?>) args[0]); return null;
                    }
                    return switch (op) {
                        case "retain" -> { references = Math.addExact(references, 1); yield null; }
                        case "enable" -> null; // Native entities are created enabled.
                        case "getParent" -> parent == null ? null : parent.proxy;
                        case "getDomainId" -> domain;
                        case "getBuiltinSubscriber" -> builtins().proxy;
                        case "getDiscoveredParticipants" -> discoveredHandles(1);
                        case "getDiscoveredTopics" -> discoveredHandles(2);
                        case "getDiscoveredParticipantData" -> discoveredData(1, (InstanceHandle)args[0]);
                        case "getDiscoveredTopicData" -> discoveredData(2, (InstanceHandle)args[0]);
                        case "getQos" -> qos;
                        case "setQos" -> { setQos(args); yield null; }
                        case "copyFromTopicQos" -> Policies.copyFromTopic(env, args[0], args[1]);
                        case "assertLiveliness" -> { Native.assertLiveliness(handle); yield null; }
                        case "notifyDataReaders" -> {
                            for (State child : List.copyOf(children)) if (child.kind == Kind.READER && (child.listenerMask & (1 << 8)) != 0
                                    && (Native.takeStatus(child.handle, 1 << 8) & (1 << 8)) != 0) child.dispatch.onEvent(8, new long[0]);
                            yield null;
                        }
                        case "getListener" -> listener;
                        case "setListener" -> { setListener(args[0], args.length > 1 ? args[1] : null); yield null; }
                        case "getInstanceHandle" -> new Values.Handle(env, Native.instanceHandle(handle)).immutableCopy();
                        case "getCurrentTime" -> {
                            var time = new Values.DdsTime(env, Math.multiplyExact(System.currentTimeMillis(), 1_000_000L), TimeUnit.NANOSECONDS);
                            if (args.length == 0) yield time.immutableCopy();
                            ((ModifiableTime) args[0]).copyFrom(time); yield args[0];
                        }
                        case "createTopic" -> createTopic(args);
                        case "createPublisher" -> container(args, true);
                        case "createSubscriber" -> container(args, false);
                        case "createDataWriter" -> endpoint(args, true);
                        case "createDataReader" -> endpoint(args, false);
                        case "createDataState" -> new DataStates(env);
                        case "select" -> new ReaderSelector<>(env, (DataReader<?>) proxy, root, type);
                        case "createReadCondition" -> readCondition((Subscriber.DataState) args[0]);
                        case "createQueryCondition" -> queryCondition(args);
                        case "createContentFilteredTopic" -> filteredTopic(args);
                        case "getFilterExpression" -> contentFilter.current.expression;
                        case "getExpressionParameters" -> contentFilter.current.parameters;
                        case "setExpressionParameters" -> { contentFilter.current = new ContentFilter(type, contentFilter.current.expression, (List<String>) args[0]); yield null; }
                        case "getRelatedTopic" -> topic.proxy;
                        case "getStatusCondition" -> statusCondition();
                        case "getStatusChanges" -> Conditions.statuses(Native.statusChanges(handle));
                        case "getPublicationMatchedStatus" -> StatusValues.create(env, 11, Native.status(handle, 11));
                        case "getSubscriptionMatchedStatus" -> StatusValues.create(env, 12, Native.status(handle, 12));
                        case "lookupTopicDescription", "lookupDataWriter", "lookupDataReader" -> lookup(args[0], op);
                        case "closeContainedEntities" -> { closeChildren(); for (var condition : List.copyOf(conditions)) closeReadCondition(condition); yield null; }
                        case "getTypeName" -> type.getTypeName();
                        case "getTypeSupport" -> type;
                        case "getName" -> name;
                        case "getTopic", "getTopicDescription" -> topic.proxy;
                        case "cast" -> object;
                        case "write" -> { write(args); yield null; }
                        case "registerInstance" -> {
                            timestamp(args, 1); // Registration is local; the core has no timestamped registration event.
                            yield new Values.Handle(env, Native.instanceOperation(handle, encode(args[0]), true)).immutableCopy();
                        }
                        case "getKeyValue" -> keyValue(args);
                        case "lookupInstance" -> {
                            Object value = args[args.length - 1];
                            var result = new Values.Handle(env, builtinKind == 0 ? Native.instanceOperation(handle, encode(value), false) : Native.builtinLookup(handle, Discovery.key(value)));
                            if (args.length == 1) yield result.immutableCopy();
                            ((ModifiableInstanceHandle) args[0]).copyFrom(result); yield args[0];
                        }
                        case "dispose", "unregisterInstance" -> { dispose(args, op.equals("unregisterInstance")); yield null; }
                        case "waitForAcknowledgments" -> {
                            Native.waitForAcks(handle, duration(args)); yield null;
                        }
                        case "getMatchedSubscriptions", "getMatchedPublications" -> {
                            Set<InstanceHandle> handles = new LinkedHashSet<>();
                            for (long value : Native.matchedHandles(handle, kind == Kind.WRITER))
                                handles.add(new Values.Handle(env, value).immutableCopy());
                            yield Collections.unmodifiableSet(handles);
                        }
                        case "getMatchedSubscriptionData", "getMatchedPublicationData" -> {
                            long instance = Values.handleValue((InstanceHandle)args[0], env);
                            if (instance == 0) throw new IllegalArgumentException("A non-nil endpoint handle is required");
                            yield Discovery.data(env, Native.matchedData(handle, kind == Kind.WRITER, instance));
                        }
                        case "read", "take" -> samples(method, args, op.equals("take"));
                        case "readNextSample", "takeNextSample" -> nextSample(args[0], op.equals("takeNextSample"));
                        default -> {
                            int index = -1;
                            for (int i = 0; i < Conditions.STATUS_TYPES.size(); i++)
                                if (op.equals("get" + Conditions.STATUS_TYPES.get(i).getSimpleName())) index = i;
                            if (index >= 0) yield StatusValues.create(env, index, Native.status(handle, index));
                            throw Policies.unsupported(method.toGenericString());
                        }
                    };
                } catch (DdsException error) { throw Errors.map(env, error); }
            }
        }
        private int legalStatuses() {
            return switch (kind) {
                case TOPIC -> 1;
                case FILTER_TOPIC -> 0;
                case WRITER, PUBLISHER -> (1 << 1) | (1 << 3) | (1 << 9) | (1 << 11);
                case READER -> (1 << 2) | (1 << 4) | (1 << 5) | (1 << 6) | (1 << 8) | (1 << 10) | (1 << 12);
                case SUBSCRIBER -> (1 << 2) | (1 << 4) | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 8) | (1 << 10) | (1 << 12);
                case PARTICIPANT -> (1 << 13) - 1;
            };
        }
        private void initializeListener(Object value, Object statuses) {
            try { setListener(value, statuses); }
            catch (RuntimeException error) { close(true); throw error; }
        }
        private void setListener(Object value, Object statuses) {
            validateListenerArguments(value, statuses);
            int mask = statuses == null ? legalStatuses() : 0;
            if (statuses != null) for (Object status : (Collection<?>) statuses) {
                int index = Conditions.STATUS_TYPES.indexOf(Objects.requireNonNull(status));
                if (index < 0 || (legalStatuses() & (1 << index)) == 0) throw new IllegalArgumentException("Status is not applicable to this listener");
                mask |= 1 << index;
            }
            listener = value; listenerMask = value == null ? 0 : mask;
            refreshListeners();
        }
        private void refreshListeners() {
            ListenerDispatch.Target[] targets = new ListenerDispatch.Target[13];
            int mask = 0;
            int applicable = switch (kind) {
                case PARTICIPANT, PUBLISHER -> 0;
                case SUBSCRIBER -> 1 << 7;
                default -> legalStatuses();
            };
            for (int i = 0; i < targets.length; i++) if ((applicable & (1 << i)) != 0) {
                for (State owner = this; owner != null; owner = owner.parent) if ((owner.listenerMask & (1 << i)) != 0) {
                    targets[i] = new ListenerDispatch.Target(owner.listener, proxy, env, handle); mask |= 1 << i; break;
                }
            }
            dispatch.configure(targets);
            if (mask != 0 || listenerContext != 0) {
                if (listenerContext == 0) listenerContext = Native.listenerCreate(dispatch);
                Native.listenerSet(handle, listenerContext, mask);
            }
            for (State child : children) child.refreshListeners();
        }
        private EntityQos<?> defaultQos(Class<?> api) {
            return defaults.computeIfAbsent(api, key -> (EntityQos<?>) Policies.defaults(env, key, key == DataWriterQos.class));
        }
        private void setQos(Object[] args) {
            if (args.length != 1) throw Policies.unsupported("XML QoS profiles");
            Policies.requireOwned(env, args[0]);
            EntityQos<?> value = (EntityQos<?>) args[0];
            if (kind == Kind.READER && !Objects.equals(qos.get(TimeBasedFilter.class), value.get(TimeBasedFilter.class)))
                throw new UnsupportedOperationException("The unmodified Cyclone DDS core cannot update an enabled reader's time-based filter; recreate the reader");
            try { Native.setQos(handle, Policies.nativeQos(value)); }
            catch (DdsException error) {
                if (error.code() == -2) throw new UnsupportedOperationException("Cyclone DDS does not support this enabled-entity update: "
                    + String.join(", ", Policies.changedPolicies(qos, value)) + ". Create the entity with these policies instead.", error);
                throw error;
            }
            qos = value;
        }
        private Object readCondition(Subscriber.DataState value) {
            DataStates snapshot = DataStates.snapshot(env, value);
            int entity = snapshot.mask() == 0 ? Native.guard() : Native.readCondition(handle, snapshot.mask());
            var condition = new Conditions.Read<>(env, (DataReader<?>) proxy, entity, snapshot, this::closeReadCondition);
            conditions.add(condition); return condition;
        }
        @SuppressWarnings("unchecked") private Object queryCondition(Object[] args) {
            int offset = args.length == 2 ? 0 : 1;
            DataStates states = offset == 0 ? DataStates.any(env) : DataStates.snapshot(env, (Subscriber.DataState) args[0]);
            ContentFilter filter = new ContentFilter(type, (String) args[offset], (List<String>) args[offset + 1]);
            var query = new Query<>(env, (DataReader<?>) proxy, handle, states, type, filter, root, this::closeReadCondition);
            conditions.add(query); return query;
        }
        private static final class MutableFilter {
            volatile ContentFilter current;
            MutableFilter(ContentFilter filter) { current = filter; }
            public boolean matches(byte[] cdr) { return current.matches(cdr); }
        }
        @SuppressWarnings("unchecked") private Object filteredTopic(Object[] args) {
            String filterName = Objects.requireNonNull((String) args[0]);
            if (filterName.isBlank() || filterName.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid filtered topic name");
            State related = state(args[1]); related.requireOpen();
            if (related.kind != Kind.TOPIC || related.root != root) throw new IllegalArgumentException("Expected a topic of this participant");
            for (State child : children) if (Objects.equals(child.name, filterName)) throw new Errors.Precondition(env, "Topic description name already exists");
            ContentFilter filter = new ContentFilter(related.type, (String) args[2], (List<String>) args[3]);
            var binding = related.type.binding();
            int nativeTopic = Native.topicConfigured(handle, related.name, related.type.getTypeName(), binding.hasKey(), binding.allowedDataRepresentations(), binding.typeInformation(), binding.typeMapping(), Policies.nativeQos(related.qos));
            State s = new State(env, Kind.FILTER_TOPIC, this, nativeTopic, related.qos, filterName, related.type);
            s.topic = related; s.contentFilter = new MutableFilter(filter);
            try { s.filterContext = Native.topicFilter(nativeTopic, s.contentFilter); }
            catch (RuntimeException error) { Native.delete(nativeTopic); children.remove(s); throw error; }
            return s.as(ContentFilteredTopic.class);
        }
        private void closeReadCondition(Conditions.Read<?> condition) {
            synchronized (root) { condition.delete(); conditions.remove(condition); }
        }
        private Object statusCondition() {
            if (statusCondition == null) {
                int mask = switch (kind) {
                    case TOPIC -> 1;
                    case WRITER -> (1 << 1) | (1 << 3) | (1 << 9) | (1 << 11);
                    case READER -> (1 << 2) | (1 << 4) | (1 << 5) | (1 << 6) | (1 << 8) | (1 << 10) | (1 << 12);
                    case SUBSCRIBER -> 1 << 7;
                    default -> 0;
                };
                statusCondition = new Conditions.EntityStatus<>(env, handle, (Entity<?, ?>) proxy, mask);
            }
            return statusCondition;
        }
        private Object createTopic(Object[] args) {
            if (kind != Kind.PARTICIPANT) throw new IllegalArgumentException("Topics require a participant");
            String topicName = Objects.requireNonNull((String) args[0]);
            if (topicName.isBlank() || topicName.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid topic name");
            if (args.length > 2 && args[2] instanceof String) throw Policies.unsupported("XML QoS profiles");
            CycloneTypeSupport<?> support;
            if (args[1] instanceof Class<?> dataClass) support = new CycloneTypeSupport<>(env, dataClass, null);
            else if (args[1] instanceof CycloneTypeSupport<?> ts && ts.getEnvironment() == env) support = ts;
            else throw new IllegalArgumentException("Expected a generated Java class or a type support from this environment");
            TopicQos topicQos = (TopicQos) defaultQos(TopicQos.class);
            if (args.length > 2) {
                Policies.requireOwned(env, args[2]);
                topicQos = (TopicQos) args[2];
            }
            if (args.length == 5) validateListenerArguments(args[3], args[4]);
            var binding = support.binding();
            int nativeTopic = Native.topicConfigured(handle, topicName, support.getTypeName(), binding.hasKey(), binding.allowedDataRepresentations(), binding.typeInformation(), binding.typeMapping(), Policies.nativeQos(topicQos));
            State s = new State(env, Kind.TOPIC, this, nativeTopic, topicQos, topicName, support);
            Object result = s.as(Topic.class);
            s.initializeListener(args.length == 5 ? args[3] : null, args.length == 5 ? args[4] : null);
            return result;
        }
        private Object container(Object[] args, boolean publisher) {
            Class<?> api = publisher ? PublisherQos.class : SubscriberQos.class;
            EntityQos<?> value = defaultQos(api);
            if (args.length > 0) { Policies.requireOwned(env, args[0]); value = (EntityQos<?>) args[0]; }
            if (args.length == 3) validateListenerArguments(args[1], args[2]);
            State s = new State(env, publisher ? Kind.PUBLISHER : Kind.SUBSCRIBER, this,
                    Native.containerConfigured(handle, publisher, Policies.nativeQos(value)), value, null, null);
            Object result = publisher ? s.as(Publisher.class) : s.as(Subscriber.class);
            s.initializeListener(args.length == 3 ? args[1] : null, args.length == 3 ? args[2] : null);
            return result;
        }
        private Object endpoint(Object[] args, boolean writer) {
            State t = state(args[0]); t.requireOpen();
            if ((t.kind != Kind.TOPIC && (writer || t.kind != Kind.FILTER_TOPIC)) || t.root != root) throw new IllegalArgumentException("Topic belongs to another participant");
            if (t.builtinKind != 0) throw new Errors.Precondition(env, "Use the built-in subscriber's existing readers");
            Class<?> api = writer ? DataWriterQos.class : DataReaderQos.class;
            EntityQos<?> value = defaultQos(api);
            if (args.length > 1) { Policies.requireOwned(env, args[1]); value = (EntityQos<?>) args[1]; }
            if (args.length == 4) validateListenerArguments(args[2], args[3]);
            State s = new State(env, writer ? Kind.WRITER : Kind.READER, this,
                    Native.endpointConfigured(handle, t.handle, writer, Policies.nativeQos(value)), value, t.name, t.type);
            s.topic = t;
            Object result = writer ? s.as(DataWriter.class) : s.as(DataReader.class);
            s.initializeListener(args.length == 4 ? args[2] : null, args.length == 4 ? args[3] : null);
            return result;
        }
        private State builtins() {
            if (builtinSubscriber != null && builtinSubscriber.handle != 0) return builtinSubscriber;
            State sub = new State(env, Kind.SUBSCRIBER, this, Native.container(handle, false),
                defaultQos(SubscriberQos.class), null, null);
            sub.as(Subscriber.class);
            try {
                for (int i = 1; i <= 4; i++) {
                    int nativeReader;
                    try { nativeReader = Native.builtinReader(sub.handle, i); }
                    catch (org.eclipse.cyclonedds.core.DdsException error) {
                        if (i == 2 && error.code() == -2) continue;
                        throw error;
                    }
                    var support = new CycloneTypeSupport<>(env, i);
                    State t = new State(env, Kind.TOPIC, this, Native.readerTopic(nativeReader),
                        defaultQos(TopicQos.class), Discovery.NAMES.get(i-1), support);
                    t.borrowed = true; t.builtinKind = i; t.as(Topic.class);
                    State r = new State(env, Kind.READER, sub, nativeReader,
                        Discovery.qos(env, DataReaderQos.class, Native.getQos(nativeReader)), t.name, support);
                    r.topic = t; r.builtinKind = i; r.as(DataReader.class); r.initializeListener(null, null);
                }
                sub.initializeListener(null, null); builtinSubscriber = sub; return sub;
            } catch (RuntimeException error) { sub.close(true); throw error; }
        }
        private State discoveryReader(int topicKind) {
            for (State child : builtins().children) if (child.builtinKind == topicKind && child.handle != 0) return child;
            if (topicKind == 2 && builtins().children.stream().noneMatch(child -> child.builtinKind == 2))
                throw new UnsupportedOperationException("Topic discovery is disabled in this Cyclone DDS core");
            throw new Errors.Precondition(env, "Built-in reader is closed");
        }
        private Set<InstanceHandle> discoveredHandles(int topicKind) {
            State reader = discoveryReader(topicKind);
            Set<InstanceHandle> result = new LinkedHashSet<>();
            long own = Native.instanceHandle(handle);
            for (DiscoveryData sample : Native.builtinRead(reader.handle, topicKind, 65536, 0, 127, 0, false))
                if (sample.info().validData() && (topicKind != 1 || sample.info().instanceHandle() != own))
                    result.add(new Values.Handle(env, sample.info().instanceHandle()).immutableCopy());
            return Collections.unmodifiableSet(result);
        }
        private Object discoveredData(int topicKind, InstanceHandle instance) {
            long value = Values.handleValue(instance, env);
            if (value == 0) throw new IllegalArgumentException("A non-nil discovery handle is required");
            State reader = discoveryReader(topicKind);
            var samples = Native.builtinRead(reader.handle, topicKind, 1, 0, 127, value, false);
            if (samples.length == 0 || !samples[0].info().validData()) throw new Errors.Precondition(env, "Unknown discovery handle");
            return Discovery.data(env, samples[0]);
        }
        private Object lookup(Object key, String operation) {
            Kind wanted = operation.equals("lookupTopicDescription") ? Kind.TOPIC : operation.equals("lookupDataWriter") ? Kind.WRITER : Kind.READER;
            String target = key instanceof String text ? text : state(key).name;
            for (State child : children) if ((child.kind == wanted || wanted == Kind.TOPIC && child.kind == Kind.FILTER_TOPIC) && child.handle != 0 && Objects.equals(child.name, target)) {
                child.references++; return child.proxy;
            }
            return null;
        }
        @SuppressWarnings("unchecked") private byte[] encode(Object value) {
            type.getType().cast(Objects.requireNonNull(value));
            var representation = (DataRepresentation) qos.get(DataRepresentation.class);
            var cdr = new CdrWriter(kind == Kind.WRITER && representation != null && representation.getValue().get(0) == 0 ? 1 : 2);
            ((CycloneTypeSupport<Object>) type).binding().codec().write(cdr, value);
            return cdr.toByteArray();
        }
        @SuppressWarnings("unchecked") private Object keyValue(Object[] args) throws ReflectiveOperationException {
            if (builtinKind != 0) {
                long instance = Values.handleValue((InstanceHandle)args[args.length-1], env);
                if (instance == 0) throw new IllegalArgumentException("A non-nil instance handle is required");
                int[] key = builtinKeys.get(instance);
                if (key == null) {
                    DiscoveryData data = Native.builtinKey(handle, builtinKind, instance);
                    key = data.key(); builtinKeys.put(instance, key.clone());
                }
                Object holder = args.length == 1 ? Discovery.empty(env, builtinKind) : Objects.requireNonNull(args[0]);
                return Discovery.copyKey(holder, Discovery.withKey(env, builtinKind, key));
            }
            Object holder = args.length == 1 ? type.getType().getConstructor().newInstance() : Objects.requireNonNull(args[0]);
            InstanceHandle instance = (InstanceHandle) args[args.length - 1];
            long value = Values.handleValue(instance, env);
            if (value == 0) throw new IllegalArgumentException("A non-nil instance handle is required");
            var codec = ((CycloneTypeSupport<Object>) type).codec();
            Object result = codec.read(new CdrReader(Native.keyValue(handle, value, encode(holder))));
            if (type.binding().hasKey()) codec.copyKeys(holder, result);
            return holder;
        }
        private long timestamp(Object[] args, int offset) {
            if (args.length == offset) return -1;
            if (args[offset] instanceof Time time) {
                if (!time.isValid()) throw new IllegalArgumentException("Invalid timestamp");
                return time.getTime(TimeUnit.NANOSECONDS);
            }
            long value = (Long) args[offset];
            if (value < 0) throw new IllegalArgumentException("Invalid timestamp");
            return Values.nanos(value, (TimeUnit) args[offset + 1]);
        }
        private void write(Object[] args) {
            byte[] cdr = encode(args[0]);
            int offset = 1;
            if (args.length > 1 && args[1] instanceof InstanceHandle instance) {
                long expected = Values.handleValue(instance, env);
                if (expected != 0 && Native.instanceOperation(handle, cdr, false) != expected)
                    throw new Errors.Precondition(env, "Instance handle does not identify this sample");
                offset++;
            }
            long time = timestamp(args, offset);
            if (time < 0) Native.write(handle, cdr); else Native.writeAt(handle, cdr, time);
        }
        private void dispose(Object[] args, boolean unregister) {
            long instance = Values.handleValue((InstanceHandle) args[0], env);
            if (args.length > 1 && args[1] != null) {
                byte[] cdr = encode(args[1]);
                long inferred = Native.instanceOperation(handle, cdr, instance == 0);
                if (instance != 0 && instance != inferred) throw new Errors.Precondition(env, "Instance handle does not identify this sample");
                instance = inferred;
            }
            if (instance == 0) throw new IllegalArgumentException("Instance handle or sample is required");
            Native.dispose(handle, instance, unregister, timestamp(args, args.length > 1 ? 2 : 1));
        }
        private long duration(Object[] args) {
            if (args[0] instanceof Duration duration) return duration.getDuration(TimeUnit.NANOSECONDS);
            long amount = (Long) args[0];
            if (amount < 0) throw new IllegalArgumentException("Negative timeout");
            return amount == Long.MAX_VALUE ? Long.MAX_VALUE : Values.nanos(amount, (TimeUnit) args[1]);
        }
        @SuppressWarnings("unchecked") private Object samples(Method method, Object[] args, boolean take) {
            int maximum = 65536;
            int mask = 127;
            long instance = 0;
            boolean next = false;
            int source = handle;
            Query<?> query = null;
            List<Sample<Object>> destination = null;
            boolean list = args.length > 0 && method.getParameterTypes()[0] == List.class;
            if (list) destination = args[0] == null ? new ArrayList<>() : (List<Sample<Object>>) args[0];
            else if (args.length == 1 && args[0] instanceof Integer count) maximum = count;
            Object selector = args.length == 2 ? args[1] : args.length == 1 && !list && !(args[0] instanceof Integer) ? args[0] : null;
            if (selector != null) {
                if (!(selector instanceof ReaderSelector<?> selection)) throw new IllegalArgumentException("Foreign reader selector");
                var settings = selection.settings(proxy);
                maximum = settings.maximum(); mask = settings.state().mask(); next = settings.next();
                instance = Values.handleValue(settings.instance(), env);
                if (settings.expression() != null) {
                    query = (Query<?>) selection.condition(settings); query.checkFailure(); source = query.resource().handle();
                }
                // The normative list+selector overload is bounded by the list's current length.
                if (list && args[0] != null) maximum = maximum == ResourceLimits.LENGTH_UNLIMITED
                        ? destination.size() : Math.min(maximum, destination.size());
            } else if (args.length == 2 || (args.length == 1 && !list && !(args[0] instanceof Integer)))
                throw new IllegalArgumentException("Selector is required");
            if (maximum == ResourceLimits.LENGTH_UNLIMITED) maximum = 65536;
            if (maximum < 0 || maximum > 65536) throw new IllegalArgumentException("Sample limit must be in [0, 65536]");
            var codec = ((CycloneTypeSupport<Object>) type).codec();
            List<Sample<Object>> values = new ArrayList<>();
            WireSample[] batch = builtinKind != 0 || maximum == 0 || mask == 0 ? new WireSample[0] : next ? Native.selectNext(source, maximum, take, mask, instance) : Native.select(source, maximum, take, mask, instance);
            if (builtinKind != 0 && maximum != 0 && mask != 0) for (DiscoveryData data : Native.builtinRead(source, builtinKind, maximum, take ? 2 : 1, mask, instance, next)) {
                builtinKeys.put(data.info().instanceHandle(), data.key().clone());
                values.add(new Samples.Data<>(env, codec, data.info().validData() ? Discovery.data(env, data) : null, data.info()));
            }
            if (query != null) query.checkFailure();
            for (WireSample info : batch) {
                Object data = info.validData() ? codec.read(new CdrReader(info.cdr())) : null;
                values.add(new Samples.Data<>(env, codec, data, info));
            }
            if (list) {
                int existing = destination.size();
                for (int i = 0; i < values.size(); i++) {
                    Sample<Object> value = values.get(i);
                    if (i < existing) {
                        if (destination.get(i) instanceof Samples.Data<?> old && old.ownedBy(env, codec)) {
                            ((Samples.Data<Object>) old).replace((Samples.Data<Object>) value); value = (Sample<Object>) old;
                        }
                        destination.set(i, value);
                    } else destination.add(value);
                }
                while (destination.size() > values.size()) destination.remove(destination.size() - 1);
                return destination;
            }
            loans++;
            return new Samples.Loan<>(values, () -> { synchronized (root) { loans--; } });
        }
        @SuppressWarnings("unchecked") private boolean nextSample(Object target, boolean take) {
            var codec = ((CycloneTypeSupport<Object>) type).codec();
            if (!(target instanceof Samples.Data<?> sample) || !sample.ownedBy(env, codec))
                throw new IllegalArgumentException("Expected a sample of this type returned by this provider");
            if (builtinKind != 0) {
                var batch = Native.builtinRead(handle, builtinKind, 1, take ? 2 : 1, 2 | 4 | 8 | 16 | 32 | 64, 0, false);
                if (batch.length == 0) return false;
                var data = batch[0];
                builtinKeys.put(data.info().instanceHandle(), data.key().clone());
                ((Samples.Data<Object>)sample).replace(new Samples.Data<>(env, codec, data.info().validData() ? Discovery.data(env, data) : null, data.info()));
                return true;
            }
            WireSample[] batch = Native.select(handle, 1, take, 2 | 4 | 8 | 16 | 32 | 64, 0);
            if (batch.length == 0) return false;
            WireSample info = batch[0];
            Object value = info.validData() ? codec.read(new CdrReader(info.cdr())) : null;
            ((Samples.Data<Object>) sample).replace(new Samples.Data<>(env, codec, value, info));
            return true;
        }
        private boolean hasLoans() { return loans != 0 || children.stream().anyMatch(State::hasLoans); }
        private boolean usesTopic(State target) {
            return handle != 0 && (topic == target || children.stream().anyMatch(s -> s.usesTopic(target)));
        }
        private void closeChildren() {
            if (hasLoans()) throw new Errors.Precondition(env, "Close sample iterators before closing their reader or parent");
            for (State child : List.copyOf(children)) if (child.kind != Kind.TOPIC && child.kind != Kind.FILTER_TOPIC) child.close(true);
            for (State child : List.copyOf(children)) if (child.kind == Kind.FILTER_TOPIC) child.close(true);
            for (State child : List.copyOf(children)) if (child.kind == Kind.TOPIC) child.close(true);
        }
        private void close(boolean force) {
            if (handle == 0) return;
            if (!force && references > 1) { references--; return; }
            if (hasLoans()) throw new Errors.Precondition(env, "Close sample iterators before closing their reader or parent");
            if ((kind == Kind.TOPIC || kind == Kind.FILTER_TOPIC) && root.usesTopic(this)) throw new Errors.Precondition(env, "Topic is still used by a reader or writer");
            closeChildren();
            for (var condition : List.copyOf(conditions)) closeReadCondition(condition);
            if (!borrowed) Native.delete(handle); handle = 0; builtinKeys.clear();
            if (filterContext != 0) { Native.filterFree(filterContext); filterContext = 0; }
            dispatch.configure(new ListenerDispatch.Target[13]);
            if (listenerContext != 0) { Native.listenerFree(listenerContext); listenerContext = 0; }
            if (statusCondition != null) statusCondition.invalidate();
            if (parent != null) parent.children.remove(this);
            else dispatch.shutdown();
        }
    }
}
