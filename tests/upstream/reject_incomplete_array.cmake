# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

file(MAKE_DIRECTORY "${OUTPUT}")
execute_process(COMMAND "${IDLC}" -l "${BACKEND}" -o "${OUTPUT}" "${INPUT}"
  RESULT_VARIABLE result ERROR_VARIABLE error)
if(result EQUAL 0 OR NOT error MATCHES "Array has incomplete element type")
  message(FATAL_ERROR "Expected the core parser's incomplete-array diagnostic: ${result}: ${error}")
endif()
file(GLOB_RECURSE generated "${OUTPUT}/*.java")
if(generated)
  message(FATAL_ERROR "Rejected input left generated Java files: ${generated}")
endif()
