..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Subscriber and reader
=====================

``participant.createSubscriber()`` creates a native subscriber. Its
``createDataReader(topic)`` method creates a typed reader. Readers expose
``read`` / ``take``, reusable state/instance/content selectors, next-instance
traversal, read/query conditions, key reconstruction, content-filtered topics,
listener callbacks, sample metadata, historical-data waits and standard status getters.

Iterator results must be closed. List results are copied; provider-owned sample
objects can be reused. ``readNextSample`` / ``takeNextSample`` update existing
provider samples with unread data.

See :doc:`../howtos/exchange` and :doc:`../howtos/waitset`.

See :doc:`../howtos/operations` for queries, filters and listener behavior.
