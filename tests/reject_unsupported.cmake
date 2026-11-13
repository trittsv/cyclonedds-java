# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

foreach(input IN ITEMS "${INPUT}")
  file(REMOVE_RECURSE "${OUTPUT}")
  file(MAKE_DIRECTORY "${OUTPUT}")
  execute_process(COMMAND "${IDLC}" -l "${BACKEND}" -o "${OUTPUT}" "${input}"
    RESULT_VARIABLE result ERROR_VARIABLE error)
  if(result EQUAL 0 OR NOT error MATCHES "unsupported member type")
    message(FATAL_ERROR "Unsupported IDL was not diagnosed correctly: ${input}: ${result}: ${error}")
  endif()
  if(EXISTS "${OUTPUT}/Unsupported/Changeable.java")
    message(FATAL_ERROR "Backend left an invalid generated Java type")
  endif()
endforeach()
