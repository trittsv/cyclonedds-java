..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Waitsets and conditions
================================================================================


.. code-block:: java

   var waitset = org.omg.dds.core.WaitSet.newWaitSet(environment);
   try (var condition = selection.getCondition()) {
       waitset.attachCondition(condition);
       waitset.waitForConditions(5, java.util.concurrent.TimeUnit.SECONDS);
       var batch = selection.take(null);
   } finally {
       if (waitset instanceof AutoCloseable resource) resource.close();
   }

A read condition tracks the configured sample/view/instance states. A selector
caches its condition while that state configuration is unchanged; changing its
state can create a new condition, whose lifetime belongs to the reader/caller.
Instance-restricted conditions remain unsupported. Closing a read condition or
its reader removes the condition from attached waitsets. If a thread was waiting
on that attachment, it wakes with ``AlreadyClosedException``.

Guard conditions can be triggered from another thread for application wakeup.
Status conditions expose applicable native status bits; use publication or
subscription matched status conditions for discovery. Reading the corresponding
matched-status getter clears that status's change flag. Check each relevant
status predicate before blocking, since events can occur before attachment.

A waitset allows one blocked thread; a second concurrent wait raises
``PreconditionNotMetException``. Attach/detach operations can run while waiting.
An empty waitset waits until attachment/wakeup or timeout. A finite wait without
triggered conditions raises ``TimeoutException``\ ; zero timeout checks immediately.
The active-condition collection is replaced with the triggered conditions.
Waits do not hold a Java participant monitor, so publishing/closing from another
thread can proceed.

The normative WaitSet and GuardCondition APIs have no close method. Provider
instances additionally implement ``AutoCloseable`` for deterministic cleanup and
register a GC cleanup action. Keep waitsets/guards alive while using them, and
close them through that optional interface when the application's scope ends.
Read conditions use their standard ``close()`` method.

``readNextSample(sample)`` and ``takeNextSample(sample)`` update an existing sample
returned by this provider and select only NOT_READ samples. They return false
without changing it when no unread sample exists. Obtain a sample from a list
read/take, or clone a provider sample for reusable storage. A sample of a foreign
type/provider is rejected before accessing DDS.
