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

package org.eclipse.cyclonedds.topic;

import java.util.Objects;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;

/** Java codec and language-neutral XTypes metadata emitted by the idljava backend.
 * Representation flags use bit 0 for XCDR1 and bit 2 for XCDR2. Generated types
 * restrict these flags to the schemas supported by the unmodified core. */
public record TypeSupport<T>(String typeName, boolean hasKey, byte[] typeInformation,
                             byte[] typeMapping, Codec<T> codec, int allowedDataRepresentations) {
    public TypeSupport(String name, boolean keyed, byte[] information, byte[] mapping, Codec<T> codec) {
        this(name, keyed, information, mapping, codec, 5);
    }
    public TypeSupport {
        Objects.requireNonNull(typeName);
        typeInformation = Objects.requireNonNull(typeInformation).clone();
        typeMapping = Objects.requireNonNull(typeMapping).clone();
        Objects.requireNonNull(codec);
        if (allowedDataRepresentations == 0 || (allowedDataRepresentations & ~5) != 0)
            throw new IllegalArgumentException("Expected XCDR1/XCDR2 representation flags");
        if (typeName.isBlank() || typeName.indexOf('\0') >= 0 || typeInformation.length == 0 || typeMapping.length == 0)
            throw new IllegalArgumentException("Type name and XTypes metadata are required");
    }
    @Override public byte[] typeInformation() { return typeInformation.clone(); }
    @Override public byte[] typeMapping() { return typeMapping.clone(); }

    /** Encodes fields in IDL declaration order; the runtime supplies the encapsulation header. */
    public interface Codec<T> {
        void write(CdrWriter writer, T value);
        T read(CdrReader reader);
        /** Copies mapped key fields into an existing holder; generated from IDL key paths. */
        default void copyKeys(T target, T source) {
            throw new UnsupportedOperationException("Regenerate this type to support key-value holder updates");
        }
    }
}
