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
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.core.status.PublicationMatchedStatus;
import org.omg.dds.core.status.SubscriptionMatchedStatus;
import org.omg.dds.domain.DomainParticipantFactory;

public final class OmgQosTest {
    private OmgQosTest() {}
    interface Operation { void run() throws Exception; }
    private static void rejects(Class<? extends Throwable> type, Operation operation) throws Exception {
        try { operation.run(); } catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError(e); }
        throw new AssertionError("Expected " + type.getName());
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgQosTest.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(env);
        var policy = PolicyFactory.getPolicyFactory(env);
        var transientLocal = policy.Durability().withTransientLocal();
        assert transientLocal.getKind() == Durability.Kind.TRANSIENT_LOCAL;
        assert policy.Durability().getKind() == Durability.Kind.VOLATILE;
        assert transientLocal.compareTo(policy.Durability()) > 0;
        var resources = policy.ResourceLimits().withMaxSamples(16).withMaxInstances(2).withMaxSamplesPerInstance(8);
        rejects(IllegalArgumentException.class, () -> resources.withMaxSamples(0));
        rejects(IllegalArgumentException.class, () -> resources.withMaxInstances(-2));
        var deadline = policy.Deadline().withPeriod(2, TimeUnit.SECONDS);
        assert deadline.getPeriod().getDuration(TimeUnit.SECONDS) == 2;
        assert deadline.compareTo(policy.Deadline()) > 0;
        assert policy.Liveliness().withManualByTopic().getKind() == Liveliness.Kind.MANUAL_BY_TOPIC;
        assert policy.Ownership().withExclusive().getKind() == Ownership.Kind.EXCLUSIVE;
        assert policy.DestinationOrder().withSourceTimestamp().compareTo(policy.DestinationOrder()) > 0;
        assert policy.LatencyBudget().withDuration(1, TimeUnit.MILLISECONDS).getDuration().getDuration(TimeUnit.MILLISECONDS) == 1;
        assert policy.TransportPriority().withValue(7).getValue() == 7;
        assert policy.ReaderDataLifecycle().getAutoPurgeNoWriterSamplesDelay().isInfinite();
        assert policy.ReaderDataLifecycle().withAutoPurgeDisposedSamplesDelay(10, TimeUnit.MILLISECONDS).getAutoPurgeDisposedSamplesDelay().getDuration(TimeUnit.MILLISECONDS) == 10;
        assert policy.Representation().getValue().equals(List.of((short) 2));
        assert policy.EntityFactory().isAutoEnableCreatedEntities();
        var defaultQos = factory.getDefaultParticipantQos();
        assert defaultQos.equals(factory.getDefaultParticipantQos());
        assert defaultQos.hashCode() == factory.getDefaultParticipantQos().hashCode();
        rejects(UnsupportedOperationException.class, () -> factory.createParticipant(81, defaultQos.withPolicy(policy.EntityFactory().withAutoEnableCreatedEntities(false)), null, null));
        var ws = WaitSet.newWaitSet(env);
        try (var participant = factory.createParticipant(81);
             var topic = participant.createTopic("OmgDurability", Message.class);
             var publisher = participant.createPublisher()) {
            var qos = publisher.getDefaultDataWriterQos().withPolicies(transientLocal,
                policy.History().withKeepLast(8), resources, policy.DurabilityService().withHistoryDepth(8), policy.Lifespan().withDuration(30, TimeUnit.SECONDS));
            try (var writer = publisher.createDataWriter(topic, qos)) {
                for (int i = 0; i < 3; i++) writer.write(new Message(42, "retained " + i));
                try (var subscriber = participant.createSubscriber();
                     var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(
                        transientLocal, policy.Reliability().withReliable(), policy.History().withKeepLast(8), resources));
                     var condition = reader.select().getCondition()) {
                    ws.attachCondition(condition);
                    var matched = reader.getStatusCondition();
                    matched.setEnabledStatuses(Set.of(SubscriptionMatchedStatus.class));
                    ws.attachCondition(matched);
                    while (reader.getSubscriptionMatchedStatus().getCurrentCount() == 0) ws.waitForConditions(5, TimeUnit.SECONDS);
                    ws.detachCondition(matched);
                    reader.waitForHistoricalData(5, TimeUnit.SECONDS);
                    var received = new ArrayList<org.omg.dds.sub.Sample<Message>>();
                    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (received.size() < 3) {
                        ws.waitForConditions(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                        received.addAll(reader.select().take(null));
                    }
                    assert received.size() == 3 : received.size();
                    assert received.get(0).getData().get_text().equals("retained 0");
                    assert received.get(2).getData().get_text().equals("retained 2");
                    ws.detachCondition(condition);
                    rejects(ImmutablePolicyException.class, () -> writer.setQos(qos.withPolicy(policy.Durability().withVolatile())));
                    assert writer.getQos().getDurability().equals(transientLocal);
                }
            }
            try (var readerOwner = participant.createSubscriber(); var reader = readerOwner.createDataReader(topic)) {
                var before = reader.getQos();
                rejects(IllegalArgumentException.class, () -> readerOwner.createDataReader(topic,
                    before.withPolicy(policy.Representation().withValue((short) 1))));
                rejects(UnsupportedOperationException.class, () -> reader.setQos(before.withPolicy(policy.ReaderDataLifecycle().withAutoPurgeDisposedSamplesDelay(1, TimeUnit.MILLISECONDS))));
                assert reader.getQos().equals(before);
                rejects(UnsupportedOperationException.class, () -> reader.setQos(before.withPolicy(policy.TypeConsistency().withExactNameTypeConsistency())));
                rejects(UnsupportedOperationException.class, () -> reader.setQos(before.withPolicy(policy.TypeConsistency().withDeclaredTypeConsistency())));
                rejects(ImmutablePolicyException.class, () -> reader.setQos(before.withPolicy(policy.Representation().withValue((short) 0))));
                rejects(ImmutablePolicyException.class, () -> reader.setQos(before.withPolicy(policy.DestinationOrder().withSourceTimestamp())));
                assert reader.getQos().equals(before);
            }
            try (var writer = publisher.createDataWriter(topic)) {
                var before = writer.getQos();
                rejects(UnsupportedOperationException.class, () -> writer.setQos(before.withPolicy(policy.Deadline().withPeriod(1, TimeUnit.SECONDS))));
                assert writer.getQos().equals(before);
                rejects(UnsupportedOperationException.class, () -> writer.setQos(before.withPolicy(policy.LatencyBudget().withDuration(1, TimeUnit.MILLISECONDS))));
                assert writer.getQos().equals(before);
                writer.setQos(before.withPolicy(policy.TransportPriority().withValue(7)));
                assert writer.getQos().getTransportPriority().getValue() == 7;
            }
            var invalid = publisher.getDefaultDataWriterQos().withPolicies(policy.History().withKeepLast(8),
                policy.ResourceLimits().withMaxSamplesPerInstance(2));
            rejects(InconsistentPolicyException.class, () -> publisher.createDataWriter(topic, invalid));
            var partition = policy.Partition().withName(List.of("alpha"));
            participant.setDefaultPublisherQos(participant.getDefaultPublisherQos().withPolicy(partition));
            try (var isolated = participant.createPublisher(); var subscriber = participant.createSubscriber();
                 var writer = isolated.createDataWriter(topic); var reader = subscriber.createDataReader(topic)) {
                assert reader.getParent() == subscriber;
                var condition = writer.getStatusCondition();
                condition.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
                ws.attachCondition(condition);
                rejects(TimeoutException.class, () -> ws.waitForConditions(50, TimeUnit.MILLISECONDS));
                assert writer.getPublicationMatchedStatus().getCurrentCount() == 0;
                rejects(UnsupportedOperationException.class, () -> isolated.setQos(isolated.getQos().withPolicy(policy.Partition().withName(List.of("")))));
                assert isolated.getQos().getPartition().equals(partition);
                try (var matching = participant.createPublisher(participant.getDefaultPublisherQos().withPolicy(
                        policy.Partition().withName(List.of(""))), null, null);
                     var matchingWriter = matching.createDataWriter(topic)) {
                    var matchingCondition = matchingWriter.getStatusCondition();
                    matchingCondition.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
                    ws.attachCondition(matchingCondition);
                    if (matchingWriter.getPublicationMatchedStatus().getCurrentCount() == 0) ws.waitForConditions(5, TimeUnit.SECONDS);
                    assert matchingWriter.getPublicationMatchedStatus().getCurrentCount() == 1;
                    ws.detachCondition(matchingCondition);
                }
                ws.detachCondition(condition);
                writer.assertLiveliness();
            }
            var topicQos = participant.getDefaultTopicQos().withPolicies(transientLocal, deadline);
            var copied = publisher.copyFromTopicQos(publisher.getDefaultDataWriterQos(), topicQos);
            assert copied.getDurability().equals(transientLocal) && copied.getDeadline().equals(deadline);
            try (var configured = participant.createTopic("OmgConfiguredTopic", Message.class, topicQos, null, null)) {
                assert configured.getQos().getDurability().equals(transientLocal);
            }
        } finally { ((AutoCloseable) ws).close(); }
        System.out.println("OMG QoS: transient-local late joiner, resource validation, partitions, updates and topic propagation passed");
    }
}
