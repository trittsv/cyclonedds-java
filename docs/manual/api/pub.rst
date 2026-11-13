..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Publisher and writer
====================

``participant.createPublisher()`` creates a native publisher. Its
``createDataWriter(topic)`` method creates a typed writer. Supported operations
include ordinary/timestamped writes, instance registration/lookup and key-value
reconstruction, dispose/unregister, acknowledgment waits, listeners and standard
status getters. Timestamped registration overloads validate their timestamp;
registration remains local and sends no timestamped sample.

See :doc:`../howtos/qos` and :doc:`../howtos/exchange`.

See :doc:`../howtos/operations` for instance operations and listener behavior.
