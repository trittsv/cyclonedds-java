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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounded XCDR1/XCDR2 reader for final, appendable and mutable types, either endian. */
public final class CdrReader {
    private final ByteBuffer buffer;
    private final int maxAlignment;
    private int origin = 4;
    private final java.util.ArrayDeque<Integer> memberOrigins = new java.util.ArrayDeque<>();
    private int typeDepth;
    private boolean nextValueBounded;
    private final java.util.ArrayDeque<Boolean> typeBounds = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> memberDepths = new java.util.ArrayDeque<>();
    /** Generated fields propagate a surrounding value boundary only at the tail.
     * Parameter bodies always supply their own boundary, including non-tail members. */
    public void prepareValue(boolean tail) {
        nextValueBounded = (!memberDepths.isEmpty() && memberDepths.peek() == typeDepth)
            || (tail && !typeBounds.isEmpty() && typeBounds.peek());
    }
    /** Collection elements have no independent XCDR1 length boundary. */
    public void prepareElement() { nextValueBounded = false; }
    private int recoveryMode;
    private boolean recovered;
    /** Starts a generated member's try-construct scope (0 discard, 1 default, 2 trim). */
    public int[] beginRecovery(int mode) {
        int[] previous = {recoveryMode, recovered ? 1 : 0};
        recoveryMode = mode; recovered = false; return previous;
    }
    public boolean endRecovery(int[] previous) {
        boolean result = recovered;
        recoveryMode = previous[0]; recovered = previous[1] != 0; return result;
    }
    public boolean recoverValue() {
        if (recoveryMode == 0) return false;
        recovered = true; return true;
    }
    public int sequenceLimit(int count, int bound) {
        if (bound != 0 && Integer.toUnsignedLong(bound) < count) {
            if (!recoverValue()) throw malformed("Sequence exceeds bound");
            return recoveryMode == 1 ? 0 : bound;
        }
        return count;
    }
    private final int encapsulation;
    private final java.util.ArrayDeque<Integer> limits = new java.util.ArrayDeque<>();

    public CdrReader(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length < 4) throw malformed("Missing encapsulation");
        int id = (Byte.toUnsignedInt(bytes[0]) << 8) | Byte.toUnsignedInt(bytes[1]);
        if (id != 0 && id != 1 && id != 2 && id != 3 && id != 6 && id != 7 && id != 8 && id != 9 && id != 10 && id != 11)
            throw malformed("Unsupported encapsulation " + id);
        if (bytes[2] != 0 || (bytes[3] & 0xfc) != 0)
            throw malformed("Unsupported encapsulation options");
        int padding = bytes[3] & 3;
        if (bytes.length - 4 < padding) throw malformed("Invalid trailing padding");
        buffer = ByteBuffer.wrap(bytes).order((id & 1) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        buffer.limit(bytes.length - padding);
        buffer.position(4);
        maxAlignment = id < 4 ? 8 : 4; encapsulation = id;
    }

    private static IllegalArgumentException malformed(String reason) {
        return new IllegalArgumentException("Malformed CDR: " + reason);
    }
    private void require(int count, int alignment) {
        int padding = (-(buffer.position() - origin)) & (Math.min(alignment, maxAlignment) - 1);
        if (count < 0 || padding > buffer.remaining() || count > buffer.remaining() - padding)
            throw malformed("Truncated payload or invalid length");
        buffer.position(buffer.position() + padding);
    }

    public boolean readBoolean() {
        byte value = readByte();
        if (value != 0 && value != 1) throw malformed("Invalid boolean");
        return value != 0;
    }
    public byte readByte() { require(1, 1); return buffer.get(); }
    public char readChar() { return (char) Byte.toUnsignedInt(readByte()); }
    public char readWChar() {
        char value = (char) Short.toUnsignedInt(readShort());
        if (Character.isSurrogate(value)) throw malformed("Surrogate wchar");
        return value;
    }
    public String readWString(int bound) {
        int bytes = readInt();
        if (bytes < 0 || (bytes & 1) != 0) throw malformed("Invalid wstring byte length");
        require(bytes, 2);
        ByteBuffer text = buffer.slice().order(buffer.order()); text.limit(bytes);
        try {
            String value = (buffer.order() == ByteOrder.LITTLE_ENDIAN ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE)
                .newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString();
            if (value.indexOf('\0') >= 0) throw malformed("Embedded NUL in wstring");
            boolean oversized = bound != 0 && Integer.toUnsignedLong(bound) < value.length();
            if (oversized) {
                if (!recoverValue()) throw malformed("Wstring exceeds bound");
                int end = bound;
                if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                value = recoveryMode == 1 ? "" : value.substring(0, end);
            }
            buffer.position(buffer.position() + bytes); return value;
        } catch (CharacterCodingException e) { throw malformed("Invalid UTF-16"); }
    }
    public short readShort() { require(2, 2); return buffer.getShort(); }
    public int readInt() { require(4, 4); return buffer.getInt(); }
    public long readLong() { require(8, 8); return buffer.getLong(); }
    public float readFloat() { require(4, 4); return buffer.getFloat(); }
    public double readDouble() { require(8, 8); return buffer.getDouble(); }

    public String readString() {
        return readString(0);
    }
    public String readString(int bound) {
        int length = readInt();
        if (length < 1) throw malformed("Invalid string length");
        require(length, 1);
        int start = buffer.position();
        if (buffer.get(start + length - 1) != 0) throw malformed("Missing string terminator");
        for (int i = start; i < start + length - 1; i++)
            if (buffer.get(i) == 0) throw malformed("Embedded NUL in string");
        ByteBuffer text = buffer.slice(); text.limit(length - 1);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            String value = decoder.decode(text).toString();
            if (bound != 0 && Integer.toUnsignedLong(bound) < length - 1L) {
                if (!recoverValue()) throw malformed("String exceeds bound");
                int end = bound;
                while (end > 0 && (buffer.get(start + end) & 0xc0) == 0x80) end--;
                ByteBuffer prefix = buffer.slice(); prefix.limit(end);
                value = recoveryMode == 1 ? "" : decoder.reset().decode(prefix).toString();
            }
            buffer.position(start + length); return value;
        } catch (CharacterCodingException e) { throw malformed("Invalid UTF-8"); }
    }

    public int readSequenceLength(int bound, int elementBytes) {
        int length = readInt();
        if (elementBytes < 1 || length < 0 || (bound != 0 && Integer.toUnsignedLong(bound) < length && !recoverValue())
                || length > buffer.remaining() / elementBytes)
            throw malformed("Invalid sequence length");
        return length;
    }

    public byte[] readOctetSequence() {
        int length = readInt();
        require(length, 1);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }
    public boolean isXcdr2() { return maxAlignment == 4; }
    public int beginType(boolean appendable) { return beginType(appendable ? 1 : 0); }
    public int beginType(int extensibility) {
        if (typeDepth == 0 && isXcdr2() && (encapsulation - 6) / 2 != extensibility) throw malformed("Encapsulation does not match type extensibility");
        if (typeDepth == 0 && !isXcdr2() && (encapsulation >= 2) != (extensibility == 2)) throw malformed("Encapsulation does not match type extensibility");
        boolean bounded = typeDepth == 0 || nextValueBounded;
        nextValueBounded = false;
        typeBounds.push(bounded);
        if (++typeDepth > 128) throw malformed("IDL nesting exceeds 128 types");
        if (extensibility != 0 && isXcdr2()) return beginDelimited();
        if (extensibility == 1 && bounded) {
            limits.push(buffer.limit());
            return buffer.limit();
        }
        return 0;
    }
    private record Member(int start, int end, boolean mustUnderstand) {}
    public static final class Mutable {
        private final java.util.Map<Integer, Member> members = new java.util.LinkedHashMap<>();
        private final java.util.Set<Integer> read = new java.util.HashSet<>();
        private int end;
        private Mutable(int end) { this.end = end; }
    }
    public Mutable beginMutable() {
        Mutable frame = new Mutable(beginType(2));
        if (!isXcdr2()) {
            for (;;) {
                var parameter = parameter();
                if (parameter.sentinel) { frame.end = buffer.position(); return frame; }
                int start = buffer.position(), end = start + parameter.length;
                if (frame.members.putIfAbsent(parameter.id, new Member(start, end, parameter.mustUnderstand)) != null)
                    throw malformed("Duplicate mutable member ID");
                buffer.position(end);
            }
        }
        while (hasRemaining()) {
            // A parameter list may finish with alignment padding.
            if (buffer.remaining() < 4) { require(buffer.remaining(), 1); buffer.position(buffer.limit()); break; }
            int header = readInt(), code = (header >>> 28) & 7, id = header & 0x0fffffff;
            long length;
            if (code < 4) length = 1L << code;
            else if (code == 4) length = Integer.toUnsignedLong(readInt());
            else {
                require(4, 4);
                long count = Integer.toUnsignedLong(buffer.getInt(buffer.position()));
                length = 4 + count * (code == 5 ? 1 : code == 6 ? 4 : 8);
            }
            if (length > Integer.MAX_VALUE) throw malformed("Mutable member exceeds Java limits");
            require((int) length, 1); int start = buffer.position(), end = start + (int) length;
            if (frame.members.putIfAbsent(id, new Member(start, end, (header & 0x80000000) != 0)) != null)
                throw malformed("Duplicate mutable member ID");
            buffer.position(end);
        }
        return frame;
    }
    public boolean beginMember(Mutable frame, int id) {
        Member member = frame.members.get(id);
        if (member == null) return false;
        frame.read.add(id); buffer.position(member.start());
        enterMember(member.end()); return true;
    }
    private record Parameter(int id, int length, boolean mustUnderstand, boolean sentinel) {}
    private Parameter parameter() {
        require(0, 4);
        int header = Short.toUnsignedInt(readShort()), length = Short.toUnsignedInt(readShort());
        int id = header & 0xbfff;
        boolean mustUnderstand = (header & 0x4000) != 0;
        boolean sentinel = id == 0x3f02;
        if (id == 0x3f01) {
            if (length != 8) throw malformed("Invalid extended parameter header");
            header = readInt(); length = readInt();
            id = header & 0x8fffffff; mustUnderstand = (header & 0x40000000) != 0;
        } else if (id == 0x3f02 && length != 0) throw malformed("Invalid parameter-list sentinel");
        require(length, 1);
        return new Parameter(id, length, mustUnderstand, sentinel);
    }
    private void enterMember(int end) {
        limits.push(buffer.limit()); buffer.limit(end);
        memberOrigins.push(origin);
        memberDepths.push(typeDepth); nextValueBounded = true;
        if (!isXcdr2()) origin = buffer.position();
    }
    public boolean beginOptional(int id) {
        if (isXcdr2()) throw malformed("XCDR1 optional header expected");
        var parameter = parameter();
        if (parameter.sentinel || parameter.id != id) throw malformed("Unexpected optional member ID");
        if (parameter.length == 0) return false;
        enterMember(buffer.position() + parameter.length); return true;
    }
    public void endMember() {
        // XTypes 1.1 implementations include final four-byte padding in the length.
        // Accept that form as well as the unpadded XTypes 1.3 form.
        if (!isXcdr2() && buffer.remaining() <= 3) while (buffer.hasRemaining())
            if (buffer.get() != 0) throw malformed("Invalid parameter padding");
        if (limits.isEmpty() || buffer.hasRemaining()) throw malformed("Invalid mutable member length");
        buffer.limit(limits.pop()); origin = memberOrigins.pop();
        memberDepths.pop(); nextValueBounded = false;
    }
    public void endMutable(Mutable frame) {
        for (var entry : frame.members.entrySet())
            if (entry.getValue().mustUnderstand() && !frame.read.contains(entry.getKey())) throw malformed("Unknown must-understand member " + entry.getKey());
        if (isXcdr2()) endType(frame.end);
        else { buffer.position(frame.end); endType(0); }
    }
    public boolean hasRemaining() { return buffer.hasRemaining(); }
    public void endType(int end) {
        if (typeBounds.isEmpty()) throw malformed("Unbalanced type frame");
        typeBounds.pop(); nextValueBounded = false;
        if (--typeDepth < 0) throw malformed("Unbalanced type frame");
        if (end != 0) { buffer.position(end); endDelimited(end); }
    }
    public int beginDelimited() {
        int length = readInt();
        require(length, 1);
        int end = buffer.position() + length;
        limits.push(buffer.limit()); buffer.limit(end);
        return end;
    }
    public void endDelimited(int end) {
        if (limits.isEmpty() || buffer.position() != end || buffer.limit() != end) throw malformed("Invalid delimited collection length");
        buffer.limit(limits.pop());
    }
}
