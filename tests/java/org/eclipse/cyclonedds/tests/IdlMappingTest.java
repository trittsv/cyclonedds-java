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

package org.eclipse.cyclonedds.tests;

import BindingTest.*;
import HelloWorld.Message;
import java.io.*;
import org.eclipse.cyclonedds.type.seq.IntegerSequence;

/** Exercises the supported IDL4-Java class and primitive sequence mapping. */
public final class IdlMappingTest {
    private IdlMappingTest() {}
    public static void main(String[] args) throws Exception {
        assert !Message.class.isRecord();
        assert Serializable.class.isAssignableFrom(Message.class);
        var message = new Message();
        assert message.get_id() == 0 && message.get_text().isEmpty();
        message.set_id(42); message.set_text("IDL4-Java");
        assert message.equals(new Message(42, "IDL4-Java"));
        var telemetry = new Telemetry();
        assert telemetry.get_position().get_x() == 0;
        assert telemetry.get_label().isEmpty() && telemetry.get_payload().isEmpty();
        assert Color.valueOf(1) == Color.GREEN && Color.GREEN.getValue() == 1;
        var escaped = new EscapedNames();
        escaped.set__class(3);
        assert escaped.get__class() == 3;
        telemetry.set_values(new IntegerSequence(new int[]{1,2,3}));
        telemetry.get_values().add(4);
        assert telemetry.get_values().get(3) == 4;
        assert java.util.Arrays.equals(telemetry.get_values().toArray(new int[0]), new int[]{1,2,3,4});
        Checks.rejects(IndexOutOfBoundsException.class, () -> telemetry.set_label("x".repeat(25)));
        Checks.rejects(IndexOutOfBoundsException.class, () -> telemetry.set_values(new IntegerSequence(new int[9])));
        var out = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(out)) { stream.writeObject(telemetry); }
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            assert stream.readObject().equals(telemetry);
        }
        System.out.println("IDL4-Java supported class, constructor, accessor, enum, sequence, bounds and serialization mapping passed");
    }
}
