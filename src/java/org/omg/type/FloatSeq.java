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

package org.omg.type;

/** IDL4-Java primitive sequence. Boxed List access also supports Java unboxing. */
public interface FloatSeq extends java.util.List<Float>, java.io.Serializable {
    default boolean add(float value) { return add(Float.valueOf(value)); }
    default void add(int index, float value) { add(index, Float.valueOf(value)); }
    default void set(int index, float value) { set(index, Float.valueOf(value)); }
    default boolean add(float[] values) { return add(values, 0, values.length); }
    default boolean add(float[] values, int index) { return add(values, index, values.length - index); }
    default boolean add(float[] values, int index, int count) {
        java.util.Objects.checkFromIndexSize(index, count, values.length);
        for (int i = 0; i < count; i++) add(values[index + i]);
        return count != 0;
    }
    default void set(int destination, float[] values, int source, int count) {
        java.util.Objects.checkFromIndexSize(source, count, values.length);
        java.util.Objects.checkFromIndexSize(destination, count, size());
        for (int i = 0; i < count; i++) set(destination + i, values[source + i]);
    }
    default float[] toArray(float[] values) {
        if (values.length < size()) values = new float[size()];
        for (int i = 0; i < size(); i++) values[i] = get(i);
        return values;
    }
}
