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

package org.eclipse.cyclonedds.core.cdr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** XCDR1/XCDR2 writer. Parameter-list values have their own alignment origin. */
public final class CdrWriter {
    private ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    private final int version;
    private int origin = 4;
    private final java.util.ArrayDeque<Integer> origins = new java.util.ArrayDeque<>();
    private int typeDepth;
    private final java.util.IdentityHashMap<Object, Boolean> activeValues = new java.util.IdentityHashMap<>();
    public void enter(Object value) {
        Objects.requireNonNull(value, "Required IDL value");
        if (activeValues.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic Java object graph is not a finite DDS value");
    }
    public void leave(Object value) { activeValues.remove(value); }

    public CdrWriter() { this(2); }
    public CdrWriter(int version) { this(version, ByteOrder.LITTLE_ENDIAN); }
    public CdrWriter(int version, ByteOrder order) {
        if (version != 1 && version != 2) throw new IllegalArgumentException("Expected XCDR version 1 or 2");
        this.version = version; buffer.order(Objects.requireNonNull(order));
        buffer.put(new byte[]{0, (byte)(version == 1 ? 0 : 6), 0, 0});
        if (order == ByteOrder.LITTLE_ENDIAN) buffer.put(1, (byte)(buffer.get(1) + 1));
    }
    public boolean isXcdr2() { return version == 2; }

    private void reserve(int count, int alignment) {
        int padding = (-(buffer.position() - origin)) & (alignment - 1);
        int required = Math.addExact(buffer.position(), Math.addExact(padding, count));
        if (required > buffer.capacity()) {
            int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max((long) required, 2L * buffer.capacity()));
            ByteBuffer next = ByteBuffer.allocate(capacity).order(buffer.order());
            buffer.flip();
            next.put(buffer);
            buffer = next;
        }
        for (int i = 0; i < padding; i++) buffer.put((byte) 0);
    }

    public void writeBoolean(boolean value) { writeByte((byte) (value ? 1 : 0)); }
    public void writeChar(char value) {
        if (value > 255) throw new IllegalArgumentException("IDL char exceeds 8 bits");
        writeByte((byte) value);
    }
    public void writeWChar(char value) {
        if (Character.isSurrogate(value)) throw new IllegalArgumentException("IDL wchar cannot be a surrogate");
        writeShort((short) value);
    }
    public void writeWString(String value, int bound) {
        Objects.requireNonNull(value);
        // A wide string is a byte count followed by UTF-16 code units, without a terminator.
        validateUnicode(value);
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("IDL wstring contains NUL");
        if (bound != 0 && Integer.toUnsignedLong(bound) < value.length())
            throw new IndexOutOfBoundsException("IDL wstring exceeds bound");
        writeInt(Math.multiplyExact(value.length(), 2));
        for (int i = 0; i < value.length(); i++) writeShort((short) value.charAt(i));
    }
    private static void validateUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Unpaired surrogate");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Unpaired surrogate");
        }
    }
    public void writeByte(byte value) { reserve(1, 1); buffer.put(value); }
    public void writeShort(short value) { reserve(2, 2); buffer.putShort(value); }
    public void writeInt(int value) { reserve(4, 4); buffer.putInt(value); }
    public void writeLong(long value) { reserve(8, isXcdr2() ? 4 : 8); buffer.putLong(value); }
    public void writeFloat(float value) { reserve(4, 4); buffer.putFloat(value); }
    public void writeDouble(double value) { reserve(8, isXcdr2() ? 4 : 8); buffer.putDouble(value); }

    /** IDL string: UTF-8 octets, length includes the trailing NUL. */
    public void writeString(String value) {
        writeString(value, 0);
    }
    public void writeString(String value, int bound) {
        Objects.requireNonNull(value);
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("IDL string contains NUL");
        // Reject unpaired surrogates instead of silently replacing them.
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Unpaired surrogate");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Unpaired surrogate");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bound != 0 && Integer.toUnsignedLong(bound) < bytes.length)
            throw new IndexOutOfBoundsException("IDL string exceeds bound");
        writeInt(Math.addExact(bytes.length, 1));
        reserve(Math.addExact(bytes.length, 1), 1);
        buffer.put(bytes).put((byte) 0);
    }

    public void writeSequenceLength(int length, int bound) {
        if (length < 0 || (bound != 0 && Integer.toUnsignedLong(bound) < length))
            throw new IndexOutOfBoundsException("IDL sequence exceeds bound");
        writeInt(length);
    }

    public void writeOctetSequence(byte[] bytes) {
        Objects.requireNonNull(bytes);
        writeInt(bytes.length);
        reserve(bytes.length, 1);
        buffer.put(bytes);
    }

    public byte[] toByteArray() { return Arrays.copyOf(buffer.array(), buffer.position()); }
    /** Start an XCDR2 delimited collection; finish with the returned position. */
    public int beginDelimited() { writeInt(0); return buffer.position(); }
    public int beginType(boolean appendable) { return beginType(appendable ? 1 : 0); }
    /** 0 = final, 1 = appendable, 2 = mutable. */
    public int beginType(int extensibility) {
        if (extensibility < 0 || extensibility > 2) throw new IllegalArgumentException("Invalid extensibility");
        if (typeDepth >= 128) throw new IllegalArgumentException("IDL nesting exceeds 128 types");
        if (typeDepth++ == 0) buffer.put(1, (byte) ((isXcdr2() ? 6 + 2 * extensibility : extensibility == 2 ? 2 : 0)
            + (buffer.order() == ByteOrder.LITTLE_ENDIAN ? 1 : 0)));
        return isXcdr2() ? extensibility != 0 ? beginDelimited() : 0 : extensibility == 2 ? -1 : 0;
    }
    public int beginMember(int id, boolean mustUnderstand) {
        if (id < 0 || id > 0x0fffffff) throw new IllegalArgumentException("Invalid member ID");
        if (!isXcdr2()) {
            reserve(0, 4);
            writeShort((short)0x7f01); writeShort((short)8);
            writeInt(id | (mustUnderstand ? 0x40000000 : 0));
            writeInt(0);
            origins.push(origin); origin = buffer.position();
            return origin;
        }
        writeInt(0x40000000 | (mustUnderstand ? 0x80000000 : 0) | id);
        return beginDelimited(); // LC=4: explicit NEXTINT length, valid for every member type.
    }
    public void endMember(int start) {
        endDelimited(start);
        if (!isXcdr2()) origin = origins.pop();
    }

    public void endType(int start) {
        if (start == -1) { reserve(0, 4); writeShort((short)0x7f02); writeShort((short)0); }
        else if (start != 0) endDelimited(start);
        typeDepth--;
    }
    public void endDelimited(int start) {
        if (start < 8 || start > buffer.position()) throw new IllegalArgumentException("Invalid delimiter position");
        buffer.putInt(start - 4, buffer.position() - start);
    }
}
