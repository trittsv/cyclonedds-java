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

/** Mutable implementation of the IDL4-Java CharSeq interface. */
public final class CharSequence extends java.util.ArrayList<Character> implements org.omg.type.CharSeq {
    private static final long serialVersionUID = 1L;
    public CharSequence() {}
    public CharSequence(int initialCapacity) { super(initialCapacity); }
    public CharSequence(char[] values) { super(values.length); add(values); }
}
