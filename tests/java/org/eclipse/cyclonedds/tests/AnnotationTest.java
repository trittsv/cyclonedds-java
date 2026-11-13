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

import java.math.BigInteger;
import java.util.*;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.topic.TypeSupport;
import org.eclipse.cyclonedds.type.seq.LongSequence;
import java_mapped.*;

/** Independent wire vectors plus actual DDS delivery of recoverable samples. */
public final class AnnotationTest {
    private AnnotationTest() {}
    private static byte[] textVector() {
        var w = new CdrWriter(); w.beginType(0);
        w.writeString("abcdef"); w.writeString("overflow");
        w.writeSequenceLength(4, 0); for (int i = 1; i <= 4; i++) w.writeInt(i);
        w.writeInt(123); w.writeInt(42); return w.toByteArray();
    }
    private static void checkText(Recovery.Text text) {
        assert text.get_trimmed().equals("abcd");
        assert text.get_defaulted().equals("abc");
        assert text.get_numbers().equals(List.of(1, 2));
        assert text.get_kind() == Recovery.Kind.SEVEN;
        assert text.get_after() == 42;
    }
    private static <T> T roundtrip(TypeSupport.Codec<T> codec, T value) {
        var w = new CdrWriter(); codec.write(w, value); return codec.read(new CdrReader(w.toByteArray()));
    }
    public static void main(String[] args) throws Exception {
        checkText(Recovery.Text.codec().read(new CdrReader(textVector())));
        var trimString = CdrCodecs.recover(CdrCodecs.string(4), 2, () -> "");
        var utf8 = new CdrWriter(); utf8.writeString("ab€z");
        assert trimString.read(new CdrReader(utf8.toByteArray())).equals("ab");
        byte[] invalidUtf8 = utf8.toByteArray(); invalidUtf8[invalidUtf8.length - 2] = (byte) 0xff;
        Checks.rejects(IllegalArgumentException.class, () -> trimString.read(new CdrReader(invalidUtf8)));
        assert roundtrip(MemberMapping.Mixed.codec(), new MemberMapping.Mixed(0xffffffffL, -1, new StringBuffer("mixed"))).get_promoted() == 0xffffffffL;
        var w = new CdrWriter(); w.beginType(0);
        w.writeWString("abcdef", 0); w.writeWString("overflow", 0); w.writeInt(99);
        var wide = Recovery.Wide.codec().read(new CdrReader(w.toByteArray()));
        assert wide.get_trimmed().equals("abcd") && wide.get_defaulted().equals("") && wide.get_after() == 99;
        w = new CdrWriter(); w.beginType(0); int end = w.beginDelimited();
        w.writeSequenceLength(2, 0); w.writeString("abcdef"); w.writeString("xy"); w.endDelimited(end); w.writeInt(7);
        var nested = Recovery.Nested.codec().read(new CdrReader(w.toByteArray()));
        assert nested.get_texts().equals(List.of("abc", "xy")) && nested.get_after() == 7;
        w = new CdrWriter(); w.beginType(0); w.writeInt(1); w.writeString("abcdef");
        assert Recovery.Choice.codec().read(new CdrReader(w.toByteArray())).get_text().equals("abc");
        byte[] truncated = Arrays.copyOf(textVector(), 13);
        Checks.rejects(IllegalArgumentException.class, () -> Recovery.Text.codec().read(new CdrReader(truncated)));
        Checks.rejects(IllegalArgumentException.class, () -> CdrCodecs.string(4).read(new CdrReader(textVector())));
        var promotedUnion = new Promoted.Choice(); promotedUnion.set_number(0xffffffffL);
        assert roundtrip(Promoted.Choice.codec(), promotedUnion).get_number() == 0xffffffffL;
        promotedUnion.set_text("default with a large discriminator", new BigInteger("18446744073709551615"));
        assert roundtrip(Promoted.Choice.codec(), promotedUnion).equals(promotedUnion);
        var memberUnion = new MemberUnion.Mixed(); memberUnion.set_text(new StringBuffer("custom branch"));
        assert roundtrip(MemberUnion.Mixed.codec(), memberUnion).equals(memberUnion);
        memberUnion.set_number(0xffffffffL);
        assert roundtrip(MemberUnion.Mixed.codec(), memberUnion).get_number() == 0xffffffffL;
        var numbers = new LongSequence(); numbers.add(0xffffffffL);
        var value = new SomeValue((short) 255, 65535, 0xffffffffL,
            new BigInteger("18446744073709551615"), new StringBuilder("hello"), new StringBuilder("wide €"),
            numbers, new ArrayList<>(List.of(BigInteger.ZERO, new BigInteger("18446744073709551615"))), ColorKind.DARK_BLUE);
        var copied = roundtrip(SomeValue.codec(), value);
        assert copied.equals(value) && copied.hashCode() == value.hashCode();
        assert copied.getMessageText().toString().equals("hello");
        assert copied.getBig().equals(value.get_big());
        Checks.rejects(IllegalArgumentException.class, () -> value.setSmall(65536));
        Checks.rejects(IllegalArgumentException.class, () -> value.setMedium(-1));
        Checks.rejects(IllegalArgumentException.class, () -> value.setBig(BigInteger.ONE.shiftLeft(64)));
        assert some_constants.Numbers.MAXIMUM == 0xffffffffL;
        assert some_constants.Numbers.PI == 3.14;
        assert roundtrip(Buffered.Text.codec(), new Buffered.Text(new StringBuffer("buffer"))).get_value().toString().equals("buffer");
        var local = roundtrip(Marked.Nested.Local.codec(), new Marked.Nested.Local(1, 88, 2));
        assert local.get_before() == 1 && local.get_cache() == 17 && local.get_after() == 2;
        assert java.lang.reflect.Modifier.isTransient(Marked.Nested.Local.class.getDeclaredField("cache").getModifiers());
        assert Marked.Nested.Local.class.getPackage().getAnnotation(Marked.PackageLabel.class).value().equals("marked");
        // Use the exact reader metadata with an independent unbounded writer codec.
        // This proves the generic native validation layer preserves recoverable data.
        var generated = Recovery.Text.typeSupport();
        var raw = new TypeSupport<byte[]>(generated.typeName(), false, generated.typeInformation(), generated.typeMapping(),
            CdrCodecs.of((out, bytes) -> {
                out.beginType(0); out.writeString("abcdef"); out.writeString("overflow");
                out.writeSequenceLength(4, 0); for (int i = 1; i <= 4; i++) out.writeInt(i);
                out.writeInt(123); out.writeInt(42);
            }, in -> { throw new UnsupportedOperationException(); }));
        try (var p = new DomainParticipant(89);
             var input = p.createTopic("RecoveryAnnotations", raw);
             var output = p.createTopic("RecoveryAnnotations", generated);
             var writer = input.createWriter(); var reader = output.createReader()) {
            Checks.await(() -> writer.matchedSubscriptions() == 1);
            writer.write(new byte[0]);
            List<org.eclipse.cyclonedds.sub.Sample<Recovery.Text>> received = new ArrayList<>();
            Checks.await(() -> { received.addAll(reader.take(8)); return !received.isEmpty(); });
            checkText(received.get(0).data());
        }
        System.setProperty("org.omg.dds.serviceClassName", "org.eclipse.cyclonedds.omg.CycloneServiceEnvironment");
        var env = org.omg.dds.core.ServiceEnvironment.createInstance(AnnotationTest.class.getClassLoader());
        var factory = org.omg.dds.domain.DomainParticipantFactory.getInstance(env);
        var reliability = org.omg.dds.core.policy.PolicyFactory.getPolicyFactory(env).Reliability().withReliable();
        try (var p = factory.createParticipant(90);
             var topic = p.createTopic("MappedFilter", SomeValue.class);
             var pub = p.createPublisher(); var sub = p.createSubscriber();
             var filtered = p.createContentFilteredTopic("MappedFilterSubset", topic,
                 "medium = 4294967295 AND big = 18446744073709551615 AND message_text LIKE 'hel%' AND color = dark_blue", List.of());
             var reader = sub.createDataReader(filtered, sub.getDefaultDataReaderQos().withPolicy(reliability));
             var writer = pub.createDataWriter(topic, pub.getDefaultDataWriterQos().withPolicy(reliability))) {
            Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty());
            writer.write(value); writer.waitForAcknowledgments(5, java.util.concurrent.TimeUnit.SECONDS);
            var accepted = reader.select().take(null);
            assert accepted.size() == 1 && accepted.get(0).getData().equals(value);
        }
        System.out.println("Annotation mapping, groups/packages, transient members and DDS try-construct recovery passed");
    }
}
