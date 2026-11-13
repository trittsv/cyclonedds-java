# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

add_library(idljava SHARED src/generator.c)
target_link_libraries(idljava PRIVATE CycloneDDS::idl)
target_compile_features(idljava PRIVATE c_std_11)
set_target_properties(idljava PROPERTIES OUTPUT_NAME cycloneddsidljava WINDOWS_EXPORT_ALL_SYMBOLS ON
  INSTALL_RPATH_USE_LINK_PATH TRUE)
add_library(CycloneDDSJava::idljava ALIAS idljava)
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(idljava PRIVATE -Wall -Wextra -Werror)
endif()
install(TARGETS idljava EXPORT CycloneDDSJavaTargets LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
