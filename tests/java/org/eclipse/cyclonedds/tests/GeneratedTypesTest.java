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

import BindingTest.Color;
import BindingTest.CompositeKey;
import BindingTest.EscapedNames;
import BindingTest.Keyless;
import BindingTest.Position;
import BindingTest.Telemetry;
import java.util.ArrayList;
import java.util.Arrays;
import org.eclipse.cyclonedds.type.seq.ByteSequence;
import org.eclipse.cyclonedds.type.seq.IntegerSequence;
import org.eclipse.cyclonedds.type.seq.DoubleSequence;
import java.util.List;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.sub.Sample;

public final class GeneratedTypesTest {
    private GeneratedTypesTest() {}
    private static Telemetry telemetry(String label, byte[] payload) {
        return new Telemetry(12, true, 'A', (byte) 255, (short) -3, Long.MIN_VALUE,
                1.5f, -2.25, Color.GREEN, new Position(4.5, 6.25), label,
                new ByteSequence(payload), new IntegerSequence(new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}), new DoubleSequence(new double[]{1.25, -5.5}));
    }
    private static void same(Telemetry actual, Telemetry expected) {
        assert actual.id() == expected.id();
        assert actual.active() && actual.code() == 'A' && actual.flags() == (byte) 255;
        assert actual.delta() == -3 && actual.counter() == Long.MIN_VALUE;
        assert actual.temperature() == 1.5f && actual.measurement() == -2.25;
        assert actual.color() == Color.GREEN && actual.position().equals(expected.position());
        assert actual.label().equals(expected.label());
        assert actual.payload().equals(expected.payload());
        assert actual.values().equals(expected.values());
        assert actual.readings().equals(expected.readings());
    }
    public static void main(String[] args) throws Exception {
        var expected = telemetry("Greetings 🌍", new byte[]{1,2,3});
        var cdr = new CdrWriter();
        Telemetry.codec().write(cdr, expected);
        same(Telemetry.codec().read(new CdrReader(cdr.toByteArray())), expected);
        var escaped = new EscapedNames(1,2,3,4);
        cdr = new CdrWriter();
        EscapedNames.codec().write(cdr, escaped);
        assert EscapedNames.codec().read(new CdrReader(cdr.toByteArray())).equals(escaped);
        Checks.rejects(IndexOutOfBoundsException.class, () -> Telemetry.codec().write(new CdrWriter(), telemetry("x".repeat(25), new byte[0])));
        Checks.rejects(IndexOutOfBoundsException.class, () -> Telemetry.codec().write(new CdrWriter(), telemetry("x", new byte[17])));
        Checks.rejects(IllegalArgumentException.class, () -> Color.fromValue(99));
        // Accessors must not allow callers to mutate the generated metadata.
        byte[] info = Telemetry.typeSupport().typeInformation();
        info[0] ^= 127;
        assert !Arrays.equals(info, Telemetry.typeSupport().typeInformation());
        try (var participant = new DomainParticipant(74);
             var topic = participant.createTopic("GeneratedTelemetry", Telemetry.typeSupport());
             var writer = topic.createWriter(); var reader = topic.createReader();
             var compositeTopic = participant.createTopic("GeneratedComposite", CompositeKey.typeSupport());
             var compositeWriter = compositeTopic.createWriter(); var compositeReader = compositeTopic.createReader();
             var keylessTopic = participant.createTopic("GeneratedKeyless", Keyless.typeSupport());
             var keylessWriter = keylessTopic.createWriter(); var keylessReader = keylessTopic.createReader()) {
            Checks.await(() -> writer.matchedSubscriptions() == 1 && compositeWriter.matchedSubscriptions() == 1
                    && keylessWriter.matchedSubscriptions() == 1);
            writer.write(expected);
            List<Sample<Telemetry>> received = new ArrayList<>();
            Checks.await(() -> { received.addAll(reader.take(16)); return !received.isEmpty(); });
            same(received.get(0).data(), expected);
            compositeWriter.write(new CompositeKey("region-with-key-larger-than-sixteen-bytes", 1, "first"));
            compositeWriter.write(new CompositeKey("region-with-key-larger-than-sixteen-bytes", 1, "updated"));
            compositeWriter.write(new CompositeKey("another", 1, "other"));
            List<Sample<CompositeKey>> keyed = new ArrayList<>();
            Checks.await(() -> { keyed.addAll(compositeReader.take(16)); return keyed.size() >= 3; });
            var sameKey = keyed.stream().filter(s -> s.data().region().startsWith("region")).toList();
            assert sameKey.size() == 2 && sameKey.get(0).instanceHandle() == sameKey.get(1).instanceHandle();
            assert sameKey.get(0).data().text().equals("first") && sameKey.get(1).data().text().equals("updated");
            assert keyed.stream().map(Sample::instanceHandle).distinct().count() == 2;
            keylessWriter.write(new Keyless(1)); keylessWriter.write(new Keyless(2));
            List<Sample<Keyless>> keyless = new ArrayList<>();
            Checks.await(() -> { keyless.addAll(keylessReader.take(16)); return keyless.size() >= 2; });
            assert keyless.get(0).instanceHandle() == keyless.get(1).instanceHandle();
        }
        System.out.println("Generated nested types, enums, bounds, sequences, composite keys and keyless DDS passed");
    }
}
