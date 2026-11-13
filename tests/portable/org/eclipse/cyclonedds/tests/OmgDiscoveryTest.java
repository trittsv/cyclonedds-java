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
import java.util.function.BooleanSupplier;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.topic.*;
import org.omg.dds.sub.*;

/** Native-peer discovery verified solely through the normative Java API. */
public final class OmgDiscoveryTest {
    private static void await(BooleanSupplier predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Discovery timed out");
            Thread.sleep(5);
        }
    }
    private static void bytes(byte[] actual, int first, int last) { assert Arrays.equals(actual, new byte[]{(byte)first, 0, (byte)last}) : Arrays.toString(actual); }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgDiscoveryTest.class.getClassLoader());
        var pf = PolicyFactory.getPolicyFactory(env);
        Process peer = null;
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(99);
             var topic = participant.createTopic("DiscoveryPeer", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(pf.Reliability().withReliable(), pf.Deadline().withPeriod(20, TimeUnit.SECONDS)));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(pf.Reliability().withReliable(), pf.Deadline().withPeriod(20, TimeUnit.SECONDS)))) {
            var builtin = participant.getBuiltinSubscriber();
            assert builtin == participant.getBuiltinSubscriber() && builtin.getParent() == participant;
            try (DataReader<ParticipantBuiltinTopicData> participants = builtin.lookupDataReader("DCPSParticipant");
                 DataReader<TopicBuiltinTopicData> topics = builtin.lookupDataReader("DCPSTopic");
                 DataReader<PublicationBuiltinTopicData> publications = builtin.lookupDataReader("DCPSPublication");
                 DataReader<SubscriptionBuiltinTopicData> subscriptions = builtin.lookupDataReader("DCPSSubscription")) {
                assert participants != null && topics != null && publications != null && subscriptions != null;
                assert publications.getParent() == builtin && publications.getTopicDescription().getName().equals("DCPSPublication");
                peer = new ProcessBuilder(args[0]).inheritIO().start();
                await(() -> writer.getMatchedSubscriptions().size() >= 2 && reader.getMatchedPublications().size() >= 2);
                var remotePublication = reader.getMatchedPublications().stream().map(reader::getMatchedPublicationData)
                    .filter(data -> data.getUserData().getValue().length == 3).findFirst().orElseThrow();
                var remoteSubscription = writer.getMatchedSubscriptions().stream().map(writer::getMatchedSubscriptionData)
                    .filter(data -> data.getUserData().getValue().length == 3).findFirst().orElseThrow();
                assert remotePublication.getTopicName().equals("DiscoveryPeer") && remotePublication.getTypeName().equals("HelloWorld::Message");
                assert remotePublication.getParticipantKey().equals(remoteSubscription.getParticipantKey());
                bytes(remotePublication.getUserData().getValue(), 2, 252);
                bytes(remoteSubscription.getUserData().getValue(), 3, 251);
                bytes(remotePublication.getGroupData().getValue(), 4, 254);
                bytes(remotePublication.getTopicData().getValue(), 5, 253);
                assert remotePublication.getReliability().getKind() == Reliability.Kind.RELIABLE;
                assert remotePublication.getDeadline().getPeriod().getDuration(TimeUnit.SECONDS) == 20;
                assert remotePublication.getRepresentation().getValue().equals(List.of((short)2));
                var clone = remotePublication.clone(); assert clone.equals(remotePublication) && clone != remotePublication;
                var exposed = clone.getUserData().getValue(); exposed[0] = 77;
                bytes(clone.getUserData().getValue(), 2, 252);
                var keyWords = clone.getKey().getValue(); keyWords[0] ^= -1;
                assert clone.getKey().equals(remotePublication.getKey());
                await(() -> !participant.getDiscoveredParticipants().isEmpty());
                ParticipantBuiltinTopicData remoteParticipant = null;
                for (var handle : participant.getDiscoveredParticipants()) {
                    var data = participant.getDiscoveredParticipantData(handle);
                    if (data.getKey().equals(remotePublication.getParticipantKey())) remoteParticipant = data;
                }
                assert remoteParticipant != null; bytes(remoteParticipant.getUserData().getValue(), 6, 255);
                await(() -> !participant.getDiscoveredTopics().isEmpty());
                assert participant.getDiscoveredTopics().stream().map(participant::getDiscoveredTopicData)
                    .anyMatch(data -> data.getName().equals("DiscoveryPeer") && Arrays.equals(data.getTopicData().getValue(), new byte[]{5,0,(byte)253}));
                var unread = builtin.createDataState().with(SampleState.NOT_READ).withAnyViewState().withAnyInstanceState();
                var snapshots = publications.select().dataState(unread).read(null);
                var remoteSample = snapshots.stream().filter(sample -> sample.getData() != null && sample.getData().getKey().equals(remotePublication.getKey())).findFirst().orElseThrow();
                assert remoteSample.getSampleState() == SampleState.NOT_READ;
                assert remoteSample.clone().getData().equals(remoteSample.getData());
                assert publications.lookupInstance(remoteSample.getData()).equals(remoteSample.getInstanceHandle());
                assert publications.getKeyValue(remoteSample.getData().clone(), remoteSample.getInstanceHandle()).getKey().equals(remoteSample.getData().getKey());
                var unrelated = writer.registerInstance(new Message(123, "application key"));
                try { publications.getKeyValue(remoteSample.getData().clone(), unrelated); throw new AssertionError("Foreign instance accepted"); }
                catch (IllegalArgumentException expected) { }

                var holder = remoteSample.getData().clone();
                assert publications.getKeyValue(holder, remoteSample.getInstanceHandle()) == holder;
                assert holder.getKey().equals(remotePublication.getKey()) && holder.getTopicName().equals("DiscoveryPeer");
                try (var loan = publications.read()) { assert loan.hasNext() && loan.next().getData() != null; }
                assert topics.select().read(null).stream().anyMatch(sample -> sample.getData() != null && sample.getData().getName().equals("DiscoveryPeer"));
                assert participants.select().read(null).stream().anyMatch(sample -> sample.getData() != null && sample.getData().getKey().equals(remotePublication.getParticipantKey()));
                assert subscriptions.select().read(null).stream().anyMatch(sample -> sample.getData() != null && sample.getData().getKey().equals(remoteSubscription.getKey()));
                var ws = WaitSet.newWaitSet(env);
                try (var condition = publications.createReadCondition(unread)) {
                    ws.attachCondition(condition);
                    writer.write(new Message(1, "done")); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                    assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
                    ws.waitForConditions(5, TimeUnit.SECONDS);
                    var removed = publications.select().dataState(unread).take(null);
                    assert removed.stream().anyMatch(sample -> sample.getData() == null && sample.getInstanceHandle().equals(remoteSample.getInstanceHandle()));
                    assert publications.getKeyValue(remoteSample.getData().clone(), remoteSample.getInstanceHandle()).getKey().equals(clone.getKey());
                    bytes(clone.getUserData().getValue(), 2, 252); // Remains owned after native deletion and loan release.
                } finally { ((AutoCloseable)ws).close(); }
                try { reader.getMatchedPublicationData(remoteSample.getInstanceHandle()); throw new AssertionError("Deleted publication accepted"); }
                catch (PreconditionNotMetException expected) { }
                try { reader.getMatchedPublicationData(InstanceHandle.nilHandle(env)); throw new AssertionError("Nil handle accepted"); }
                catch (IllegalArgumentException expected) { }
            }
        } finally { if (peer != null && peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); } }
        System.out.println("Discovery: all four built-in readers, native metadata/QoS, keys, copies and shutdown events passed");
    }
}
