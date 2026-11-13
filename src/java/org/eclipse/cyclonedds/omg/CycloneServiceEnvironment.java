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
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.type.TypeSupport;
import org.omg.dds.type.builtin.KeyedBytes;
import org.omg.dds.type.builtin.KeyedString;
import org.omg.dds.type.dynamic.DynamicDataFactory;
import org.omg.dds.type.dynamic.DynamicTypeFactory;

/** Cyclone provider selected through the normative ServiceEnvironment factory. */
public final class CycloneServiceEnvironment extends ServiceEnvironment {
    private final ServiceProviderInterface spi = new Provider();
    public CycloneServiceEnvironment() {}
    public CycloneServiceEnvironment(Map<String, Object> configuration) {
        if (configuration != null && !configuration.isEmpty())
            throw new IllegalArgumentException("No ServiceEnvironment configuration keys are supported yet");
    }
    @Override public ServiceProviderInterface getSPI() { return spi; }

    private final class Provider implements ServiceProviderInterface {
        private final PolicyFactory policies = new Policies.Factory(CycloneServiceEnvironment.this);
        private final DomainParticipantFactory participants = new ParticipantFactory(CycloneServiceEnvironment.this);
        public DomainParticipantFactory getParticipantFactory() { return participants; }
        public PolicyFactory getPolicyFactory() { return policies; }
        public <T> TypeSupport<T> newTypeSupport(Class<T> type, String name) { return new CycloneTypeSupport<>(CycloneServiceEnvironment.this, type, name); }
        public Duration newDuration(long value, TimeUnit unit) { return new Values.DdsDuration(CycloneServiceEnvironment.this, value, unit); }
        public Duration infiniteDuration() { return newDuration(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
        public Duration zeroDuration() { return newDuration(0, TimeUnit.NANOSECONDS); }
        public ModifiableTime newTime(long value, TimeUnit unit) { return new Values.DdsTime(CycloneServiceEnvironment.this, value, unit); }
        public Time invalidTime() { return new Values.DdsTime(CycloneServiceEnvironment.this, -1, TimeUnit.NANOSECONDS).immutableCopy(); }
        public ModifiableInstanceHandle newInstanceHandle() { return new Values.Handle(CycloneServiceEnvironment.this, 0); }
        public InstanceHandle nilHandle() { return new Values.Handle(CycloneServiceEnvironment.this, 0).immutableCopy(); }
        public Set<Class<? extends Status>> allStatusKinds() {
            return Set.of(InconsistentTopicStatus.class, OfferedDeadlineMissedStatus.class, RequestedDeadlineMissedStatus.class,
                    OfferedIncompatibleQosStatus.class, RequestedIncompatibleQosStatus.class, SampleLostStatus.class,
                    SampleRejectedStatus.class, LivelinessLostStatus.class, LivelinessChangedStatus.class,
                    PublicationMatchedStatus.class, SubscriptionMatchedStatus.class, DataAvailableStatus.class, DataOnReadersStatus.class);
        }
        public Set<Class<? extends Status>> noStatusKinds() { return Set.of(); }
        public DynamicTypeFactory getTypeFactory() { throw Policies.unsupported("DynamicTypeFactory"); }
        public DynamicDataFactory getDynamicDataFactory() { throw Policies.unsupported("DynamicDataFactory"); }
        public GuardCondition newGuardCondition() { return new Conditions.Guard(CycloneServiceEnvironment.this); }
        public WaitSet newWaitSet() { return new BlockingWaitSet(CycloneServiceEnvironment.this); }
        public QosProvider newQosProvider(String uri, String profile) { throw Policies.unsupported("XML QoS profiles"); }
        public KeyedString newKeyedString() { throw Policies.unsupported("KeyedString"); }
        public KeyedBytes newKeyedBytes() { throw Policies.unsupported("KeyedBytes"); }
    }
}
