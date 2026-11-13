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

import java.util.ArrayList;
import java.util.List;
import org.eclipse.cyclonedds.core.DdsException;
import org.eclipse.cyclonedds.core.Qos;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import HelloWorld.Message;
import org.eclipse.cyclonedds.sub.Sample;
import org.eclipse.cyclonedds.topic.TypeSupport;

public final class PubSubTest {
    private PubSubTest() {}
    public static void main(String[] args) throws Exception {
        try (var publisher = new DomainParticipant(71); var subscriber = new DomainParticipant(71);
             var writeTopic = publisher.createTopic("JavaBindingTest", Message.typeSupport());
             var readTopic = subscriber.createTopic("JavaBindingTest", Message.typeSupport());
             var writer = writeTopic.createWriter(); var reader = readTopic.createReader()) {
            Checks.await(() -> writer.matchedSubscriptions() == 1 && reader.matchedPublications() == 1);
            assert reader.take(1).isEmpty();
            List<Message> expected = List.of(new Message(1, "Greetings 🌍"), new Message(2, "second"), new Message(1, "updated"));
            for (var value : expected) writer.write(value);
            List<Sample<Message>> received = new ArrayList<>();
            Checks.await(() -> { received.addAll(reader.take(32)); return received.size() >= expected.size(); });
            // DDS orders changes within an instance; take may group different keys.
            assert received.size() == expected.size();
            assert received.stream().map(Sample::data).collect(java.util.stream.Collectors.toSet())
                    .equals(new java.util.HashSet<>(expected)) : received;
            assert received.stream().allMatch(s -> s.validData() && s.sourceTimestamp() > 0);
            var firstKey = received.stream().filter(s -> s.data().id() == 1).toList();
            var secondKey = received.stream().filter(s -> s.data().id() == 2).findFirst().orElseThrow();
            assert firstKey.stream().map(Sample::data).toList().equals(List.of(expected.get(0), expected.get(2)));
            assert firstKey.get(0).instanceHandle() == firstKey.get(1).instanceHandle();
            assert firstKey.get(0).instanceHandle() != secondKey.instanceHandle();
            Checks.rejects(IllegalArgumentException.class, () -> reader.take(0));
            var generated = Message.typeSupport();
            Checks.rejects(DdsException.class, () -> publisher.createTopic("InvalidMetadata",
                    new TypeSupport<>(generated.typeName(), true, new byte[]{1,2,3,4},
                            generated.typeMapping(), generated.codec())));
            Checks.rejects(DdsException.class, () -> publisher.createTopic("WrongKeyMetadata",
                    new TypeSupport<>(generated.typeName(), false, generated.typeInformation(),
                            generated.typeMapping(), generated.codec())));
            // A short codec must be rejected by the binding's native type support.
            var invalid = new TypeSupport<Message>(generated.typeName(), generated.hasKey(),
                    generated.typeInformation(), generated.typeMapping(), new TypeSupport.Codec<>() {
                public void write(org.eclipse.cyclonedds.core.cdr.CdrWriter cdr, Message value) { cdr.writeInt(value.id()); }
                public Message read(org.eclipse.cyclonedds.core.cdr.CdrReader cdr) { throw new UnsupportedOperationException(); }
            });
            try (var topic = publisher.createTopic("InvalidSample", invalid); var badWriter = topic.createWriter()) {
                Checks.rejects(DdsException.class, () -> badWriter.write(new Message(1, "ignored")));
            }
        }
        // Parent deletion invalidates Java handles; close remains idempotent.
        var parent = new DomainParticipant(73);
        var topic = parent.createTopic("LifetimeTest", Message.typeSupport());
        var writer = topic.createWriter(new Qos(false, 1));
        parent.close();
        parent.close();
        Checks.rejects(IllegalStateException.class, () -> writer.write(new Message(1, "closed")));
        Checks.rejects(IllegalStateException.class, topic::createReader);
        writer.close(); topic.close();
        // Deleting a topic releases its Java reference, while existing DDS endpoints
        // retain their native topic. Writer deletion produces invalid-data metadata.
        var owner = new DomainParticipant(73);
        try {
            var retainedTopic = owner.createTopic("TopicReferenceTest", Message.typeSupport());
            var retainedWriter = retainedTopic.createWriter();
            var retainedReader = retainedTopic.createReader();
            Checks.await(() -> retainedWriter.matchedSubscriptions() == 1);
            retainedTopic.close();
            Checks.rejects(IllegalStateException.class, retainedTopic::createWriter);
            retainedWriter.write(new Message(99, "retained"));
            List<Sample<Message>> live = new ArrayList<>();
            Checks.await(() -> { live.addAll(retainedReader.take(16)); return !live.isEmpty(); });
            assert live.get(0).data().equals(new Message(99, "retained"));
            retainedWriter.close();
            retainedWriter.close();
            Checks.rejects(IllegalStateException.class, () -> retainedWriter.write(new Message(99, "closed")));
            List<Sample<Message>> notifications = new ArrayList<>();
            Checks.await(() -> {
                notifications.addAll(retainedReader.take(16));
                return notifications.stream().anyMatch(s -> !s.validData());
            });
            assert notifications.stream().filter(s -> !s.validData()).allMatch(s -> s.data() == null);
            retainedReader.close();
        } finally { owner.close(); }
        System.out.println("DDS pub/sub, keyed instances, validation and lifecycle passed");
    }
}
