# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

# The C generator is used exclusively by the independent interoperability peer.
idlc_generate(TARGET interop-c-types FILES
  "${PROJECT_SOURCE_DIR}/examples/helloworld/HelloWorld.idl"
  "${CMAKE_CURRENT_SOURCE_DIR}/idl/Types.idl" DEFAULT_EXTENSIBILITY final)
add_executable(interop-peer c/interop_peer.c)
target_link_libraries(interop-peer PRIVATE interop-c-types CycloneDDS::ddsc)
add_executable(collections-peer c/collections_peer.c)
target_link_libraries(collections-peer PRIVATE interop-c-types CycloneDDS::ddsc)
add_executable(structured-peer c/structured_peer.c)
target_link_libraries(structured-peer PRIVATE interop-c-types CycloneDDS::ddsc)
add_executable(broader-peer c/broader_peer.c)
target_link_libraries(broader-peer PRIVATE interop-c-types CycloneDDS::ddsc)
add_executable(qos-peer c/qos_peer.c)
target_link_libraries(qos-peer PRIVATE interop-c-types CycloneDDS::ddsc)
add_executable(discovery-peer c/discovery_peer.c)
target_link_libraries(discovery-peer PRIVATE interop-c-types CycloneDDS::ddsc)
