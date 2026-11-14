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
public interface DoubleSeq extends java.util.List<Double>, java.io.Serializable {
    default boolean add(double value) { return add(Double.valueOf(value)); }
    default void add(int index, double value) { add(index, Double.valueOf(value)); }
    default void set(int index, double value) { set(index, Double.valueOf(value)); }
    default boolean add(double[] values) { return add(values, 0, values.length); }
    default boolean add(double[] values, int index) { return add(values, index, values.length - index); }
    default boolean add(double[] values, int index, int count) {
        java.util.Objects.checkFromIndexSize(index, count, values.length);
        for (int i = 0; i < count; i++) add(values[index + i]);
        return count != 0;
    }
    default void set(int destination, double[] values, int source, int count) {
        java.util.Objects.checkFromIndexSize(source, count, values.length);
        java.util.Objects.checkFromIndexSize(destination, count, size());
        for (int i = 0; i < count; i++) set(destination + i, values[source + i]);
    }
    default double[] toArray(double[] values) {
        if (values.length < size()) values = new double[size()];
        for (int i = 0; i < size(); i++) values[i] = get(i);
        return values;
    }
}
