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

import java.nio.*;
import java.util.*;
import org.eclipse.cyclonedds.core.cdr.*;

/** Independent vectors derived from the upstream CDRStreamer boundary scenarios. */
public final class UpstreamCdrTest {
    private static byte[] vector(int encapsulation, int... words) {
        var bytes = ByteBuffer.allocate(4 + words.length * 4)
            .order((encapsulation & 1) == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{0, (byte) encapsulation, 0, 0});
        for (int word : words) bytes.putInt(word);
        return bytes.array();
    }
    public static void main(String[] args) {
        switch (args[0]) {
        case "primitive-boundaries": {
            var random = new Random(0xC1C10E);
            for (int i = 0; i < 1000; i++) {
                int n = i < 3 ? new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}[i] : random.nextInt();
                long l = i < 3 ? new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE}[i] : random.nextLong();
                var w = new CdrWriter();
                w.writeByte((byte)n); w.writeShort((short)n); w.writeInt(n); w.writeLong(l);
                var expected = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
                expected.put(new byte[]{0,7,0,0}).put((byte)n).put((byte)0).putShort((short)n).putInt(n).putLong(l);
                assert Arrays.equals(w.toByteArray(), expected.array());
                var r = new CdrReader(expected.array());
                assert r.readByte() == (byte)n && r.readShort() == (short)n && r.readInt() == n && r.readLong() == l;
                assert !r.hasRemaining();
            }
            break;
        }
        case "float-boundaries": {
            for (int bits : new int[]{0, 0x80000000, 1, 0x007fffff, 0x00800000, 0x7f7fffff, 0x7f800000, 0xff800000, 0x7fc00001}) {
                float f = Float.intBitsToFloat(bits);
                var w = new CdrWriter(); w.writeFloat(f);
                assert Arrays.equals(w.toByteArray(), vector(7, bits));
                assert Float.floatToRawIntBits(new CdrReader(w.toByteArray()).readFloat()) == bits;
            }
            for (long bits : new long[]{0, Long.MIN_VALUE, 1, 0x000fffffffffffffL, 0x0010000000000000L,
                    0x7fefffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff8000000000001L}) {
                var w = new CdrWriter(); w.writeDouble(Double.longBitsToDouble(bits));
                assert Arrays.equals(w.toByteArray(), vector(7, (int)bits, (int)(bits >>> 32)));
                assert Double.doubleToRawLongBits(new CdrReader(w.toByteArray()).readDouble()) == bits;
            }
            break;
        }
        case "alignment-endianness":
            for (int id : new int[]{0, 1, 6, 7}) for (int prefix = 0; prefix < 16; prefix++) {
                var b = ByteBuffer.allocate(128).order((id & 1) == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                b.put(new byte[]{0, (byte)id, 0, 0});
                for (int i = 0; i < prefix; i++) b.put((byte)i);
                while ((b.position() - 4) % 2 != 0) b.put((byte)0);
                b.putShort((short)0xaabb);
                while ((b.position() - 4) % 4 != 0) b.put((byte)0);
                b.putInt(0x12345678);
                while ((b.position() - 4) % (id < 2 ? 8 : 4) != 0) b.put((byte)0);
                b.putLong(0x1122334455667788L);
                var r = new CdrReader(Arrays.copyOf(b.array(), b.position()));
                for (int i = 0; i < prefix; i++) assert r.readByte() == i;
                assert r.readShort() == (short)0xaabb && r.readInt() == 0x12345678 && r.readLong() == 0x1122334455667788L;
                assert !r.hasRemaining();
            }
            break;
        case "truncated-payload": {
            var w = new CdrWriter(); w.writeInt(123456); w.writeChar('g'); w.writeString("abcdef"); w.writeDouble(654.321);
            byte[] complete = w.toByteArray();
            for (int size = 0; size < complete.length; size++) {
                byte[] truncated = Arrays.copyOf(complete, size);
                Checks.rejects(IllegalArgumentException.class, () -> {
                    var r = new CdrReader(truncated); r.readInt(); r.readChar(); r.readString(); r.readDouble();
                });
            }
            break;
        }
        case "nested-read-limits":
            for (int id : new int[]{10, 11}) {
                for (int header : new int[]{0x40000003, 0x40000002, 0x70000003})
                    Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(vector(id, 8, header,
                        header == 0x70000003 ? -1 : 256)).beginMutable());
            }
            for (int id : new int[]{8, 9})
                Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(vector(id, 256)).beginType(1));
            break;
        case "mutable-member-order":
            for (int id : new int[]{10, 11}) {
                // LC=2 gives a four-byte member; IDs deliberately arrive out of order.
                var r = new CdrReader(vector(id, 16, 0x20000009, 99, 0x20000003, 33));
                var frame = r.beginMutable();
                assert r.beginMember(frame, 3); assert r.readInt() == 33; r.endMember();
                assert r.beginMember(frame, 9); assert r.readInt() == 99; r.endMember();
                assert !r.beginMember(frame, 42); r.endMutable(frame);
                assert !r.hasRemaining();
            }
            break;
        case "mutable-must-understand":
            for (int id : new int[]{10, 11}) {
                var r = new CdrReader(vector(id, 8, 0xa0000009, 99));
                var frame = r.beginMutable();
                Checks.rejects(IllegalArgumentException.class, () -> r.endMutable(frame));
                var optional = new CdrReader(vector(id, 8, 0x20000009, 99));
                optional.endMutable(optional.beginMutable());
                Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(vector(id, 16, 0x20000009, 99, 0x20000009, 100)).beginMutable());
            }
            break;
        case "sequence-lengths":
            for (int length : new int[]{-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 2})
                Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(vector(7, length, 123)).readSequenceLength(0, 4));
            assert new CdrReader(vector(7, 0)).readSequenceLength(1, 4) == 0;
            Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(vector(7, 2, 123, 456)).readSequenceLength(1, 4));
            Checks.rejects(IndexOutOfBoundsException.class, () -> new CdrWriter().writeSequenceLength(2, 1));
            Checks.rejects(IndexOutOfBoundsException.class, () -> new CdrWriter().writeSequenceLength(-1, 0));
            break;
        case "string-boundaries":
            for (String value : new String[]{"", "Hello, World!", "llsllë", "🌍", "a".repeat(65536)}) {
                var w = new CdrWriter(); w.writeString(value);
                assert new CdrReader(w.toByteArray()).readString().equals(value);
                int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                w = new CdrWriter(); w.writeString(value, bytes);
                assert new CdrReader(w.toByteArray()).readString(bytes).equals(value);
                if (bytes > 1) {
                    final int bound = bytes - 1;
                    Checks.rejects(IndexOutOfBoundsException.class, () -> new CdrWriter().writeString(value, bound));
                }
            }
            break;
        default: throw new AssertionError("Unknown case: " + args[0]);
        }
        System.out.println(args[0] + " passed");
    }
}
