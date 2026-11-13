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

import java.util.*;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.topic.TypeSupport;

/** Compile and exercise the exact IDL fixtures used by IDLCXXRecursiveProbes. */
public final class UpstreamRecursiveTest {
    private static <T> T roundTrip(TypeSupport.Codec<T> codec, T value) {
        var writer = new CdrWriter(); codec.write(writer, value);
        var reader = new CdrReader(writer.toByteArray());
        T copy = codec.read(reader);
        assert copy.equals(value) && copy.hashCode() == value.hashCode();
        assert !reader.hasRemaining();
        return copy;
    }
    public static void main(String[] args) {
        switch (args[0]) {
        case "optional-self": {
            var leaf = new optional_probe.optional_node(2, null);
            var root = new optional_probe.optional_node(1, leaf);
            var copy = roundTrip(optional_probe.optional_node.codec(), root);
            assert copy.get_child() != leaf && copy.get_child().get_child() == null;
            leaf.set_child(root);
            Checks.rejects(IllegalArgumentException.class, () -> optional_probe.optional_node.codec().write(new CdrWriter(), root));
            break;
        }
        case "optional-array": {
            var root = new optional_array_probe.node(new optional_array_probe.node[]{new optional_array_probe.node(), new optional_array_probe.node()});
            var copy = roundTrip(optional_array_probe.node.codec(), root);
            assert copy.get_child().length == 2 && copy.get_child()[0].get_child() == null;
            Checks.rejects(IndexOutOfBoundsException.class, () -> root.set_child(new optional_array_probe.node[1]));
            break;
        }
        case "optional-external": {
            roundTrip(optional_external_probe.holder.codec(), new optional_external_probe.holder());
            var root = new optional_external_probe.holder(new optional_external_probe.leaf(123));
            assert roundTrip(optional_external_probe.holder.codec(), root).get_child().get_value() == 123;
            break;
        }
        case "external-union": {
            var leaf = new external_union_probe.external_union(); leaf.set_value(42);
            var root = new external_union_probe.external_union(); root.set_child(leaf);
            assert roundTrip(external_union_probe.external_union.codec(), root).get_child().get_value() == 42;
            root.set_child(root);
            Checks.rejects(IllegalArgumentException.class, () -> external_union_probe.external_union.codec().write(new CdrWriter(), root));
            break;
        }
        case "mutual-sequence": {
            var leaf = new mutual_sequence_probe.a();
            var branch = new mutual_sequence_probe.b(List.of(leaf));
            var root = new mutual_sequence_probe.a(List.of(branch));
            assert roundTrip(mutual_sequence_probe.a.codec(), root).get_bs().get(0).get_as().get(0).get_bs().isEmpty();
            leaf.set_bs(List.of(branch));
            Checks.rejects(IllegalArgumentException.class, () -> mutual_sequence_probe.a.codec().write(new CdrWriter(), root));
            break;
        }
        case "optional-sequence": {
            var root = new optional_sequence_probe.optional_sequence_node(1,
                List.of(new optional_sequence_probe.optional_sequence_node(2, null)));
            assert roundTrip(optional_sequence_probe.optional_sequence_node.codec(), root).get_children().get(0).get_children() == null;
            roundTrip(optional_sequence_probe.optional_sequence_node.codec(), new optional_sequence_probe.optional_sequence_node(1, List.of()));
            break;
        }
        case "optional-typedef-sequence": {
            var root = new optional_typedef_sequence_probe.node(List.of(new optional_typedef_sequence_probe.node()));
            assert roundTrip(optional_typedef_sequence_probe.node.codec(), root).get_children().get(0).get_children() == null;
            break;
        }
        default: throw new AssertionError(args[0]);
        }
        System.out.println(args[0] + " passed");
    }
}
