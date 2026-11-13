# Copyright(c) 2026 Sven Trittler. SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
file(REMOVE_RECURSE "${STAGING}")
file(MAKE_DIRECTORY "${STAGING}")
execute_process(COMMAND "${JAR}" --extract --file "${LAUNCHER_JAR}"
  WORKING_DIRECTORY "${STAGING}" COMMAND_ERROR_IS_FATAL ANY)
set(payload "${STAGING}/META-INF/cyclonedds-idlc/${PLATFORM}")
file(MAKE_DIRECTORY "${payload}/bin" "${payload}/lib" "${STAGING}/META-INF/cyclonedds")
get_filename_component(executable "${IDLC}" NAME)
get_filename_component(backend "${BACKEND}" NAME)
file(COPY_FILE "${IDLC}" "${payload}/bin/${executable}")
file(COPY_FILE "${BACKEND}" "${payload}/lib/${backend}")
# Capture the actual compiler dependency closure, including versioned loader names.
# OS runtime libraries remain supplied by the host, as for the runtime JAR.
set(dependency_search)
if(WIN32)
  get_filename_component(idlc_directory "${IDLC}" DIRECTORY)
  list(APPEND dependency_search DIRECTORIES "${idlc_directory}")
endif()
file(GET_RUNTIME_DEPENDENCIES
  EXECUTABLES "${IDLC}" LIBRARIES "${BACKEND}"
  ${dependency_search}
  RESOLVED_DEPENDENCIES_VAR dependencies UNRESOLVED_DEPENDENCIES_VAR missing
  PRE_EXCLUDE_REGEXES "^api-ms-" "^ext-ms-"
  POST_EXCLUDE_REGEXES "^/System/Library/" "^/usr/lib/.*\\.dylib$"
    "/(ld-linux[^/]*|lib(c|m|pthread|rt|dl|gcc_s|stdc\\+\\+|resolv|util|anl))[-.][^/]*$"
    "[Ww][Ii][Nn][Dd][Oo][Ww][Ss][/\\\\][Ss][Yy][Ss][Tt][Ee][Mm]32[/\\\\]")
if(missing)
  message(FATAL_ERROR "Unresolved IDL compiler dependencies: ${missing}")
endif()
foreach(dependency IN LISTS dependencies)
  get_filename_component(name "${dependency}" NAME)
  if(EXISTS "${payload}/lib/${name}")
    file(SHA256 "${payload}/lib/${name}" existing_hash)
    file(SHA256 "${dependency}" dependency_hash)
    if(NOT existing_hash STREQUAL dependency_hash)
      message(FATAL_ERROR "Conflicting IDL compiler library: ${name}")
    endif()
  else()
    # COPY_FILE dereferences symlinks so all loader names are actual JAR entries.
    file(COPY_FILE "${dependency}" "${payload}/lib/${name}")
  endif()
endforeach()
file(WRITE "${payload}/compiler.properties"
  "platform=${PLATFORM}\nexecutable=bin/${executable}\nbackend=lib/${backend}\n")
file(GLOB files RELATIVE "${payload}" "${payload}/bin/*" "${payload}/lib/*")
list(SORT files)
set(manifest "")
foreach(file IN LISTS files)
  file(SHA256 "${payload}/${file}" digest)
  string(APPEND manifest "${digest} ${file}\n")
endforeach()
file(WRITE "${payload}/files.sha256" "${manifest}")
file(WRITE "${STAGING}/META-INF/cyclonedds-idlc/platforms.list" "${PLATFORM}\n")
file(WRITE "${STAGING}/META-INF/cyclonedds/bundle.properties" "version=${VERSION}\nkind=compiler\nplatform=${PLATFORM}\n")
file(COPY "${SOURCE_DIR}/LICENSE" "${SOURCE_DIR}/THIRD_PARTY_NOTICES.md" DESTINATION "${STAGING}/META-INF")
file(COPY "${SOURCE_DIR}/third_party/cyclonedds/LICENSE" "${SOURCE_DIR}/third_party/cyclonedds/NOTICE.md"
  DESTINATION "${STAGING}/META-INF/cyclonedds")
file(COPY "${SOURCE_DIR}/third_party/idlc/LICENSE-mcpp" DESTINATION "${STAGING}/META-INF/cyclonedds")
execute_process(COMMAND "${JAR}" --create --file "${OUTPUT}" --main-class org.eclipse.cyclonedds.idlc.Idlc
  -C "${STAGING}" . COMMAND_ERROR_IS_FATAL ANY)
