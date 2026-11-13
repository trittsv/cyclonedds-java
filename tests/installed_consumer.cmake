# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

set(prefix "${BUILD_DIR}/consumer-install")
# Validate a fresh installation, without files left by earlier binding versions.
file(REMOVE_RECURSE "${prefix}")
function(checked)
  execute_process(COMMAND ${ARGV} RESULT_VARIABLE result OUTPUT_VARIABLE output ERROR_VARIABLE errors)
  if(NOT result EQUAL 0)
    message(FATAL_ERROR "Consumer command failed (${result}): ${ARGV}\n${output}\n${errors}")
  endif()
endfunction()
checked("${CMAKE_COMMAND}" --install "${BUILD_DIR}" --prefix "${prefix}")
checked("${CMAKE_COMMAND}" -S "${SOURCE_DIR}/tests/consumer" -B "${BUILD_DIR}/consumer-build"
  "-DCMAKE_PREFIX_PATH=${prefix}" "-DCycloneDDS_DIR=${CORE_DIR}" "-DJava_JAVA_EXECUTABLE=${JAVA}")
checked("${CMAKE_COMMAND}" --build "${BUILD_DIR}/consumer-build" --parallel 2)
checked("${CMAKE_CTEST_COMMAND}" --test-dir "${BUILD_DIR}/consumer-build" --output-on-failure)
message(STATUS "Installed consumer configured, generated Java types, built and exchanged DDS data")
