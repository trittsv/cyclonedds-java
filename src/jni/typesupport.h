/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

#ifndef CYCLONEDDS_JAVA_TYPESUPPORT_H
#define CYCLONEDDS_JAVA_TYPESUPPORT_H
#include "dds/dds.h"
#include "dds/ddsi/ddsi_sertype.h"
struct java_sample {
  unsigned char *bytes;
  uint32_t size;
  enum ddsi_serdata_kind kind;
};
/* Copies language-neutral XTypes blobs; ownership then belongs to the DDS type. */
dds_entity_t java_create_topic(dds_entity_t participant, const char *topic_name,
    const char *type_name, bool has_key, uint32_t representations, const unsigned char *info, uint32_t info_size,
    const unsigned char *map, uint32_t map_size, const dds_qos_t *qos);
bool java_merge_key(const struct ddsi_sertype *type, const struct java_sample *key, struct java_sample *value);
#endif
