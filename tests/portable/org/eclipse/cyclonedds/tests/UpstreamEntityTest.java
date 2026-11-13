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
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.DataReader;

/** Java adaptations of upstream parent, lookup, default-QoS and closure scenarios. */
public final class UpstreamEntityTest {
    private interface Operation { void run() throws Exception; }
    private static void closed(Operation operation) throws Exception {
        try { operation.run(); } catch (AlreadyClosedException expected) { return; }
        throw new AssertionError("Expected AlreadyClosedException");
    }
    @SuppressWarnings("try") // Explicit close exercises reference counts and use-after-close behavior.
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(UpstreamEntityTest.class.getClassLoader());
        var pf = PolicyFactory.getPolicyFactory(env);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(96);
             var topic = participant.createTopic("UpstreamEntities", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic); var reader = subscriber.createDataReader(topic)) {
            switch (args[0]) {
            case "parent-links":
                assert publisher.getParent() == participant && subscriber.getParent() == participant;
                assert topic.getParent() == participant && writer.getParent() == publisher && reader.getParent() == subscriber;
                assert writer.getTopic() == topic && reader.getTopicDescription() == topic;
                assert participant.getDomainId() == 96;
                assert topic.getName().equals("UpstreamEntities") && topic.getTypeName().equals("HelloWorld::Message");
                var handles = Set.of(participant.getInstanceHandle(), topic.getInstanceHandle(), publisher.getInstanceHandle(),
                    subscriber.getInstanceHandle(), writer.getInstanceHandle(), reader.getInstanceHandle());
                assert handles.size() == 6 && handles.stream().noneMatch(InstanceHandle::isNil);
                break;
            case "default-endpoint-qos": {
                var oldWriter = publisher.getDefaultDataWriterQos();
                var oldReader = subscriber.getDefaultDataReaderQos();
                publisher.setDefaultDataWriterQos(oldWriter.withPolicy(pf.History().withKeepLast(7)));
                subscriber.setDefaultDataReaderQos(oldReader.withPolicy(pf.History().withKeepLast(9)));
                try (var nextWriter = publisher.createDataWriter(topic); var nextReader = subscriber.createDataReader(topic)) {
                    assert nextWriter.getQos().getHistory().getDepth() == 7;
                    assert nextReader.getQos().getHistory().getDepth() == 9;
                    assert writer.getQos().equals(oldWriter) && reader.getQos().equals(oldReader);
                    assert oldWriter.getHistory().getDepth() == 1 && oldReader.getHistory().getDepth() == 1;
                }
                break;
            }
            case "default-container-qos": {
                var oldPublisher = participant.getDefaultPublisherQos();
                var oldSubscriber = participant.getDefaultSubscriberQos();
                participant.setDefaultPublisherQos(oldPublisher.withPolicy(pf.Partition().withName(Set.of("publisher-partition"))));
                participant.setDefaultSubscriberQos(oldSubscriber.withPolicy(pf.Partition().withName(Set.of("subscriber-partition"))));
                try (var nextPublisher = participant.createPublisher(); var nextSubscriber = participant.createSubscriber()) {
                    assert nextPublisher.getQos().getPartition().getName().equals(Set.of("publisher-partition"));
                    assert nextSubscriber.getQos().getPartition().getName().equals(Set.of("subscriber-partition"));
                    assert publisher.getQos().equals(oldPublisher) && subscriber.getQos().equals(oldSubscriber);
                    assert nextPublisher.getParent() == participant && nextSubscriber.getParent() == participant;
                }
                break;
            }
            case "lookup-retain": {
                assert publisher.lookupDataWriter("absent") == null;
                assert subscriber.lookupDataReader("absent") == null;
                assert participant.lookupTopicDescription("absent") == null;
                for (int i = 0; i < 20; i++) {
                    var byName = publisher.<Message>lookupDataWriter(topic.getName()); assert byName == writer; byName.close();
                    var byTopic = publisher.lookupDataWriter(topic); assert byTopic == writer; byTopic.close();
                    var readerName = subscriber.<Message>lookupDataReader(topic.getName()); assert readerName == reader; readerName.close();
                    var readerTopic = subscriber.lookupDataReader(topic); assert readerTopic == reader; readerTopic.close();
                    var topicName = participant.<Message>lookupTopicDescription(topic.getName()); assert topicName == topic;
                    ((AutoCloseable)topicName).close();
                }
                assert !writer.getInstanceHandle().isNil() && !reader.getInstanceHandle().isNil();
                writer.close(); reader.close();
                assert publisher.lookupDataWriter(topic) == null && subscriber.lookupDataReader(topic) == null;
                break;
            }
            case "retain-close":
                writer.retain(); writer.close();
                assert writer.getParent() == publisher;
                writer.close(); closed(writer::getQos); closed(() -> writer.write(new Message()));
                reader.retain(); reader.close();
                assert reader.getParent() == subscriber;
                reader.close(); closed(reader::getQos); closed(reader::read);
                break;
            case "close-cascade": {
                var condition = reader.select().getCondition();
                participant.closeContainedEntities();
                closed(writer::getQos); closed(reader::getQos); closed(publisher::getQos); closed(subscriber::getQos);
                closed(topic::getName); closed(condition::getTriggerValue);
                assert participant.getDomainId() == 96;
                assert participant.lookupTopicDescription("UpstreamEntities") == null;
                try (var replacement = participant.createTopic("RecreatedAfterClose", Message.class)) {
                    assert replacement.getName().equals("RecreatedAfterClose");
                }
                break;
            }
            case "matched-endpoint-sets": {
                var additional = new ArrayList<DataReader<Message>>();
                try {
                    for (int i = 0; i < 20; i++) additional.add(subscriber.createDataReader(topic));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (writer.getMatchedSubscriptions().size() != 21) {
                        if (System.nanoTime() > deadline) throw new AssertionError("Endpoint matching timed out");
                        Thread.sleep(1);
                    }
                    var matched = writer.getMatchedSubscriptions();
                    assert matched.size() == 21 && matched.contains(reader.getInstanceHandle());
                    for (var dr : additional) {
                        assert matched.contains(dr.getInstanceHandle());
                        assert dr.getMatchedPublications().equals(Set.of(writer.getInstanceHandle()));
                    }
                    try { matched.clear(); throw new AssertionError("Mutable discovery snapshot"); }
                    catch (UnsupportedOperationException expected) { }
                } finally { for (var dr : additional) dr.close(); }
                assert writer.getMatchedSubscriptions().equals(Set.of(reader.getInstanceHandle()));
                break;
            }
            case "registration-keys": {
                var first = new Message(1000, "first"); var second = new Message(2000, "second");
                assert writer.lookupInstance(first).isNil() && writer.lookupInstance(second).isNil();
                var a = writer.registerInstance(first); var b = writer.registerInstance(second);
                assert !a.isNil() && !b.isNil() && !a.equals(b);
                assert a.equals(writer.lookupInstance(new Message(1000, "changed non-key")));
                assert b.equals(writer.lookupInstance(second));
                assert writer.getKeyValue(a).get_id() == 1000 && writer.getKeyValue(b).get_id() == 2000;
                var holder = InstanceHandle.nilHandle(env).modifiableCopy();
                assert writer.lookupInstance(holder, second) == holder && holder.equals(b);
                writer.write(first, a); writer.write(second, b); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert reader.lookupInstance(first).equals(a) && reader.lookupInstance(second).equals(b);
                writer.unregisterInstance(a, first); writer.unregisterInstance(b);
                break;
            }
            default: throw new AssertionError("Unknown case: " + args[0]);
            }
        }
        System.out.println(args[0] + " passed");
    }
}
