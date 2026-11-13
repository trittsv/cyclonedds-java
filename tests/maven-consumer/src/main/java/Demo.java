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
import MavenDemo.Message;

public final class Demo {
    private Demo() {}
    public static void main(String[] args) {
        var message = new Message(42, "Generated during Maven build");
        if (message.get_id() != 42) throw new AssertionError();
        System.out.println(message.get_text());
    }
}
