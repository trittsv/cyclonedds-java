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

import java.util.*;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.core.status.*;

@SuppressWarnings("rawtypes")
final class StatusValues {
    private StatusValues() {}
    static Status create(ServiceEnvironment env, int kind, long[] v) {
        return switch (kind) {
            case 0 -> new InconsistentTopic(env, v);
            case 1 -> new OfferedDeadlineMissed(env, v);
            case 2 -> new RequestedDeadlineMissed(env, v);
            case 3 -> new OfferedIncompatibleQos(env, v);
            case 4 -> new RequestedIncompatibleQos(env, v);
            case 5 -> new SampleLost(env, v);
            case 6 -> new SampleRejected(env, v);
            case 7 -> new DataOnReaders(env, v);
            case 8 -> new DataAvailable(env, v);
            case 9 -> new LivelinessLost(env, v);
            case 10 -> new LivelinessChanged(env, v);
            case 11 -> new PublicationMatched(env, v);
            case 12 -> new SubscriptionMatched(env, v);
            default -> throw new IllegalArgumentException("Unknown status");
        };
    }
    private static Class<? extends QosPolicy> policy(int id) {
        return switch (id) {
            case 1 -> UserData.class;
            case 2 -> Durability.class;
            case 3 -> Presentation.class;
            case 4 -> Deadline.class;
            case 5 -> LatencyBudget.class;
            case 6 -> Ownership.class;
            case 7 -> OwnershipStrength.class;
            case 8 -> Liveliness.class;
            case 9 -> TimeBasedFilter.class;
            case 10 -> Partition.class;
            case 11 -> Reliability.class;
            case 12 -> DestinationOrder.class;
            case 13 -> History.class;
            case 14 -> ResourceLimits.class;
            case 15 -> EntityFactory.class;
            case 16 -> WriterDataLifecycle.class;
            case 17 -> ReaderDataLifecycle.class;
            case 18 -> TopicData.class;
            case 19 -> GroupData.class;
            case 20 -> TransportPriority.class;
            case 21 -> Lifespan.class;
            case 22 -> DurabilityService.class;
            case 24 -> TypeConsistencyEnforcement.class;
            case 25 -> DataRepresentation.class;
            default -> null;
        };
    }
    private static final class InconsistentTopic extends InconsistentTopicStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        InconsistentTopic(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
    }
    private static final class OfferedDeadlineMissed extends OfferedDeadlineMissedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        OfferedDeadlineMissed(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public InstanceHandle getLastInstanceHandle() { return new Values.Handle(env, v[2]).immutableCopy(); }
    }
    private static final class RequestedDeadlineMissed extends RequestedDeadlineMissedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        RequestedDeadlineMissed(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public InstanceHandle getLastInstanceHandle() { return new Values.Handle(env, v[2]).immutableCopy(); }
    }
    private static final class OfferedIncompatibleQos extends OfferedIncompatibleQosStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        OfferedIncompatibleQos(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public Class<? extends QosPolicy> getLastPolicyClass() { return policy((int) v[2]); }
        public Set<QosPolicyCount> getPolicies() { throw Policies.unsupported("Per-policy incompatibility counters are not exposed by the Cyclone C API"); }
    }
    private static final class RequestedIncompatibleQos extends RequestedIncompatibleQosStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        RequestedIncompatibleQos(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public Class<? extends QosPolicy> getLastPolicyClass() { return policy((int) v[2]); }
        public Set<QosPolicyCount> getPolicies() { throw Policies.unsupported("Per-policy incompatibility counters are not exposed by the Cyclone C API"); }
    }
    private static final class SampleLost extends SampleLostStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        SampleLost(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
    }
    private static final class SampleRejected extends SampleRejectedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        SampleRejected(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public Kind getLastReason() { return Kind.values()[(int) v[2]]; }
        public InstanceHandle getLastInstanceHandle() { return new Values.Handle(env, v[3]).immutableCopy(); }
    }
    private static final class DataOnReaders extends DataOnReadersStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        DataOnReaders(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    private static final class DataAvailable extends DataAvailableStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        DataAvailable(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    private static final class LivelinessLost extends LivelinessLostStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        LivelinessLost(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
    }
    private static final class LivelinessChanged extends LivelinessChangedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        LivelinessChanged(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getAliveCount() { return (int) v[0]; }
        public int getNotAliveCount() { return (int) v[1]; }
        public int getAliveCountChange() { return (int) v[2]; }
        public int getNotAliveCountChange() { return (int) v[3]; }
        public InstanceHandle getLastPublicationHandle() { return new Values.Handle(env, v[4]).immutableCopy(); }
    }
    private static final class PublicationMatched extends PublicationMatchedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        PublicationMatched(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public int getCurrentCount() { return (int) v[2]; }
        public int getCurrentCountChange() { return (int) v[3]; }
        public InstanceHandle getLastSubscriptionHandle() { return new Values.Handle(env, v[4]).immutableCopy(); }
    }
    private static final class SubscriptionMatched extends SubscriptionMatchedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] v;
        SubscriptionMatched(ServiceEnvironment env, long[] values) { this.env = env; this.v = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) v[0]; }
        public int getTotalCountChange() { return (int) v[1]; }
        public int getCurrentCount() { return (int) v[2]; }
        public int getCurrentCountChange() { return (int) v[3]; }
        public InstanceHandle getLastPublicationHandle() { return new Values.Handle(env, v[4]).immutableCopy(); }
    }
}
