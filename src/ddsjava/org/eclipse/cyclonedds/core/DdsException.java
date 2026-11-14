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

/** A failed operation in the native Cyclone DDS runtime. */
public final class DdsException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code;

    public DdsException(String operation, int code, String reason) {
        super(operation + ": " + reason + " (" + code + ")");
        this.code = code;
    }

    public int code() { return code; }
}
