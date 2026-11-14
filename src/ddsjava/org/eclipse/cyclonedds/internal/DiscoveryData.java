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

/** Owned snapshot of native discovery data; no native loan escapes JNI. */
public record DiscoveryData(int kind, int[] key, int[] participantKey, String topicName,
                            String typeName, Native.Qos qos, WireSample info) {}
