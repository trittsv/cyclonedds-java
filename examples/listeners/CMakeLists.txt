# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

add_jar(listeners-example SOURCES java/org/eclipse/cyclonedds/examples/Listeners.java
  INCLUDE_JARS example-common omg-dds-api idl4-java-api "$<TARGET_PROPERTY:hello-types,JAR_FILE>")
add_dependencies(listeners-example hello-types)
