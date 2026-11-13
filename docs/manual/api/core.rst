..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Core
====

``org.omg.dds.core.ServiceEnvironment`` selects a provider using
``org.omg.dds.serviceClassName``. Use the value
``org.eclipse.cyclonedds.omg.CycloneServiceEnvironment``.

``WaitSet``, ``GuardCondition``, ``StatusCondition`` and reader conditions provide
blocking event access. ``Duration``, ``Time`` and ``InstanceHandle`` carry standard
values. Supported native failures map to standard DDS exceptions.

See :doc:`../howtos/waitset` and :doc:`../howtos/qos`.
