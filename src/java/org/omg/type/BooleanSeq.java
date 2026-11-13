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
public interface BooleanSeq extends java.util.List<Boolean>, java.io.Serializable {
    default boolean add(boolean value) { return add(Boolean.valueOf(value)); }
    default void add(int index, boolean value) { add(index, Boolean.valueOf(value)); }
    default void set(int index, boolean value) { set(index, Boolean.valueOf(value)); }
    default boolean add(boolean[] values) { return add(values, 0, values.length); }
    default boolean add(boolean[] values, int index) { return add(values, index, values.length - index); }
    default boolean add(boolean[] values, int index, int count) {
        java.util.Objects.checkFromIndexSize(index, count, values.length);
        for (int i = 0; i < count; i++) add(values[index + i]);
        return count != 0;
    }
    default void set(int destination, boolean[] values, int source, int count) {
        java.util.Objects.checkFromIndexSize(source, count, values.length);
        java.util.Objects.checkFromIndexSize(destination, count, size());
        for (int i = 0; i < count; i++) set(destination + i, values[source + i]);
    }
    default boolean[] toArray(boolean[] values) {
        if (values.length < size()) values = new boolean[size()];
        for (int i = 0; i < size(); i++) values[i] = get(i);
        return values;
    }
}
