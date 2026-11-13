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

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.*;
import org.eclipse.cyclonedds.topic.TypeSupport.Codec;

/** Collection codecs used by generated IDL code. */
public final class CdrCodecs {
    private CdrCodecs() {}
    public static <T> Codec<T> of(BiConsumer<CdrWriter, T> writer, Function<CdrReader, T> reader) {
        return new Codec<>() {
            public void write(CdrWriter w, T value) { writer.accept(w, value); }
            public T read(CdrReader r) { return reader.apply(r); }
        };
    }
    /** Applies recovery only to construction failures; malformed wire data still fails. */
    public static <T> Codec<T> recover(Codec<T> codec, int mode, Supplier<T> defaultValue) {
        return of(codec::write, r -> {
            int[] previous = r.beginRecovery(mode);
            T result;
            try { result = codec.read(r); }
            catch (RuntimeException e) { r.endRecovery(previous); throw e; }
            boolean recovered = r.endRecovery(previous);
            return recovered && mode == 1 ? defaultValue.get() : result;
        });
    }
    /** Value equality for mutable string mappings, including nested collections and arrays. */
    public static Object comparable(Object value) {
        if (value instanceof CharSequence text) return text.toString();
        if (value instanceof List<?> list) return list.stream().map(CdrCodecs::comparable).toArray();
        if (value != null && value.getClass().isArray()) {
            Object[] result = new Object[Array.getLength(value)];
            for (int i = 0; i < result.length; i++) result[i] = comparable(Array.get(value, i));
            return result;
        }
        return value;
    }
    public static <A, B> Codec<B> map(Codec<A> codec, Function<A, B> decode, Function<B, A> encode) {
        return of((w, value) -> codec.write(w, encode.apply(value)), r -> decode.apply(codec.read(r)));
    }
    private static void unsigned(long value, long maximum) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException("Unsigned IDL value out of range");
    }
    public static final Codec<Short> UINT8 = of((w, v) -> { unsigned(v, 255); w.writeByte(v.byteValue()); }, r -> (short) Byte.toUnsignedInt(r.readByte()));
    public static final Codec<Integer> UINT16 = of((w, v) -> { unsigned(v, 65535); w.writeShort(v.shortValue()); }, r -> Short.toUnsignedInt(r.readShort()));
    public static final Codec<Long> UINT32 = of((w, v) -> { unsigned(v, 0xffffffffL); w.writeInt(v.intValue()); }, r -> Integer.toUnsignedLong(r.readInt()));
    public static final Codec<java.math.BigInteger> UINT64 = of((w, v) -> {
        if (v.signum() < 0 || v.bitLength() > 64) throw new IllegalArgumentException("Unsigned IDL value out of range");
        w.writeLong(v.longValue());
    }, r -> { long v = r.readLong(); return v < 0 ? java.math.BigInteger.valueOf(v & Long.MAX_VALUE).setBit(63) : java.math.BigInteger.valueOf(v); });
    public static final Codec<Boolean> BOOLEAN = of(CdrWriter::writeBoolean, CdrReader::readBoolean);
    public static final Codec<Character> CHAR = of(CdrWriter::writeChar, CdrReader::readChar);
    public static final Codec<Character> WCHAR = of(CdrWriter::writeWChar, CdrReader::readWChar);
    public static final Codec<Byte> BYTE = of(CdrWriter::writeByte, CdrReader::readByte);
    public static final Codec<Short> SHORT = of(CdrWriter::writeShort, CdrReader::readShort);
    public static final Codec<Integer> INT = of(CdrWriter::writeInt, CdrReader::readInt);
    public static final Codec<Long> LONG = of(CdrWriter::writeLong, CdrReader::readLong);
    public static final Codec<Float> FLOAT = of(CdrWriter::writeFloat, CdrReader::readFloat);
    public static final Codec<Double> DOUBLE = of(CdrWriter::writeDouble, CdrReader::readDouble);
    public static <E> Codec<E> enumeration(int bits, ToIntFunction<E> value, IntFunction<E> factory) {
        return enumeration(bits, value, factory, () -> factory.apply(0));
    }
    public static <E> Codec<E> enumeration(int bits, ToIntFunction<E> value, IntFunction<E> factory, Supplier<E> defaultValue) {
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("Invalid enum bit bound");
        return of((w, e) -> {
            int n = value.applyAsInt(e);
            if (!w.isXcdr2() || bits > 16) w.writeInt(n); else if (bits <= 8) w.writeByte((byte) n); else w.writeShort((short) n);
        }, r -> {
            int n = !r.isXcdr2() || bits > 16 ? r.readInt() : bits <= 8 ? Byte.toUnsignedInt(r.readByte()) : Short.toUnsignedInt(r.readShort());
            try { return factory.apply(n); }
            catch (IllegalArgumentException e) { if (!r.recoverValue()) throw e; return defaultValue.get(); }
        });
    }
    public static Codec<String> string(int bound) { return of((w, value) -> w.writeString(value, bound), r -> r.readString(bound)); }
    public static Codec<String> wstring(int bound) { return of((w, value) -> w.writeWString(value, bound), r -> r.readWString(bound)); }
    public static <T> Codec<T> optional(Codec<T> element) {
        return optional(element, 0, false);
    }
    public static <T> Codec<T> optional(Codec<T> element, int id, boolean mustUnderstand) {
        return of((w, value) -> {
            if (w.isXcdr2()) { w.writeBoolean(value != null); if (value != null) element.write(w, value); }
            else { int start = w.beginMember(id, mustUnderstand); if (value != null) element.write(w, value); w.endMember(start); }
        }, r -> {
            if (r.isXcdr2()) return r.readBoolean() ? element.read(r) : null;
            if (!r.beginOptional(id)) return null;
            T value = element.read(r); r.endMember(); return value;
        });
    }
    /** Resolves recursive codec references only when a value is actually encoded. */
    public static <T> Codec<T> lazy(Supplier<Codec<T>> supplier) {
        return of((w, value) -> supplier.get().write(w, value), r -> supplier.get().read(r));
    }
    public static <T, L extends List<T>> Codec<L> primitiveSequence(Codec<T> element, int bound, Supplier<L> factory) {
        return new Codec<>() {
            public void write(CdrWriter w, L values) {
                w.writeSequenceLength(values.size(), bound);
                for (T value : values) element.write(w, Objects.requireNonNull(value));
            }
            public L read(CdrReader r) {
                int width = element == LONG || element == DOUBLE || element == UINT64 ? 8 : element == INT || element == FLOAT || element == UINT32 ? 4 : element == SHORT || element == WCHAR || element == UINT16 ? 2 : 1;
                int count = r.readSequenceLength(bound, width);
                int limit = r.sequenceLimit(count, bound);
                L values = factory.get();
                for (int i = 0; i < count; i++) { r.prepareElement(); T value = element.read(r); if (i < limit) values.add(value); }
                return values;
            }
        };
    }
    @SuppressWarnings("unchecked") public static <A> A newArray(Class<?> component, int[] dimensions, Supplier<?> initializer) {
        Object array = Array.newInstance(component, dimensions);
        initialize(array, dimensions.length, initializer);
        return (A) array;
    }
    private static void initialize(Object array, int depth, Supplier<?> initializer) {
        for (int i = 0; i < Array.getLength(array); i++) {
            if (depth == 1) Array.set(array, i, initializer.get());
            else initialize(Array.get(array, i), depth - 1, initializer);
        }
    }
    public static void validateArray(Object value, int[] dimensions) { validateArray(value, dimensions, 0); }
    private static void validateArray(Object value, int[] dimensions, int index) {
        if (Array.getLength(Objects.requireNonNull(value)) != dimensions[index]) throw new IndexOutOfBoundsException("IDL array length mismatch");
        if (index + 1 < dimensions.length) for (int i = 0; i < dimensions[index]; i++) validateArray(Array.get(value, i), dimensions, index + 1);
    }
    public static <T> Codec<List<T>> sequence(Codec<T> element, int bound, boolean delimited) {
        return new Codec<>() {
            public void write(CdrWriter w, List<T> values) {
                Objects.requireNonNull(values);
                int start = delimited && w.isXcdr2() ? w.beginDelimited() : 0;
                w.writeSequenceLength(values.size(), bound);
                for (T value : values) element.write(w, Objects.requireNonNull(value));
                if (start != 0) w.endDelimited(start);
            }
            public List<T> read(CdrReader r) {
                int end = delimited && r.isXcdr2() ? r.beginDelimited() : 0;
                int count = r.readSequenceLength(bound, 1);
                int limit = r.sequenceLimit(count, bound);
                List<T> values = new ArrayList<>(limit);
                for (int i = 0; i < count; i++) { r.prepareElement(); T value = element.read(r); if (i < limit) values.add(value); }
                if (end != 0) r.endDelimited(end);
                return values;
            }
        };
    }
    public static <A> Codec<A> array(Class<?> component, int length, Codec<?> element, boolean delimited) {
        return new Codec<>() {
            @SuppressWarnings("unchecked") public void write(CdrWriter w, A value) {
                if (Array.getLength(Objects.requireNonNull(value)) != length) throw new IndexOutOfBoundsException("IDL array length mismatch");
                int start = delimited && w.isXcdr2() ? w.beginDelimited() : 0;
                for (int i = 0; i < length; i++) ((Codec<Object>) element).write(w, Objects.requireNonNull(Array.get(value, i)));
                if (start != 0) w.endDelimited(start);
            }
            @SuppressWarnings("unchecked") public A read(CdrReader r) {
                int end = delimited && r.isXcdr2() ? r.beginDelimited() : 0;
                A value = (A) Array.newInstance(component, length);
                for (int i = 0; i < length; i++) { r.prepareElement(); Array.set(value, i, element.read(r)); }
                if (end != 0) r.endDelimited(end);
                return value;
            }
        };
    }
}
