/* Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause */
import MavenDemo.Message;

public final class Demo {
    private Demo() {}
    public static void main(String[] args) {
        var message = new Message(42, "Generated during Maven build");
        if (message.get_id() != 42) throw new AssertionError();
        System.out.println(message.get_text());
    }
}
