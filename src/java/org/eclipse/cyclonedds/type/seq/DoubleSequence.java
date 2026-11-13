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

package org.eclipse.cyclonedds.type.seq;

/** Mutable implementation of the IDL4-Java DoubleSeq interface. */
public final class DoubleSequence extends java.util.ArrayList<Double> implements org.omg.type.DoubleSeq {
    private static final long serialVersionUID = 1L;
    public DoubleSequence() {}
    public DoubleSequence(int initialCapacity) { super(initialCapacity); }
    public DoubleSequence(double[] values) { super(values.length); add(values); }
}
