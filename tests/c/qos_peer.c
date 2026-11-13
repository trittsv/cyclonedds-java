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
#include <string.h>
#include "dds/dds.h"
#include "HelloWorld.h"

static bool bytes_equal(const dds_qos_t *qos, int kind, unsigned char first, unsigned char last) {
  void *bytes = NULL; size_t size = 0;
  bool present = kind == 0 ? dds_qget_userdata(qos, &bytes, &size) : kind == 1
    ? dds_qget_groupdata(qos, &bytes, &size) : dds_qget_topicdata(qos, &bytes, &size);
  bool equal = present && size == 3 && ((unsigned char *) bytes)[0] == first
    && ((unsigned char *) bytes)[1] == 0 && ((unsigned char *) bytes)[2] == last;
  dds_free(bytes); return equal;
}
int main(void) {
  dds_entity_t p = dds_create_participant(91, NULL, NULL);
  dds_qos_t *qos = dds_create_qos();
  dds_qset_reliability(qos, DDS_RELIABILITY_RELIABLE, DDS_SECS(2));
  dds_qset_destination_order(qos, DDS_DESTINATIONORDER_BY_SOURCE_TIMESTAMP);
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  dds_entity_t input = dds_create_topic(p, &HelloWorld_Message_desc, "QosMetadata", NULL, NULL);
  dds_entity_t output = dds_create_topic(p, &HelloWorld_Message_desc, "QosMetadataReply", NULL, NULL);
  dds_entity_t rd = dds_create_reader(p, input, qos, NULL), wr = dds_create_writer(p, output, qos, NULL);
  dds_delete_qos(qos);
  if (rd < 0 || wr < 0) return 2;
  int phase = 0;
  dds_time_t end = dds_time() + DDS_SECS(15);
  while (phase < 2 && dds_time() < end) {
    dds_instance_handle_t handles[8];
    dds_return_t n = dds_get_matched_publications(rd, handles, 8);
    if (n > 0 && dds_get_matched_subscriptions(wr, NULL, 0) > 0) {
      dds_builtintopic_endpoint_t *endpoint = dds_get_matched_publication_data(rd, handles[0]);
      if (endpoint) {
        dds_destination_order_kind_t order;
        unsigned char user = phase == 0 ? 1 : 8, group = phase == 0 ? 2 : 9;
        bool ok = bytes_equal(endpoint->qos, 0, user, 255) && bytes_equal(endpoint->qos, 1, group, 254)
          && bytes_equal(endpoint->qos, 2, 3, 253)
          && dds_qget_destination_order(endpoint->qos, &order) && order == DDS_DESTINATIONORDER_BY_SOURCE_TIMESTAMP;
        dds_builtintopic_free_endpoint(endpoint);
        if (ok) {
          HelloWorld_Message reply = {.id = phase + 1, .text = phase == 0 ? "metadata verified" : "updates verified"};
          if (dds_write(wr, &reply) < 0) return 3;
          phase++;
        }
      }
    }
    dds_sleepfor(DDS_MSECS(5));
  }
  if (phase != 2) fprintf(stderr, "QoS discovery peer verified %d/2 metadata phases\n", phase);
  bool ok = phase == 2 && dds_wait_for_acks(wr, DDS_SECS(2)) >= 0;
  dds_delete(p); return ok ? 0 : 1;
}
