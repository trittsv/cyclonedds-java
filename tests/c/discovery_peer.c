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
#include "HelloWorld.h"

int main(void) {
  dds_qos_t *pq = dds_create_qos(), *gq = dds_create_qos(), *tq = dds_create_qos(), *wq = dds_create_qos(), *rq = dds_create_qos();
  const unsigned char participant_data[] = {6, 0, 255}, group_data[] = {4, 0, 254}, topic_data[] = {5, 0, 253};
  const unsigned char writer_data[] = {2, 0, 252}, reader_data[] = {3, 0, 251};
  dds_qset_userdata(pq, participant_data, sizeof(participant_data));
  dds_qset_groupdata(gq, group_data, sizeof(group_data));
  dds_qset_topicdata(tq, topic_data, sizeof(topic_data));
  dds_qset_userdata(wq, writer_data, sizeof(writer_data));
  dds_qset_userdata(rq, reader_data, sizeof(reader_data));
  dds_qset_reliability(wq, DDS_RELIABILITY_RELIABLE, DDS_SECS(1));
  dds_qset_reliability(rq, DDS_RELIABILITY_RELIABLE, DDS_SECS(1));
  dds_qset_deadline(wq, DDS_SECS(20)); dds_qset_deadline(rq, DDS_SECS(20));
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(wq, 1, &representation); dds_qset_data_representation(rq, 1, &representation);
  dds_entity_t participant = dds_create_participant(99, pq, NULL);
  dds_entity_t publisher = dds_create_publisher(participant, gq, NULL), subscriber = dds_create_subscriber(participant, gq, NULL);
  dds_entity_t topic = dds_create_topic(participant, &HelloWorld_Message_desc, "DiscoveryPeer", tq, NULL);
  dds_entity_t writer = dds_create_writer(publisher, topic, wq, NULL), reader = dds_create_reader(subscriber, topic, rq, NULL);
  dds_delete_qos(pq); dds_delete_qos(gq); dds_delete_qos(tq); dds_delete_qos(wq); dds_delete_qos(rq);
  if (writer < 0 || reader < 0) { fprintf(stderr, "Cannot create discovery peer endpoints\n"); return 2; }
  bool done = false; dds_time_t deadline = dds_time() + DDS_SECS(25);
  while (!done && dds_time() < deadline) {
    void *samples[8] = {0}; dds_sample_info_t info[8];
    int count = dds_take(reader, samples, info, 8, 8);
    if (count < 0) break;
    for (int i = 0; i < count; i++) if (info[i].valid_data && strcmp(((HelloWorld_Message *)samples[i])->text, "done") == 0) done = true;
    if (count > 0) dds_return_loan(reader, samples, count);
    dds_sleepfor(DDS_MSECS(5));
  }
  dds_delete(participant);
  return done ? 0 : 1;
}
