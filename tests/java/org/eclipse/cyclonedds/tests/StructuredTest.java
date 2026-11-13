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

import BindingTest.*;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.*;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipant;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;

public final class StructuredTest {
    private StructuredTest() {}
    private static <T> void exchange(DomainParticipant participant, Publisher publisher, Subscriber subscriber,
            Class<T> type, String name, java.util.List<T> values, WaitSet ws) throws Exception {
        var reliable = PolicyFactory.getPolicyFactory(participant.getEnvironment()).Reliability().withReliable();
        try (var input = participant.createTopic(name + "ToC", type);
             var output = participant.createTopic(name + "FromC", type);
             var writer = publisher.createDataWriter(input, publisher.getDefaultDataWriterQos().withPolicy(reliable));
             var reader = subscriber.createDataReader(output, subscriber.getDefaultDataReaderQos().withPolicy(reliable));
             var data = reader.select().getCondition()) {
            var w = writer.getStatusCondition(); w.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
            var r = reader.getStatusCondition(); r.setEnabledStatuses(Set.of(SubscriptionMatchedStatus.class));
            ws.attachCondition(w); ws.attachCondition(r);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                int writers = writer.getPublicationMatchedStatus().getCurrentCount();
                int readers = reader.getSubscriptionMatchedStatus().getCurrentCount();
                if (writers > 0 && readers > 0) break;
                ws.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            ws.detachCondition(w); ws.detachCondition(r); ws.attachCondition(data);
            for (T value : values) {
                writer.write(value);
                try { ws.waitForConditions(5, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException error) { throw new AssertionError("No C echo for " + name + " " + value, error); }
                var received = reader.select().take(null);
                assert received.size() == 1 && value.equals(received.get(0).getData());
            }
            ws.detachCondition(data);
        }
    }
    public static void main(String[] args) throws Exception {
        var count = new Choice(); count.set_count(123);
        var text = new Choice(); text.set_text("union text", 3);
        assert text.get_discriminator() == 3 && text.get_text().equals("union text");
        try { text.get_count(); throw new AssertionError(); } catch (IllegalStateException expected) { }
        try { text.set_text("bad", 1); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        var point = new Choice(); point.set_point(new Position(3.5, 4.5), 12);
        var implicit = new ImplicitChoice();
        assert implicit.get_discriminator() == Color.RED && implicit.get_number() == 0;
        implicit.__default(); assert implicit.get_discriminator() != Color.RED;
        var codec = new CdrWriter(); ImplicitChoice.codec().write(codec, implicit);
        assert ImplicitChoice.codec().read(new CdrReader(codec.toByteArray())).equals(implicit);
        var old = Evolving.codec().read(new CdrReader(HexFormat.of().parseHex("000900000400000007000000")));
        assert old.get_id() == 7 && old.get_text().isEmpty();
        var future = Evolving.codec().read(new CdrReader(HexFormat.of().parseHex("000900001000000007000000040000006869210063000000")));
        assert future.get_id() == 7 && future.get_text().equals("hi!");
        var evolving = new Evolving(4, "appendable");
        var envelope = new Envelope(evolving, text);
        var top = new CdrWriter(); Evolving.codec().write(top, evolving); assert top.toByteArray()[1] == 9;
        var nested = new CdrWriter(); Envelope.codec().write(nested, envelope); assert nested.toByteArray()[1] == 7;
        assert Envelope.codec().read(new CdrReader(nested.toByteArray())).equals(envelope);
        var env = ServiceEnvironment.createInstance(StructuredTest.class.getClassLoader());
        var ws = WaitSet.newWaitSet(env);
        Process peer = new ProcessBuilder(args[0]).inheritIO().start();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(83);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber()) {
            exchange(participant, publisher, subscriber, Choice.class, "Choice", java.util.List.of(count, text, point), ws);
            exchange(participant, publisher, subscriber, Evolving.class, "Evolving", java.util.List.of(evolving), ws);
            exchange(participant, publisher, subscriber, Envelope.class, "Envelope", java.util.List.of(envelope), ws);
            assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
        } finally {
            if (peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); }
            ((AutoCloseable) ws).close();
        }
        System.out.println("IDL unions and appendable XCDR2: branch checks, schema evolution, nesting and C interoperability passed");
    }
}
