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

/* SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "dds/dds.h"
#include "dds/cdr/dds_cdrstream.h"
#include "Types.h"

static bool wait_for_reader(dds_entity_t writer) {
  dds_time_t deadline = dds_time() + DDS_SECS(5);
  while (dds_time() < deadline) {
    dds_publication_matched_status_t status;
    if (dds_get_publication_matched_status(writer, &status) < 0) return false;
    if (status.current_count > 0) return true;
    dds_sleepfor(DDS_MSECS(2));
  }
  return false;
}

int main(int argc, char **argv) {
  if (argc != 4) return 1;
  int expected = atoi(argv[1]);
  const dds_topic_descriptor_t *types[] = {
    &Broader_Change_desc, &Broader_OptionalFields_desc, &Broader_Derived_desc,
    &Broader_DerivedA_desc, &Broader_DerivedM_desc, &Broader_Wide_desc,
    &Broader_Arrays_desc, &Broader_Node_desc, &Broader_BranchNames_desc,
    &Broader_MutableChoice_desc, &Broader_AppendableChoice_desc,
    &Broader_OctetChoice_desc, &Broader_ShortChoice_desc, &Broader_Enums_desc, &Broader_ExternalChoice_desc, &Broader_Left_desc, &Broader_RecursiveChoice_desc,
    &XcdrTest_OptionalBeforeKey_desc, &XcdrTest_Aligned_desc, &XcdrTest_Nested_desc, &XcdrTest_EnumKeys_desc, &XcdrTest_WrappedNew_desc, &XcdrTest_OptionalNew_desc, &XcdrTest_UnionNew_desc, &XcdrTest_TailNew_desc, &XcdrTest_RecursiveAppendable_desc, &XcdrTest_RecursiveMutable_desc, &XcdrTest_CollectionNew_desc};
  const char *names[] = {"Change", "OptionalFields", "Derived", "DerivedA", "DerivedM", "Wide", "Arrays", "Node", "BranchNames", "MutableChoice", "AppendableChoice", "OctetChoice", "ShortChoice", "Enums", "ExternalChoice", "Left", "RecursiveChoice",
    "OptionalBeforeKey", "Aligned", "Nested", "EnumKeys", "WrappedNew", "OptionalNew", "UnionNew", "TailNew", "RecursiveAppendable", "RecursiveMutable", "CollectionNew"};
  enum { n = sizeof(types) / sizeof(types[0]) };
  dds_entity_t participant = dds_create_participant(87, NULL, NULL), readers[n], writers[n];
  dds_qos_t *qos = dds_create_qos();
  dds_qset_reliability(qos, DDS_RELIABILITY_RELIABLE, DDS_SECS(2));
  dds_qset_history(qos, DDS_HISTORY_KEEP_ALL, 0);
  dds_data_representation_id_t representation = argc > 2 && atoi(argv[2]) == 1 ? DDS_DATA_REPRESENTATION_XCDR1 : DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  for (int i = 0; i < n; i++) {
    char token[100]; snprintf(token, sizeof(token), ",%s,", names[i]);
    if (!strstr(argv[3], token)) { readers[i] = writers[i] = 0; continue; }
    char name[100];
    snprintf(name, sizeof(name), "Broad%sToC", names[i]);
    dds_entity_t input = dds_create_topic(participant, types[i], name, NULL, NULL);
    snprintf(name, sizeof(name), "Broad%sFromC", names[i]);
    dds_entity_t output = dds_create_topic(participant, types[i], name, NULL, NULL);
    readers[i] = dds_create_reader(participant, input, qos, NULL);
    writers[i] = dds_create_writer(participant, output, qos, NULL);
    if (readers[i] < 0 || writers[i] < 0) { fprintf(stderr, "Failed to create %s\n", names[i]); return 2; }
  }
  dds_delete_qos(qos);
  int received = 0, ok = 1;
  dds_time_t deadline = dds_time() + DDS_SECS(25);
  while (received < expected && dds_time() < deadline && ok) {
    for (int i = 0; i < n; i++) {
      if (readers[i] == 0) continue;
      void *values[8] = {0}; dds_sample_info_t info[8];
      dds_return_t count = dds_take(readers[i], values, info, 8, 8);
      if (count < 0) { ok = 0; break; }
      for (int j = 0; j < count; j++) if (info[j].valid_data) {
        // Java observing this writer does not guarantee that discovery has
        // completed in the reverse direction. Volatile replies need a reader.
        if (!wait_for_reader(writers[i]) || dds_write(writers[i], values[j]) < 0) ok = 0;
        received++;
      }
      if (count > 0) dds_return_loan(readers[i], values, count);
    }
    dds_sleepfor(DDS_MSECS(2));
  }
  for (int i = 0; i < n; i++) if (writers[i] != 0 && dds_wait_for_acks(writers[i], DDS_SECS(2)) < 0) ok = 0;
  dds_delete(participant);
  if (!ok || received != expected) fprintf(stderr, "Broader interoperability: %d/%d received\n", received, expected);
  return ok && received == expected ? 0 : 1;
}
