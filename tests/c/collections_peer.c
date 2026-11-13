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
#include <stdio.h>
#include <string.h>
#include "dds/dds.h"
#include "Types.h"

int main(void) {
  dds_entity_t participant = dds_create_participant(82, NULL, NULL);
  dds_entity_t input = dds_create_topic(participant, &BindingTest_Collections_desc, "CollectionsToC", NULL, NULL);
  dds_entity_t output = dds_create_topic(participant, &BindingTest_Collections_desc, "CollectionsFromC", NULL, NULL);
  dds_qos_t *qos = dds_create_qos();
  dds_qset_reliability(qos, DDS_RELIABILITY_RELIABLE, DDS_SECS(2));
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  dds_entity_t reader = dds_create_reader(participant, input, qos, NULL);
  dds_entity_t writer = dds_create_writer(participant, output, qos, NULL);
  dds_delete_qos(qos);
  int ok = 0;
  dds_time_t deadline = dds_time() + DDS_SECS(10);
  while (dds_time() < deadline) {
    void *values[1] = {NULL}; dds_sample_info_t info[1];
    dds_return_t count = dds_take(reader, values, info, 1, 1);
    if (count < 0) break;
    if (count == 1) {
      BindingTest_Collections *value = values[0];
      if (info[0].valid_data && value->id == 7 && value->grid[1][2] == 6 && value->points[0].x == 1.25 &&
          value->colors[1] == BindingTest_BLUE && strcmp(value->names[1], "two") == 0 &&
          value->path._length == 1 && value->path._buffer[0].y == 6.0 &&
          value->labels._length == 1 && strcmp(value->labels._buffer[0], "hello") == 0 &&
          value->palette._length == 1 && value->palette._buffer[0] == BindingTest_GREEN &&
          value->nested._length == 1 && value->nested._buffer[0]._length == 2 && value->nested._buffer[0]._buffer[1] == 9) {
        strcpy(value->labels._buffer[0], "from C");
        ok = dds_write(writer, value) == DDS_RETCODE_OK;
        if (ok) ok = dds_wait_for_acks(writer, DDS_SECS(2)) == DDS_RETCODE_OK;
      }
      dds_return_loan(reader, values, count); break;
    }
    dds_sleepfor(DDS_MSECS(5));
  }
  dds_delete(participant);
  if (!ok) fprintf(stderr, "Collection interoperability failed\n");
  return ok ? 0 : 1;
}
