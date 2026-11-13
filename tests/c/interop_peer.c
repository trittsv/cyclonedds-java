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
#include <stdlib.h>
#include "dds/dds.h"
#include "HelloWorld.h"
#include "Types.h"

static int receive(dds_entity_t reader, int32_t id, const char *text) {
  dds_time_t deadline = dds_time() + DDS_SECS(10);
  while (dds_time() < deadline) {
    void *samples[16] = {0};
    dds_sample_info_t infos[16];
    dds_return_t count = dds_take(reader, samples, infos, 16, 16);
    if (count < 0) return 0;
    int found = 0;
    for (int i = 0; i < count; i++) {
      const HelloWorld_Message *message = samples[i];
      if (infos[i].valid_data && message->id == id && strcmp(message->text, text) == 0) found = 1;
    }
    if (count > 0) dds_return_loan(reader, samples, count);
    if (found) return 1;
    dds_sleepfor(DDS_MSECS(10));
  }
  return 0;
}

static int receive_composite(dds_entity_t reader) {
  dds_time_t deadline = dds_time() + DDS_SECS(10);
  dds_instance_handle_t first = 0, second = 0;
  int seen = 0;
  while (dds_time() < deadline && seen != 7) {
    void *samples[16] = {0};
    dds_sample_info_t infos[16];
    dds_return_t count = dds_take(reader, samples, infos, 16, 16);
    if (count < 0) return 0;
    for (int i = 0; i < count; i++) {
      if (!infos[i].valid_data) continue;
      const BindingTest_CompositeKey *value = samples[i];
      if (value->id != 1) { dds_return_loan(reader, samples, count); return 0; }
      if (strcmp(value->region, "region-with-key-larger-than-sixteen-bytes") == 0) {
        if (first && first != infos[i].instance_handle) { dds_return_loan(reader, samples, count); return 0; }
        first = infos[i].instance_handle;
        if (strcmp(value->text, "first") == 0) seen |= 1;
        else if (strcmp(value->text, "updated") == 0) seen |= 2;
      } else if (strcmp(value->region, "another") == 0 && strcmp(value->text, "other") == 0) {
        second = infos[i].instance_handle; seen |= 4;
      }
    }
    if (count > 0) dds_return_loan(reader, samples, count);
    if (seen != 7) dds_sleepfor(DDS_MSECS(10));
  }
  return seen == 7 && first != second;
}

int main(void) {
  dds_entity_t participant = dds_create_participant(72, NULL, NULL);
  if (participant < 0) return 1;
  dds_entity_t input = dds_create_topic(participant, &HelloWorld_Message_desc, "JavaToC", NULL, NULL);
  dds_entity_t output = dds_create_topic(participant, &HelloWorld_Message_desc, "CToJava", NULL, NULL);
  dds_entity_t key_input = dds_create_topic(participant, &BindingTest_CompositeKey_desc, "CompositeToC", NULL, NULL);
  dds_entity_t key_output = dds_create_topic(participant, &BindingTest_CompositeKey_desc, "CompositeFromC", NULL, NULL);
  dds_qos_t *qos = dds_create_qos();
  dds_qset_reliability(qos, DDS_RELIABILITY_RELIABLE, DDS_SECS(2));
  dds_qset_history(qos, DDS_HISTORY_KEEP_LAST, 32);
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  dds_entity_t reader = dds_create_reader(participant, input, qos, NULL);
  dds_entity_t writer = dds_create_writer(participant, output, qos, NULL);
  dds_entity_t key_reader = dds_create_reader(participant, key_input, qos, NULL);
  dds_entity_t key_writer = dds_create_writer(participant, key_output, qos, NULL);
  dds_delete_qos(qos);
  int ok = reader > 0 && writer > 0 && receive(reader, 42, "Java → C 🌍");
  HelloWorld_Message response = {.id = 42, .text = "C → Java ✓"};
  if (ok) ok = dds_write(writer, &response) == DDS_RETCODE_OK;
  char *large = malloc(100001);
  if (!large) ok = 0;
  else {
    memset(large, 'z', 100000); large[100000] = 0;
    if (ok) ok = receive(reader, 44, large);
    response.id = 44; response.text = large;
    if (ok) ok = dds_write(writer, &response) == DDS_RETCODE_OK;
    free(large);
  }
  if (ok) ok = receive_composite(key_reader);
  BindingTest_CompositeKey key_response = {.region = "region-with-key-larger-than-sixteen-bytes", .id = 1, .text = "C-keyhash"};
  if (ok) ok = dds_write(key_writer, &key_response) == DDS_RETCODE_OK;
  if (ok) ok = receive(reader, 45, "key-received");
  if (ok) ok = dds_dispose(key_writer, &key_response) == DDS_RETCODE_OK;
  if (ok) ok = receive(reader, 43, "ack");
  dds_delete(participant);
  if (!ok) fprintf(stderr, "C interoperability peer failed\n");
  return ok ? 0 : 1;
}
