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

package org.eclipse.cyclonedds.internal;

public record WireSample(byte[] cdr, boolean validData, long sourceTimestamp,
                         long instanceHandle, int instanceState, int sampleState, int viewState,
                         long publicationHandle, int disposedGenerationCount, int noWritersGenerationCount,
                         int sampleRank, int generationRank, int absoluteGenerationRank) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
}
