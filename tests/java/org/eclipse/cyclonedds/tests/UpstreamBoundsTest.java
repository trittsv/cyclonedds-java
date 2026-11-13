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

import BindingTest.UpstreamBounded;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.type.seq.*;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.domain.DomainParticipantFactory;

/** Below/at/above-bound cases from ddscxx Bounded.cpp, including native delivery. */
public final class UpstreamBoundsTest {
    private static UpstreamBounded value(String kind, int size) {
        var numbers = new IntegerSequence(); var flags = new BooleanSequence();
        var text = new StringBuilder();
        for (int i = 0; i < size; i++) switch (kind) {
            case "strings": text.append((char)('a' + i % 26)); break;
            case "sequence": numbers.add(i + 123456); break;
            case "boolean": flags.add(i % 2 != 0); break;
            default: throw new AssertionError(kind);
        }
        return new UpstreamBounded(text.toString(), numbers, flags);
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(UpstreamBoundsTest.class.getClassLoader());
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(97);
             var topic = participant.createTopic("UpstreamBounds", UpstreamBounded.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic); var reader = subscriber.createDataReader(topic)) {
            for (int size : new int[]{0, 1, 254, 255}) {
                var expected = value(args[0], size);
                writer.write(expected); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                var samples = reader.select().take(null);
                assert samples.size() == 1 && expected.equals(samples.get(0).getData());
            }
            Checks.rejects(IndexOutOfBoundsException.class, () -> value(args[0], 256));
            // Strings are immutable and checked by setters. Sequences can grow
            // after assignment, so their bound must also be checked at write time.
            var oversized = value(args[0], 255);
            if (args[0].equals("strings")) {
                Checks.rejects(IndexOutOfBoundsException.class, () -> new CdrWriter().writeString("a".repeat(256), 255));
            } else {
                if (args[0].equals("sequence")) oversized.get_numbers().add(0);
                else oversized.get_flags().add(false);
                Checks.rejects(IndexOutOfBoundsException.class, () -> UpstreamBounded.codec().write(new CdrWriter(), oversized));
                try { writer.write(oversized); throw new AssertionError("Oversized DDS write succeeded"); }
                catch (IndexOutOfBoundsException expected) { }
            }
            assert reader.select().take(null).isEmpty();
            Checks.rejects(IllegalArgumentException.class, () -> UpstreamBounded.codec().read(new CdrReader(new byte[16])));
        }
        System.out.println(args[0] + ": generated bounds and native delivery passed");
    }
}
