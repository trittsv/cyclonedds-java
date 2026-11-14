# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
file(GLOB_RECURSE IDLC_LAUNCHER_SOURCES CONFIGURE_DEPENDS src/main/java/*.java)
add_jar(idlc-launcher SOURCES ${IDLC_LAUNCHER_SOURCES}
  ENTRY_POINT org.eclipse.cyclonedds.idlc.Idlc OUTPUT_NAME org.eclipse.cyclonedds.idlc-launcher)
set(BUNDLED_IDLC_NAME "org.eclipse.cyclonedds.idlc-${PROJECT_VERSION}-${JAVA_NATIVE_PLATFORM}.jar")
set(BUNDLED_IDLC_JAR "${CMAKE_BINARY_DIR}/${BUNDLED_IDLC_NAME}")
add_custom_command(OUTPUT "${BUNDLED_IDLC_JAR}"
  COMMAND ${CMAKE_COMMAND}
    "-DSTAGING=${CMAKE_BINARY_DIR}/idlc-bundle"
    "-DLAUNCHER_JAR=$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>"
    "-DIDLC=$<TARGET_FILE:CycloneDDS::idlc>" "-DBACKEND=$<TARGET_FILE:idljava>"
    "-DVERSION=${PROJECT_VERSION}" "-DPLATFORM=${JAVA_NATIVE_PLATFORM}" "-DJAR=${Java_JAR_EXECUTABLE}"
    "-DOUTPUT=${BUNDLED_IDLC_JAR}" "-DSOURCE_DIR=${PROJECT_SOURCE_DIR}"
    -P "${PROJECT_SOURCE_DIR}/cmake/BundleIdlc.cmake"
  DEPENDS idlc-launcher idljava CycloneDDS::idlc CycloneDDS::idl CycloneDDS::ddsc
    "$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>" "$<TARGET_FILE:idljava>"
    "${PROJECT_SOURCE_DIR}/cmake/BundleIdlc.cmake" "${PROJECT_SOURCE_DIR}/third_party/idlc/LICENSE-mcpp"
    "${PROJECT_SOURCE_DIR}/LICENSE" "${PROJECT_SOURCE_DIR}/THIRD_PARTY_NOTICES.md"
    "${PROJECT_SOURCE_DIR}/third_party/cyclonedds/LICENSE" "${PROJECT_SOURCE_DIR}/third_party/cyclonedds/NOTICE.md"
  VERBATIM)
add_custom_target(bundled-idlc ALL DEPENDS "${BUNDLED_IDLC_JAR}")
install(FILES "${BUNDLED_IDLC_JAR}" DESTINATION share/java)
set(BUNDLED_IDLC_JAR "${BUNDLED_IDLC_JAR}" PARENT_SCOPE)
set(BUNDLED_IDLC_NAME "${BUNDLED_IDLC_NAME}" PARENT_SCOPE)
