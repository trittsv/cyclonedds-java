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
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.Sample;
import HelloWorld.Message;
import BindingTest.CompositeKey;

public final class InteropTest {
    private InteropTest() {}
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(InteropTest.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(env);
        try (var participant = factory.createParticipant(72);
             var request = participant.createTopic("JavaToC", Message.class);
             var response = participant.createTopic("CToJava", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(request); var reader = subscriber.createDataReader(response);
             var keyRequest = participant.createTopic("CompositeToC", CompositeKey.class);
             var keyResponse = participant.createTopic("CompositeFromC", CompositeKey.class);
             var keyWriter = publisher.createDataWriter(keyRequest); var keyReader = subscriber.createDataReader(keyResponse)) {
            Process peer = new ProcessBuilder(args[0]).inheritIO().start();
            try {
                Checks.await(() -> writer.getMatchedSubscriptions().size() == 1 && reader.getMatchedPublications().size() == 1
                        && keyWriter.getMatchedSubscriptions().size() == 1 && keyReader.getMatchedPublications().size() == 1);
                writer.write(new Message(42, "Java → C 🌍"));
                List<Message> messages = new ArrayList<>();
                Checks.await(() -> {
                    for (var s : reader.take(new ArrayList<>())) if (s.getData() != null) messages.add(s.getData());
                    return !messages.isEmpty();
                });
                assert messages.get(0).equals(new Message(42, "C → Java ✓")) : messages;
                // Force RTPS fragmentation in both directions through custom from_ser.
                String large = "z".repeat(100_000);
                writer.write(new Message(44, large));
                messages.clear();
                Checks.await(() -> {
                    for (var s : reader.take(new ArrayList<>())) if (s.getData() != null) messages.add(s.getData());
                    return !messages.isEmpty();
                });
                assert messages.get(0).equals(new Message(44, large));
                String region = "region-with-key-larger-than-sixteen-bytes";
                keyWriter.write(new CompositeKey(region, 1, "first"));
                keyWriter.write(new CompositeKey(region, 1, "updated"));
                keyWriter.write(new CompositeKey("another", 1, "other"));
                List<Sample<CompositeKey>> keys = new ArrayList<>();
                Checks.await(() -> {
                    keys.addAll(keyReader.take(new ArrayList<>()));
                    return keys.stream().anyMatch(s -> s.getData() != null);
                });
                var keyData = keys.stream().filter(s -> s.getData() != null).findFirst().orElseThrow();
                assert keyData.getData().equals(new CompositeKey(region, 1, "C-keyhash"));
                var reconstructed = keyReader.getKeyValue(new CompositeKey("", 0, "keep payload"), keyData.getInstanceHandle());
                assert reconstructed.get_region().equals(region) && reconstructed.get_id() == 1;
                assert reconstructed.get_text().equals("keep payload");
                writer.write(new Message(45, "key-received"));
                Checks.await(() -> {
                    keys.addAll(keyReader.take(new ArrayList<>()));
                    return keys.stream().anyMatch(s -> s.getData() == null);
                });
                assert keys.stream().filter(s -> s.getData() == null).allMatch(s -> s.getData() == null
                        && s.getInstanceHandle().equals(keyData.getInstanceHandle()));
                // Acknowledge receipt before letting the peer tear down its writer.
                writer.write(new Message(43, "ack"));
                if (!peer.waitFor(10, TimeUnit.SECONDS) || peer.exitValue() != 0)
                    throw new AssertionError("C peer failed");
            } finally {
                if (peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); }
            }
        }
        System.out.println("Java ↔ separate native C process interoperability passed");
    }
}
