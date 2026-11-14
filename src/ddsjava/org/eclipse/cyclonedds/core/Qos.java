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

package org.eclipse.cyclonedds.core;

/** Endpoint reliability and KEEP_LAST history. Both endpoints use XCDR2. */
public record Qos(boolean reliable, int historyDepth) {
    public static final Qos DEFAULT = new Qos(true, 32);
    public Qos {
        if (historyDepth < 1) throw new IllegalArgumentException("historyDepth must be positive");
    }
}
