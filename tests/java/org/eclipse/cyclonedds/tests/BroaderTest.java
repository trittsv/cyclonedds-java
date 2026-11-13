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

import Broader.*;
import Broader.Arrays;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.topic.TypeSupport.Codec;
import org.eclipse.cyclonedds.type.seq.IntegerSequence;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.*;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;

public final class BroaderTest {
    private BroaderTest() {}
    private static int version = 2;
    private static <T> T roundtrip(Codec<T> codec, T value) {
        T copy = null;
        for (var order : List.of(java.nio.ByteOrder.LITTLE_ENDIAN, java.nio.ByteOrder.BIG_ENDIAN)) {
            var w = new CdrWriter(version, order); codec.write(w, value); copy = codec.read(new CdrReader(w.toByteArray()));
            assert copy.equals(value);
        }
        return copy;
    }
    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException | IndexOutOfBoundsException expected) { return; }
        throw new AssertionError("Expected malformed data or rejected IDL value");
    }
    private static int representations(Class<?> type) throws ReflectiveOperationException {
        return ((org.eclipse.cyclonedds.topic.TypeSupport<?>)type.getMethod("$typeSupport$" + type.getSimpleName()).invoke(null)).allowedDataRepresentations();
    }
    private static <T> void exchange(DomainParticipant p, Publisher pub, Subscriber sub, Class<T> type, List<T> values) throws Exception {
        if (version == 1 && (representations(type) & 1) == 0) {
            try (var topic = p.createTopic("RejectedX1" + type.getSimpleName(), type)) {
                Checks.rejects(IllegalArgumentException.class, () -> pub.createDataWriter(topic));
            }
            return;
        }
        var reliability = PolicyFactory.getPolicyFactory(p.getEnvironment()).Reliability().withReliable();
        var ws = WaitSet.newWaitSet(p.getEnvironment());
        try (var input = p.createTopic("Broad" + type.getSimpleName() + "ToC", type);
             var output = p.createTopic("Broad" + type.getSimpleName() + "FromC", type);
             var writer = pub.createDataWriter(input, pub.getDefaultDataWriterQos().withPolicy(reliability));
             var reader = sub.createDataReader(output, sub.getDefaultDataReaderQos().withPolicy(reliability));
             var data = reader.select().getCondition()) {
            try { Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty() && !reader.getMatchedPublications().isEmpty()); }
            catch (AssertionError error) {
                System.err.println(type.getSimpleName() + " writer matches=" + writer.getMatchedSubscriptions().size() + " reader matches=" + reader.getMatchedPublications().size()
                    + " offered incompatible=" + writer.getOfferedIncompatibleQosStatus().getLastPolicyClass() + " requested=" + reader.getRequestedIncompatibleQosStatus().getLastPolicyClass());
                throw error;
            }
            ws.attachCondition(data);
            for (T value : values) {
                writer.write(value);
                try { ws.waitForConditions(5, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException error) { throw new AssertionError("No C echo for " + type.getSimpleName(), error); }
                var received = reader.select().take(null);
                assert received.size() == 1 && value.equals(received.get(0).getData()) : type.getSimpleName() + ": " + received;
            }
        } finally { ((AutoCloseable) ws).close(); }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        version = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        var namedParent = new Naming.Derived(new Naming.Base(3), 4);
        assert roundtrip(Naming.Derived.$codec$Derived(), namedParent).equals(namedParent);
        var namespaceNames = new Naming._java("org", "java", 2, 3, 4, 5, 6, 7);
        assert namespaceNames.get__org().equals("org") && namespaceNames.get_text().equals("java");
        assert roundtrip(Naming.RuntimePrefixes.codec(), new Naming.RuntimePrefixes("java", "org", 9)).get__java().equals("java");
        assert namespaceNames.get_CODEC() == 6 && namespaceNames.get_TYPE() == 7;
        assert roundtrip(Naming._java.codec(), namespaceNames).equals(namespaceNames);
        var externalNumber = new ExternalChoice(); externalNumber.set_number(Integer.valueOf(9));
        var externalText = new ExternalChoice(); externalText.set_text("external €");
        assert ExternalChoice.class.getMethod("get_number").getReturnType() == Integer.class;
        assert roundtrip(ExternalChoice.codec(), externalNumber).equals(externalNumber);
        var mutual = new Left(new ArrayList<>(List.of(new Right(new Left()))));
        assert roundtrip(Left.codec(), mutual).equals(mutual);
        var terminal = new RecursiveChoice(); terminal.set_number(9);
        var recursiveUnion = new RecursiveChoice(); recursiveUnion.set_next(terminal);
        assert roundtrip(RecursiveChoice.codec(), recursiveUnion).equals(recursiveUnion);
        var futureUnion = new CdrWriter(); int unionEnd = futureUnion.beginType(1);
        futureUnion.writeInt(2); futureUnion.writeString("unknown future branch"); futureUnion.endType(unionEnd);
        var unknownBranch = OpenChoice.codec().read(new CdrReader(futureUnion.toByteArray()));
        assert unknownBranch.get_discriminator() == 2;
        try { unknownBranch.get_number(); throw new AssertionError("Unknown branch is inactive"); }
        catch (IllegalStateException expected) { }
        var absent = new Change(7, null, "external");
        var present = new Change(8, 42, "mutable string 🌍");
        assert roundtrip(Change.codec(), absent).equals(absent);
        assert roundtrip(Change.codec(), present).equals(present);
        var optionalAbsent = new OptionalFields(1, null, null, 3, 4);
        var optionalPresent = new OptionalFields(2, 17, "optional", 4, 5);
        assert roundtrip(OptionalFields.codec(), optionalAbsent).equals(optionalAbsent);
        assert roundtrip(OptionalFields.codec(), optionalPresent).equals(optionalPresent);
        var derived = new Derived(new Base(11), 22);
        assert derived instanceof Base && derived.get_first() == 11;
        assert roundtrip(Derived.codec(), derived).equals(derived);
        var derivedA = new DerivedA(new BaseA(33), "base flattened");
        var derivedM = new DerivedM(new BaseM(44), "mutable inheritance");
        assert roundtrip(DerivedA.$codec$DerivedA(), derivedA).equals(derivedA);
        assert roundtrip(DerivedM.$codec$DerivedM(), derivedM).equals(derivedM);
        var wide = new Wide('€', "Wide € 🌍");
        assert roundtrip(Wide.codec(), wide).equals(wide);
        rejects(() -> { var w = new CdrWriter(); w.writeWChar('\ud800'); });
        rejects(() -> { var w = new CdrWriter(); w.writeWString("\ud800", 0); });
        var lists = new org.omg.type.IntegerSeq[]{new IntegerSequence(), new IntegerSequence()};
        lists[0].add(5); lists[0].add(6); lists[1].add(7);
        var arrays = new Arrays(lists, (List<String>[]) new List<?>[]{new ArrayList<>(List.of("one", "🌍")), new ArrayList<>(List.of("two"))});
        assert roundtrip(Arrays.codec(), arrays).equals(arrays);
        var tree = new Node(1, new ArrayList<>(List.of(new Node(2, new ArrayList<>()), new Node(3, new ArrayList<>(List.of(new Node(4, new ArrayList<>())))))));
        assert roundtrip(Node.codec(), tree).equals(tree);
        var names = new Names(1, 2, 3, 4, 5);
        assert names.get_get_foo() == 1 && names.get_foo() == 2 && names.get_set_foo() == 3;
        assert roundtrip(Names.codec(), names).equals(names);
        assert new Annotated().get_number() == 5;
        assert new UnsignedRange().get_value() == -1;
        rejects(() -> new UnsignedRange(0));
        assert new UnsignedRange(Integer.MIN_VALUE).get_value() == Integer.MIN_VALUE;
        rejects(() -> new Annotated(1)); rejects(() -> new Annotated(10));
        assert Labeled.class.getAnnotation(Label.class).value().equals("first");
        assert Labeled.class.getAnnotation(Label.class).priority() == 2;
        var field = Labeled.class.getDeclaredField("number");
        assert field.getAnnotation(Label.class).value().equals("field");
        assert new Injected().injected() == 42;
        assert new Enums().get_value() == Small.SEVEN;
        var enums = new Enums(Small.TWO, new ArrayList<>(List.of(Small.SEVEN, Small.TWO)));
        assert roundtrip(Enums.codec(), enums).equals(enums);
        // Java heap cycles cannot be encoded as finite IDL values.
        var cycle = new Node(); cycle.get_children().add(cycle);
        rejects(() -> roundtrip(Node.codec(), cycle));
        var deep = new Node(); var tail = deep;
        for (int i = 0; i < 130; i++) { var child = new Node(); tail.get_children().add(child); tail = child; }
        rejects(() -> roundtrip(Node.codec(), deep));
        rejects(() -> new CdrWriter().writeWString("🌍", 1));
        var branchName = new BranchNames(); branchName.set__discriminator(17);
        assert branchName.get_discriminator() == 1 && branchName.get__discriminator() == 17;
        var mutableNumber = new MutableChoice(); mutableNumber.set_number(19);
        var mutableText = new MutableChoice(); mutableText.set_text("mutable union");
        var appendableNumber = new AppendableChoice(); appendableNumber.set_number(20);
        var appendableText = new AppendableChoice(); appendableText.set_text("appendable union");
        var wideNumber = new OctetChoice(); wideNumber.set_number(21);
        var wideText = new OctetChoice(); wideText.set_text("wide discriminator");
        var shortNumber = new ShortChoice(); shortNumber.set_number(22);
        var shortText = new ShortChoice(); shortText.set_text("short discriminator");
        assert roundtrip(MutableChoice.codec(), mutableNumber).equals(mutableNumber);
        assert roundtrip(MutableChoice.codec(), mutableText).equals(mutableText);
        assert roundtrip(AppendableChoice.codec(), appendableNumber).equals(appendableNumber);
        assert roundtrip(AppendableChoice.codec(), appendableText).equals(appendableText);
        // Evolve a mutable type: reorder members, retain defaults for missing members,
        // skip unknown members, and reject unknown must-understand members.
        var future = new CdrWriter(); int end = future.beginType(2);
        int member = future.beginMember(99, false); future.writeLong(123); future.endMember(member);
        member = future.beginMember(21, false); future.writeString("evolved"); future.endMember(member);
        member = future.beginMember(7, true); future.writeInt(55); future.endMember(member); future.endType(end);
        var evolved = Change.codec().read(new CdrReader(future.toByteArray()));
        assert evolved.get_id() == 55 && evolved.get_opt() == null && evolved.get_text().equals("evolved");
        var unknownMu = new CdrWriter(); end = unknownMu.beginType(2); member = unknownMu.beginMember(100, true);
        unknownMu.writeInt(3); unknownMu.endMember(member); unknownMu.endType(end);
        rejects(() -> Change.codec().read(new CdrReader(unknownMu.toByteArray())));
        var big = java.nio.ByteBuffer.allocate(28).order(java.nio.ByteOrder.BIG_ENDIAN);
        big.put(new byte[]{0, 10, 0, 0}).putInt(20).putInt(0xa0000007).putInt(23).putInt(0x50000015).putInt(4).put(new byte[]{'b','i','g',0});
        var bigValue = Change.codec().read(new CdrReader(big.array()));
        assert bigValue.get_id() == 23 && bigValue.get_opt() == null && bigValue.get_text().equals("big");
        var duplicate = new CdrWriter(); end = duplicate.beginType(2);
        member = duplicate.beginMember(7, false); duplicate.writeInt(1); duplicate.endMember(member);
        member = duplicate.beginMember(7, false); duplicate.writeInt(2); duplicate.endMember(member); duplicate.endType(end);
        rejects(() -> Change.codec().read(new CdrReader(duplicate.toByteArray())));
        var missingBranch = new CdrWriter(); end = missingBranch.beginType(2);
        member = missingBranch.beginMember(0, true); missingBranch.writeInt(1); missingBranch.endMember(member); missingBranch.endType(end);
        rejects(() -> MutableChoice.codec().read(new CdrReader(missingBranch.toByteArray())));
        Process peer = null;
        var env = ServiceEnvironment.createInstance(BroaderTest.class.getClassLoader());
        try (var p = DomainParticipantFactory.getInstance(env).createParticipant(87); var pub = p.createPublisher(); var sub = p.createSubscriber()) {
            var representation = PolicyFactory.getPolicyFactory(env).Representation().withValue(List.of((short)(version == 1 ? 0 : 2)));
            pub.setDefaultDataWriterQos(pub.getDefaultDataWriterQos().withPolicy(representation));
            sub.setDefaultDataReaderQos(sub.getDefaultDataReaderQos().withPolicy(representation));
            Class<?>[] peerTypes = {Change.class, OptionalFields.class, Derived.class, DerivedA.class, DerivedM.class,
                Wide.class, Arrays.class, Node.class, BranchNames.class, MutableChoice.class, AppendableChoice.class,
                OctetChoice.class, ShortChoice.class, Enums.class, ExternalChoice.class, Left.class, RecursiveChoice.class};
            int[] counts = {2,2,1,1,1,1,1,1,1,2,2,2,2,1,2,1,1};
            int count = 0; StringBuilder peerNames = new StringBuilder(",");
            for (int i = 0; i < peerTypes.length; i++) if (version == 2 || (representations(peerTypes[i]) & 1) != 0) {
                count += counts[i]; peerNames.append(peerTypes[i].getSimpleName()).append(',');
            }
            peer = new ProcessBuilder(args[0], "" + count, "" + version, peerNames.toString()).inheritIO().start();
            try (var keyed = p.createTopic("MutableDerivedKeys", DerivedM.class); var writer = pub.createDataWriter(keyed)) {
                var instance = writer.registerInstance(derivedM);
                var holder = new DerivedM(new BaseM(0), "retained non-key");
                assert writer.getKeyValue(holder, instance) == holder;
                assert holder.get_first() == 44 && holder.get_second().equals("retained non-key");
            }
            exchange(p, pub, sub, Change.class, List.of(absent, present));
            exchange(p, pub, sub, OptionalFields.class, List.of(optionalAbsent, optionalPresent));
            exchange(p, pub, sub, Derived.class, List.of(derived));
            exchange(p, pub, sub, DerivedA.class, List.of(derivedA));
            exchange(p, pub, sub, DerivedM.class, List.of(derivedM));
            exchange(p, pub, sub, Wide.class, List.of(wide));
            exchange(p, pub, sub, Arrays.class, List.of(arrays));
            exchange(p, pub, sub, Node.class, List.of(tree));
            exchange(p, pub, sub, BranchNames.class, List.of(branchName));
            exchange(p, pub, sub, MutableChoice.class, List.of(mutableNumber, mutableText));
            exchange(p, pub, sub, AppendableChoice.class, List.of(appendableNumber, appendableText));
            exchange(p, pub, sub, OctetChoice.class, List.of(wideNumber, wideText));
            exchange(p, pub, sub, ShortChoice.class, List.of(shortNumber, shortText));
            exchange(p, pub, sub, Enums.class, List.of(enums));
            exchange(p, pub, sub, ExternalChoice.class, List.of(externalNumber, externalText));
            exchange(p, pub, sub, Left.class, List.of(mutual));
            exchange(p, pub, sub, RecursiveChoice.class, List.of(recursiveUnion));
            assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
        } finally { if (peer != null && peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); } }
        System.out.println("Broader IDL: optionals, external, inheritance, recursion, XCDR" + version + ", wide text, sequence arrays, annotations and union evolution passed");
    }
}
