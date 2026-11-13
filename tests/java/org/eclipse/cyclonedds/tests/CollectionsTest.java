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

import BindingTest.Collections;
import BindingTest.Color;
import BindingTest.Position;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.type.seq.IntegerSequence;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipantFactory;

public final class CollectionsTest {
    private CollectionsTest() {}
    public static void main(String[] args) throws Exception {
        var value = new Collections();
        assert value.get_grid().length == 2 && value.get_grid()[0].length == 3;
        assert value.get_points()[0] != null && value.get_points()[0] != value.get_points()[1];
        assert value.get_names()[0].isEmpty() && value.get_colors()[0] == Color.RED;
        value.set_id(7);
        value.set_grid(new int[][]{{1, 2, 3}, {4, 5, 6}});
        value.set_points(new Position[]{new Position(1.25, -2.5), new Position(3, 4)});
        value.set_colors(new Color[]{Color.RED, Color.BLUE});
        value.set_names(new String[]{"one", "two"});
        value.get_path().add(new Position(5, 6));
        value.get_labels().add("hello"); value.get_palette().add(Color.GREEN);
        value.get_nested().add(new IntegerSequence(new int[]{8, 9}));
        var bytes = new CdrWriter(); Collections.codec().write(bytes, value);
        assert Collections.codec().read(new CdrReader(bytes.toByteArray())).equals(value);
        try { value.set_grid(new int[][]{{1}, {2, 3}}); throw new AssertionError(); }
        catch (IndexOutOfBoundsException expected) { /* Fixed dimensions are checked. */ }
        var corrupt = bytes.toByteArray(); corrupt[32] = 4;
        try { Collections.codec().read(new CdrReader(corrupt)); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { /* The DHEADER bounds its contents. */ }
        var env = ServiceEnvironment.createInstance(CollectionsTest.class.getClassLoader());
        var reliable = PolicyFactory.getPolicyFactory(env).Reliability().withReliable();
        var ws = WaitSet.newWaitSet(env);
        Process peer = null;
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(82);
             var input = participant.createTopic("CollectionsToC", Collections.class);
             var output = participant.createTopic("CollectionsFromC", Collections.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(input, publisher.getDefaultDataWriterQos().withPolicy(reliable));
             var reader = subscriber.createDataReader(output, subscriber.getDefaultDataReaderQos().withPolicy(reliable));
             var data = reader.select().getCondition()) {
            peer = new ProcessBuilder(args[0]).inheritIO().start();
            var matched = writer.getStatusCondition(); matched.setEnabledStatuses(java.util.Set.of(PublicationMatchedStatus.class));
            var readMatched = reader.getStatusCondition(); readMatched.setEnabledStatuses(java.util.Set.of(SubscriptionMatchedStatus.class));
            ws.attachCondition(matched); ws.attachCondition(readMatched);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                int writes = writer.getPublicationMatchedStatus().getCurrentCount();
                int reads = reader.getSubscriptionMatchedStatus().getCurrentCount();
                if (writes != 0 && reads != 0) break;
                try { ws.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (java.util.concurrent.TimeoutException error) {
                    throw new AssertionError("Discovery counts " + writes + "/" + reads + " changes " + writer.getStatusChanges() + "/" + reader.getStatusChanges(), error);
                }
            }
            ws.detachCondition(matched); ws.detachCondition(readMatched);
            ws.attachCondition(data); writer.write(value);
            ws.waitForConditions(5, TimeUnit.SECONDS);
            var received = reader.select().take(null);
            assert received.size() == 1 && received.get(0).getData() != null;
            value.set_labels(List.of("from C"));
            assert received.get(0).getData().equals(value);
            assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
        } finally {
            if (peer != null && peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); }
            ((AutoCloseable) ws).close();
        }
        System.out.println("IDL collections: fixed arrays, constructed/nested sequences, bounds and bidirectional C interoperability passed");
    }
}
