# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

file(REMOVE_RECURSE "${STAGING}")
file(MAKE_DIRECTORY "${STAGING}")
foreach(input IN ITEMS "${OMG_JAR}" "${IDL_JAR}" "${RUNTIME_JAR}")
  execute_process(COMMAND "${JAR}" --extract --file "${input}" WORKING_DIRECTORY "${STAGING}" RESULT_VARIABLE status)
  if(NOT status EQUAL 0)
    message(FATAL_ERROR "Cannot unpack runtime component ${input}")
  endif()
endforeach()
file(REMOVE "${STAGING}/META-INF/MANIFEST.MF")
file(MAKE_DIRECTORY "${STAGING}/META-INF/omg-dds" "${STAGING}/META-INF/cyclonedds")
file(COPY "${SOURCE_DIR}/third_party/omg-dds/LICENSE" "${SOURCE_DIR}/third_party/omg-dds/NOTICE"
  DESTINATION "${STAGING}/META-INF/omg-dds")
file(COPY "${SOURCE_DIR}/third_party/cyclonedds/LICENSE" "${SOURCE_DIR}/third_party/cyclonedds/NOTICE.md"
  DESTINATION "${STAGING}/META-INF/cyclonedds")
set(native_dir "${STAGING}/org/eclipse/cyclonedds/native/${PLATFORM}")
file(MAKE_DIRECTORY "${native_dir}")
set(manifest "")
foreach(library IN ITEMS "${CORE_LIBRARY}" "${JNI_LIBRARY}")
  if(NOT library)
    continue()
  endif()
  get_filename_component(name "${library}" NAME)
  file(COPY_FILE "${library}" "${native_dir}/${name}")
  file(SHA256 "${native_dir}/${name}" digest)
  string(APPEND manifest "${digest} ${name}\n")
endforeach()
file(WRITE "${native_dir}/libraries.list" "${manifest}")
file(WRITE "${STAGING}/org/eclipse/cyclonedds/native/platforms.list" "${PLATFORM}\n")
file(WRITE "${STAGING}/META-INF/cyclonedds/bundle.properties" "version=${VERSION}\nkind=runtime\nplatform=${PLATFORM}\n")
execute_process(COMMAND "${JAR}" --create --file "${OUTPUT}" -C "${STAGING}" . RESULT_VARIABLE status)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "Cannot package standalone runtime JAR")
endif()
