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

package org.eclipse.cyclonedds.tests;

import HelloWorld.Message;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.*;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;

/** Observable DDS behavior, using only the normative Java API. */
public final class OmgQosBehaviorTest {
    private OmgQosBehaviorTest() {}
    private static void until(ServiceEnvironment env, Condition condition, BooleanSupplier ready) throws Exception {
        var ws = WaitSet.newWaitSet(env);
        try {
            ws.attachCondition(condition);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.getAsBoolean()) ws.waitForConditions(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally { ws.detachCondition(condition); ((AutoCloseable) ws).close(); }
    }
    private static void matched(ServiceEnvironment env, DataWriter<?> writer) throws Exception {
        var condition = writer.getStatusCondition(); condition.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
        until(env, condition, () -> writer.getPublicationMatchedStatus().getCurrentCount() > 0);
    }
    private static List<Message> take(ServiceEnvironment env, DataReader<Message> reader) throws Exception {
        try (var condition = reader.select().getCondition()) {
            until(env, condition, condition::getTriggerValue);
            return reader.select().take(null).stream().map(Sample::getData).filter(Objects::nonNull).toList();
        }
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgQosBehaviorTest.class.getClassLoader());
        var policies = PolicyFactory.getPolicyFactory(env);
        try (var p = DomainParticipantFactory.getInstance(env).createParticipant(92);
             var topic = p.createTopic("QosBehavior", Message.class);
             var pub = p.createPublisher(); var sub = p.createSubscriber()) {
            var wq = pub.getDefaultDataWriterQos().withPolicies(policies.Reliability().withReliable(), policies.History().withKeepAll());
            var rq = sub.getDefaultDataReaderQos().withPolicies(policies.Reliability().withReliable(), policies.History().withKeepAll());
            // SOURCE order rejects an older update of the same instance; reception order admits it.
            try (var writer = pub.createDataWriter(topic, wq.withPolicy(policies.DestinationOrder().withSourceTimestamp()));
                 var source = sub.createDataReader(topic, rq.withPolicy(policies.DestinationOrder().withSourceTimestamp()));
                 var reception = sub.createDataReader(topic, rq)) {
                var condition = writer.getStatusCondition(); condition.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
                until(env, condition, () -> writer.getPublicationMatchedStatus().getCurrentCount() == 2);
                long now = System.currentTimeMillis() * 1_000_000L;
                writer.write(new Message(1, "newer"), now + 30, TimeUnit.NANOSECONDS);
                writer.write(new Message(1, "older"), now + 10, TimeUnit.NANOSECONDS);
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, source).stream().map(Message::get_text).toList().equals(List.of("newer"));
                assert take(env, reception).stream().map(Message::get_text).toList().equals(List.of("newer", "older"));
            }
            // Time-based filtering uses source timestamps; there are no scheduling sleeps here.
            try (var writer = pub.createDataWriter(topic, wq);
                 var reader = sub.createDataReader(topic, rq.withPolicy(policies.TimeBasedFilter().withMinimumSeparation(100, TimeUnit.MILLISECONDS)))) {
                matched(env, writer); long now = System.currentTimeMillis() * 1_000_000L;
                writer.write(new Message(2, "first"), now, TimeUnit.NANOSECONDS);
                writer.write(new Message(2, "suppressed"), now + 50_000_000, TimeUnit.NANOSECONDS);
                writer.write(new Message(2, "later"), now + 110_000_000, TimeUnit.NANOSECONDS);
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, reader).stream().map(Message::get_text).toList().equals(List.of("first", "later"));
                var before = reader.getQos();
                reader.setQos(before); // An unchanged interval remains legal.
                try {
                    reader.setQos(before.withPolicy(policies.TimeBasedFilter().withMinimumSeparation(0, TimeUnit.NANOSECONDS)));
                    throw new AssertionError("Unsupported enabled filter update accepted");
                } catch (UnsupportedOperationException expected) { }
                assert reader.getQos().equals(before);
                writer.write(new Message(2, "still suppressed"), now + 120_000_000, TimeUnit.NANOSECONDS);
                writer.write(new Message(2, "original filter"), now + 220_000_000, TimeUnit.NANOSECONDS);
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, reader).stream().map(Message::get_text).toList().equals(List.of("original filter"));
            }
            var exclusive = policies.Ownership().withExclusive();
            var noDispose = policies.WriterDataLifecycle().withAutDisposeUnregisteredInstances(false);
            try (var strong = pub.createDataWriter(topic, wq.withPolicies(exclusive, noDispose, policies.OwnershipStrength().withValue(20)));
                 var weak = pub.createDataWriter(topic, wq.withPolicies(exclusive, noDispose, policies.OwnershipStrength().withValue(10)));
                 var reader = sub.createDataReader(topic, rq.withPolicy(exclusive))) {
                matched(env, strong); matched(env, weak);
                strong.write(new Message(3, "owner")); strong.waitForAcknowledgments(5, TimeUnit.SECONDS);
                weak.write(new Message(3, "suppressed")); weak.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, reader).stream().map(Message::get_text).toList().equals(List.of("owner"));
                weak.setQos(weak.getQos().withPolicy(policies.OwnershipStrength().withValue(30)));
                weak.write(new Message(3, "new owner")); weak.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, reader).get(0).get_text().equals("new owner");
                strong.write(new Message(3, "suppressed again")); strong.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert reader.select().take(null).isEmpty();
            }
            var deadline = policies.Deadline().withPeriod(100, TimeUnit.MILLISECONDS);
            try (var writer = pub.createDataWriter(topic, wq.withPolicy(deadline));
                 var reader = sub.createDataReader(topic, rq.withPolicy(deadline))) {
                matched(env, writer);
                var first = writer.registerInstance(new Message(4, "first instance"));
                var second = writer.registerInstance(new Message(5, "second instance"));
                writer.write(new Message(4, "first instance")); writer.write(new Message(5, "second instance"));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                var offered = writer.getStatusCondition(); offered.setEnabledStatuses(Set.of(OfferedDeadlineMissedStatus.class));
                until(env, offered, () -> writer.getOfferedDeadlineMissedStatus().getTotalCount() >= 2);
                var requested = reader.getStatusCondition(); requested.setEnabledStatuses(Set.of(RequestedDeadlineMissedStatus.class));
                until(env, requested, () -> reader.getRequestedDeadlineMissedStatus().getTotalCount() >= 2);
                var last = writer.getOfferedDeadlineMissedStatus().getLastInstanceHandle();
                assert last.equals(first) || last.equals(second);
                writer.write(new Message(4, "resumed")); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, reader).stream().anyMatch(v -> v.get_text().equals("resumed"));
            }
            var live = policies.Liveliness().withManualByTopic().withLeaseDuration(150, TimeUnit.MILLISECONDS);
            try (var writer = pub.createDataWriter(topic, wq.withPolicy(live));
                 var reader = sub.createDataReader(topic, rq.withPolicy(live))) {
                matched(env, writer); writer.write(new Message(6, "alive"));
                var condition = reader.getStatusCondition(); condition.setEnabledStatuses(Set.of(LivelinessChangedStatus.class));
                until(env, condition, () -> reader.getLivelinessChangedStatus().getAliveCount() == 1);
                until(env, condition, () -> reader.getLivelinessChangedStatus().getNotAliveCount() == 1);
                writer.assertLiveliness(); writer.write(new Message(6, "alive again"));
                until(env, condition, () -> reader.getLivelinessChangedStatus().getAliveCount() == 1);
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert take(env, reader).stream().anyMatch(v -> v.get_text().equals("alive again"));
            }
        }
        // Assignable schemas may match even when their TypeIdentifiers differ.
        // Exact-type mode must reject the same pair, with a standard incompatibility status.
        try (var wp = DomainParticipantFactory.getInstance(env).createParticipant(93);
             var rp = DomainParticipantFactory.getInstance(env).createParticipant(93);
             var wt = wp.createTopic("QosTypeConsistency", QosEvolution.Extended.class);
             var rt = rp.createTopic("QosTypeConsistency", QosEvolution.Original.class);
             var pub = wp.createPublisher(); var sub = rp.createSubscriber();
             var exact = sub.createDataReader(rt, sub.getDefaultDataReaderQos().withPolicies(policies.Reliability().withReliable(), policies.TypeConsistency().withExactTypeTypeConsistency()));
             var assignable = sub.createDataReader(rt, sub.getDefaultDataReaderQos().withPolicies(policies.Reliability().withReliable(), policies.TypeConsistency().withAssignableTypeConsistency()));
             var writer = pub.createDataWriter(wt)) {
            matched(env, writer);
            var condition = exact.getStatusCondition(); condition.setEnabledStatuses(Set.of(RequestedIncompatibleQosStatus.class));
            until(env, condition, () -> exact.getRequestedIncompatibleQosStatus().getTotalCount() > 0);
            assert exact.getRequestedIncompatibleQosStatus().getLastPolicyClass() == TypeConsistencyEnforcement.class;
            assert exact.getMatchedPublications().isEmpty();
            writer.write(new QosEvolution.Extended(17, 23)); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            try (var ready = assignable.select().getCondition()) {
                until(env, ready, ready::getTriggerValue);
                assert assignable.select().take(null).get(0).getData().get_value() == 17;
            }
        }
        System.out.println("QoS behavior: destination order, filtering/update, exclusive ownership/update, per-instance deadlines and liveliness recovery passed");
    }
}
