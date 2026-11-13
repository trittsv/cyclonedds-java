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
public interface CharSeq extends java.util.List<Character>, java.io.Serializable {
    default boolean add(char value) { return add(Character.valueOf(value)); }
    default void add(int index, char value) { add(index, Character.valueOf(value)); }
    default void set(int index, char value) { set(index, Character.valueOf(value)); }
    default boolean add(char[] values) { return add(values, 0, values.length); }
    default boolean add(char[] values, int index) { return add(values, index, values.length - index); }
    default boolean add(char[] values, int index, int count) {
        java.util.Objects.checkFromIndexSize(index, count, values.length);
        for (int i = 0; i < count; i++) add(values[index + i]);
        return count != 0;
    }
    default void set(int destination, char[] values, int source, int count) {
        java.util.Objects.checkFromIndexSize(source, count, values.length);
        java.util.Objects.checkFromIndexSize(destination, count, size());
        for (int i = 0; i < count; i++) set(destination + i, values[source + i]);
    }
    default char[] toArray(char[] values) {
        if (values.length < size()) values = new char[size()];
        for (int i = 0; i < size(); i++) values[i] = get(i);
        return values;
    }
}
