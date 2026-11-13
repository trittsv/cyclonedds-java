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
#include "dds/dds.h"
#include "Types.h"

int main(void) {
  const dds_topic_descriptor_t *types[] = {&BindingTest_Choice_desc, &BindingTest_Evolving_desc, &BindingTest_Envelope_desc};
  const char *inputs[] = {"ChoiceToC", "EvolvingToC", "EnvelopeToC"};
  const char *outputs[] = {"ChoiceFromC", "EvolvingFromC", "EnvelopeFromC"};
  dds_entity_t participant = dds_create_participant(83, NULL, NULL), readers[3], writers[3];
  dds_qos_t *qos = dds_create_qos();
  dds_qset_reliability(qos, DDS_RELIABILITY_RELIABLE, DDS_SECS(2));
  dds_qset_history(qos, DDS_HISTORY_KEEP_ALL, 0);
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  for (int i = 0; i < 3; i++) {
    dds_entity_t input = dds_create_topic(participant, types[i], inputs[i], NULL, NULL);
    dds_entity_t output = dds_create_topic(participant, types[i], outputs[i], NULL, NULL);
    readers[i] = dds_create_reader(participant, input, qos, NULL);
    writers[i] = dds_create_writer(participant, output, qos, NULL);
    if (readers[i] < 0 || writers[i] < 0) return 2;
  }
  dds_delete_qos(qos);
  int received = 0, ok = 1;
  dds_time_t deadline = dds_time() + DDS_SECS(15);
  while (received < 5 && dds_time() < deadline && ok) {
    for (int i = 0; i < 3; i++) {
      void *values[8] = {0}; dds_sample_info_t info[8];
      dds_return_t count = dds_take(readers[i], values, info, 8, 8);
      if (count < 0) { ok = 0; break; }
      for (int j = 0; j < count; j++) if (info[j].valid_data) {
        dds_return_t result = dds_write(writers[i], values[j]);
        if (result < 0) ok = 0;
        received++;
      }
      if (count > 0) dds_return_loan(readers[i], values, count);
    }
    dds_sleepfor(DDS_MSECS(5));
  }
  for (int i = 0; i < 3; i++) if (dds_wait_for_acks(writers[i], DDS_SECS(2)) < 0) ok = 0;
  dds_delete(participant);
  if (!ok || received != 5) fprintf(stderr, "Structured interoperability failed: %d received\n", received);
  return ok && received == 5 ? 0 : 1;
}
