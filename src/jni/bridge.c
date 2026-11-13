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

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <limits.h>
#include <string.h>
#include <stdatomic.h>
#include "dds/dds.h"
#include "dds/ddsi/ddsi_serdata.h"
#include "dds/ddsrt/heap.h"

#include "typesupport.h"

#define JNI_METHOD(name) Java_org_eclipse_cyclonedds_internal_Native_##name
#define UNUSED_CLASS (void) cls

static void fail(JNIEnv *env, const char *operation, dds_return_t code) {
  if ((*env)->ExceptionCheck(env)) return;
  jclass type = (*env)->FindClass(env, "org/eclipse/cyclonedds/core/DdsException");
  if (!type) return;
  jmethodID constructor = (*env)->GetMethodID(env, type, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V");
  if (!constructor) return;
  jstring op = (*env)->NewStringUTF(env, operation);
  if (!op) return;
  jstring reason = (*env)->NewStringUTF(env, dds_strretcode(code));
  if (!reason) return;
  jobject exception = (*env)->NewObject(env, type, constructor, op, (jint) code, reason);
  if (exception) (*env)->Throw(env, exception);
}

static dds_qos_t *configured_values(JNIEnv *env, jlongArray configuration) {
  dds_qos_t *qos = dds_create_qos();
  if (!qos) { fail(env, "Allocate QoS", DDS_RETCODE_OUT_OF_RESOURCES); return NULL; }
  if (!configuration) return qos;
  if ((*env)->GetArrayLength(env, configuration) != 33) {
    dds_delete_qos(qos); fail(env, "QoS configuration", DDS_RETCODE_BAD_PARAMETER); return NULL;
  }
  jlong v[33];
  (*env)->GetLongArrayRegion(env, configuration, 0, 33, v);
  if ((*env)->ExceptionCheck(env)) { dds_delete_qos(qos); return NULL; }
  if (v[0] >= 0) {
    dds_qset_reliability(qos, (dds_reliability_kind_t) v[0], v[1]);
    dds_qset_history(qos, v[2] == 0 ? DDS_HISTORY_KEEP_ALL : DDS_HISTORY_KEEP_LAST, (int32_t) v[2]);
    dds_qset_durability(qos, (dds_durability_kind_t) v[3]);
    dds_qset_resource_limits(qos, (int32_t) v[4], (int32_t) v[5], (int32_t) v[6]);
    dds_qset_deadline(qos, v[7]);
    if (v[8] >= 0) dds_qset_lifespan(qos, v[8]);
    dds_qset_liveliness(qos, (dds_liveliness_kind_t) v[9], v[10]);
    dds_qset_ownership(qos, (dds_ownership_kind_t) v[11]);
    if (v[15]) dds_qset_ownership_strength(qos, (int32_t) v[12]);
    if (v[13] >= 0) dds_qset_time_based_filter(qos, v[13]);
    if (v[14] >= 0) dds_qset_writer_data_lifecycle(qos, v[14] != 0);
    if (v[16] >= 0) dds_qset_durability_service(qos, v[16], (dds_history_kind_t) v[17], (int32_t) v[18],
        (int32_t) v[19], (int32_t) v[20], (int32_t) v[21]);
  }
  if (v[22] >= 0) dds_qset_destination_order(qos, (dds_destination_order_kind_t) v[22]);
  if (v[23] >= 0) dds_qset_latency_budget(qos, v[23]);
  if (v[32]) dds_qset_transport_priority(qos, (int32_t) v[24]);
  if (v[25] >= 0) dds_qset_reader_data_lifecycle(qos, v[25], v[26]);
  if (v[27] >= 0) dds_qset_type_consistency(qos, (dds_type_consistency_kind_t) v[27], true, true, false, false, true);
  if (v[28] >= 0) {
    dds_data_representation_id_t representations[2]; uint32_t count = 0;
    if (v[28] & 1) representations[count++] = DDS_DATA_REPRESENTATION_XCDR1;
    if (v[28] & 4) representations[count++] = DDS_DATA_REPRESENTATION_XCDR2;
    dds_qset_data_representation(qos, count, representations);
  }
  if (v[29] >= 0) dds_qset_presentation(qos, (dds_presentation_access_scope_kind_t) v[29], v[30] != 0, v[31] != 0);
  return qos;
}
static bool configure_partitions(JNIEnv *env, dds_qos_t *qos, jobjectArray partitions) {
  if (!partitions) return true;
  jsize count = (*env)->GetArrayLength(env, partitions);
  char **names = calloc((size_t) count + 1, sizeof(*names));
  if (!names) { fail(env, "Partition allocation", DDS_RETCODE_OUT_OF_RESOURCES); return false; }
  bool ok = true;
  for (jsize i = 0; i < count; i++) {
    jstring name = (*env)->GetObjectArrayElement(env, partitions, i);
    if (!name) { ok = false; break; }
    const char *text = (*env)->GetStringUTFChars(env, name, NULL);
    if (text) {
      size_t size = strlen(text) + 1;
      names[i] = malloc(size);
      if (names[i]) memcpy(names[i], text, size); else ok = false;
      (*env)->ReleaseStringUTFChars(env, name, text);
    } else ok = false;
    (*env)->DeleteLocalRef(env, name);
    if (!ok) break;
  }
  if (ok) dds_qset_partition(qos, (uint32_t) count, (const char **) names);
  for (jsize i = 0; i < count; i++) free(names[i]);
  free(names);
  if (!ok && !(*env)->ExceptionCheck(env)) fail(env, "Partition names", DDS_RETCODE_OUT_OF_RESOURCES);
  return ok;
}

/* One configuration object carries numeric policies, immutable byte data and partitions.
   dds_qset_* copies all supplied buffers before JNI releases them. */
static dds_qos_t *configured_qos(JNIEnv *env, jobject configuration) {
  if (!configuration) return configured_values(env, NULL);
  jclass cls = (*env)->GetObjectClass(env, configuration);
  jfieldID values_id = (*env)->GetFieldID(env, cls, "values", "[J");
  jfieldID data_id = (*env)->GetFieldID(env, cls, "data", "[[B");
  jfieldID partitions_id = (*env)->GetFieldID(env, cls, "partitions", "[Ljava/lang/String;");
  if (!values_id || !data_id || !partitions_id) return NULL;
  jlongArray values = (*env)->GetObjectField(env, configuration, values_id);
  jobjectArray data = (*env)->GetObjectField(env, configuration, data_id);
  jobjectArray partitions = (*env)->GetObjectField(env, configuration, partitions_id);
  dds_qos_t *qos = configured_values(env, values);
  if (!qos) return NULL;
  bool ok = configure_partitions(env, qos, partitions);
  if (ok && data && (*env)->GetArrayLength(env, data) != 3) { fail(env, "QoS byte data", DDS_RETCODE_BAD_PARAMETER); ok = false; }
  for (jsize i = 0; ok && data && i < 3; i++) {
    jbyteArray value = (*env)->GetObjectArrayElement(env, data, i);
    if (!value) continue;
    jsize size = (*env)->GetArrayLength(env, value);
    jbyte *bytes = size ? (*env)->GetByteArrayElements(env, value, NULL) : NULL;
    if (size && !bytes) ok = false;
    else {
      if (i == 0) dds_qset_userdata(qos, bytes, (size_t) size);
      else if (i == 1) dds_qset_groupdata(qos, bytes, (size_t) size);
      else dds_qset_topicdata(qos, bytes, (size_t) size);
    }
    if (bytes) (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    (*env)->DeleteLocalRef(env, value);
  }
  (*env)->DeleteLocalRef(env, values); (*env)->DeleteLocalRef(env, data);
  (*env)->DeleteLocalRef(env, partitions); (*env)->DeleteLocalRef(env, cls);
  if (!ok) { dds_delete_qos(qos); return NULL; }
  return qos;
}
JNIEXPORT jint JNICALL JNI_METHOD(participantConfigured)(JNIEnv *env, jclass cls, jint domain, jobject configuration) {
  UNUSED_CLASS;
  dds_qos_t *qos = configured_qos(env, configuration);
  if (!qos) return 0;
  dds_entity_t entity = dds_create_participant((dds_domainid_t) domain, qos, NULL);
  dds_delete_qos(qos);
  if (entity < 0) fail(env, "dds_create_participant", entity);
  return entity;
}

JNIEXPORT jint JNICALL JNI_METHOD(participant)(JNIEnv *env, jclass cls, jint domain) {
  UNUSED_CLASS;
  dds_entity_t entity = dds_create_participant((dds_domainid_t) domain, NULL, NULL);
  if (entity < 0) fail(env, "dds_create_participant", entity);
  return entity;
}

JNIEXPORT jint JNICALL JNI_METHOD(topicConfigured)(JNIEnv *env, jclass cls, jint participant,
    jstring name, jstring type_name, jboolean has_key, jint representations, jbyteArray info, jbyteArray map, jobject configuration) {
  UNUSED_CLASS;
  const char *native_name = (*env)->GetStringUTFChars(env, name, NULL);
  if (!native_name) return 0;
  const char *native_type = (*env)->GetStringUTFChars(env, type_name, NULL);
  if (!native_type) { (*env)->ReleaseStringUTFChars(env, name, native_name); return 0; }
  jsize info_size = (*env)->GetArrayLength(env, info);
  jsize map_size = (*env)->GetArrayLength(env, map);
  jbyte *info_bytes = (*env)->GetByteArrayElements(env, info, NULL);
  jbyte *map_bytes = info_bytes ? (*env)->GetByteArrayElements(env, map, NULL) : NULL;
  dds_entity_t entity = 0;
  if (map_bytes) {
    dds_qos_t *qos = configured_qos(env, configuration);
    if (qos) {
      entity = java_create_topic(participant, native_name, native_type, has_key, (uint32_t)representations,
          (unsigned char *) info_bytes, (uint32_t) info_size, (unsigned char *) map_bytes, (uint32_t) map_size, qos);
      dds_delete_qos(qos);
    }
    (*env)->ReleaseByteArrayElements(env, map, map_bytes, JNI_ABORT);
  }
  if (info_bytes) (*env)->ReleaseByteArrayElements(env, info, info_bytes, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, type_name, native_type);
  (*env)->ReleaseStringUTFChars(env, name, native_name);
  if (entity < 0) fail(env, "Register Java type support", entity);
  return entity;
}

JNIEXPORT jint JNICALL JNI_METHOD(endpoint)(JNIEnv *env, jclass cls, jint participant,
    jint topic, jboolean writer, jboolean reliable, jint depth, jlong blocking) {
  UNUSED_CLASS;
  dds_qos_t *qos = dds_create_qos();
  if (!qos) { fail(env, "dds_create_qos", DDS_RETCODE_OUT_OF_RESOURCES); return 0; }
  dds_qset_reliability(qos, reliable ? DDS_RELIABILITY_RELIABLE : DDS_RELIABILITY_BEST_EFFORT, blocking);
  dds_qset_history(qos, depth == 0 ? DDS_HISTORY_KEEP_ALL : DDS_HISTORY_KEEP_LAST, depth);
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  dds_entity_t entity = writer ? dds_create_writer(participant, topic, qos, NULL)
                               : dds_create_reader(participant, topic, qos, NULL);
  dds_delete_qos(qos);
  if (entity < 0) fail(env, writer ? "dds_create_writer" : "dds_create_reader", entity);
  return entity;
}

JNIEXPORT void JNICALL JNI_METHOD(delete)(JNIEnv *env, jclass cls, jint entity) {
  UNUSED_CLASS;
  dds_return_t result = dds_delete(entity);
  if (result < 0) fail(env, "dds_delete", result);
}

static void write_cdr(JNIEnv *env, jint writer, jbyteArray bytes, jlong timestamp) {
  const struct ddsi_sertype *type;
  dds_return_t rc = dds_get_entity_sertype(writer, &type);
  if (rc < 0) { fail(env, "dds_get_entity_sertype", rc); return; }
  jsize size = (*env)->GetArrayLength(env, bytes);
  if (size < 4) { fail(env, "CDR encapsulation", DDS_RETCODE_BAD_PARAMETER); return; }
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (!data) return;
  ddsrt_iovec_t iov = {.iov_base = data, .iov_len = (size_t) size};
  struct ddsi_serdata *sample = ddsi_serdata_from_ser_iov(type, SDK_DATA, 1, &iov, (size_t) size);
  (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  if (!sample) { fail(env, "Validate serialized sample", DDS_RETCODE_BAD_PARAMETER); return; }
  /* writecdr consumes one reference once the writer is locked. Java serializes deletion
     with writes and never installs topic filters, so early failure paths cannot occur. */
  if (timestamp < 0) rc = dds_writecdr(writer, sample);
  else { sample->timestamp.v = timestamp; sample->statusinfo = 0; rc = dds_forwardcdr(writer, sample); }
  if (rc < 0) fail(env, "dds_writecdr", rc);
}

JNIEXPORT void JNICALL JNI_METHOD(write)(JNIEnv *env, jclass cls, jint writer, jbyteArray bytes) {
  UNUSED_CLASS; write_cdr(env, writer, bytes, -1);
}
JNIEXPORT void JNICALL JNI_METHOD(writeAt)(JNIEnv *env, jclass cls, jint writer, jbyteArray bytes, jlong timestamp) {
  UNUSED_CLASS; write_cdr(env, writer, bytes, timestamp);
}

struct collected_samples { struct ddsi_serdata **samples; dds_sample_info_t *infos; uint32_t count; const struct ddsi_sertype **types; };
static dds_return_t collect_sample(void *arg, const dds_sample_info_t *info, const struct ddsi_sertype *type, struct ddsi_serdata *data) {
  (void) type;
  struct collected_samples *batch = arg;
  if (batch->types) batch->types[batch->count] = type;
  batch->samples[batch->count] = ddsi_serdata_ref(data);
  batch->infos[batch->count++] = *info;
  return DDS_RETCODE_OK;
}
static jobjectArray read_samples(JNIEnv *env, jint reader, jint max_samples, bool take, uint32_t mask, dds_instance_handle_t instance, bool next) {
  if (max_samples < 1 || max_samples > 65536) {
    fail(env, "take limit", DDS_RETCODE_BAD_PARAMETER); return NULL;
  }
  jclass type = (*env)->FindClass(env, "org/eclipse/cyclonedds/internal/WireSample");
  if (!type) return NULL;
  jmethodID constructor = (*env)->GetMethodID(env, type, "<init>", "([BZJJIIIJIIIII)V");
  if (!constructor) return NULL;
  struct ddsi_serdata **samples = calloc((size_t) max_samples, sizeof(*samples));
  dds_sample_info_t *infos = calloc((size_t) max_samples, sizeof(*infos));
  if (!samples || !infos) {
    free(samples); free(infos);
    fail(env, "Allocate sample batch", DDS_RETCODE_OUT_OF_RESOURCES); return NULL;
  }
  dds_return_t count;
  struct collected_samples batch = {samples, infos, 0, NULL};
  if (next)
    count = take ? dds_take_next_instance_with_collector(reader, (uint32_t) max_samples, instance, mask, collect_sample, &batch)
                 : dds_read_next_instance_with_collector(reader, (uint32_t) max_samples, instance, mask, collect_sample, &batch);
  else
    count = take ? dds_take_with_collector(reader, (uint32_t) max_samples, instance, mask, collect_sample, &batch)
                 : dds_read_with_collector(reader, (uint32_t) max_samples, instance, mask, collect_sample, &batch);
  if (count < 0) {
    free(samples); free(infos); fail(env, take ? "dds_takecdr" : "dds_readcdr", count); return NULL;
  }
  jobjectArray result = (*env)->NewObjectArray(env, count, type, NULL);
  for (int i = 0; result && i < count && !(*env)->ExceptionCheck(env); i++) {
    jbyteArray bytes = NULL;
    if (infos[i].valid_data) {
      uint32_t size = ddsi_serdata_size(samples[i]);
      if (size > INT_MAX) { fail(env, "Sample exceeds Java array limit", DDS_RETCODE_OUT_OF_RESOURCES); break; }
      bytes = (*env)->NewByteArray(env, (jsize) size);
      if (!bytes) break;
      jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
      if (!data) { (*env)->DeleteLocalRef(env, bytes); break; }
      ddsi_serdata_to_ser(samples[i], 0, size, data);
      (*env)->ReleaseByteArrayElements(env, bytes, data, 0);
    }
    jobject sample = (*env)->NewObject(env, type, constructor, bytes, (jboolean) infos[i].valid_data,
        (jlong) infos[i].source_timestamp, (jlong) infos[i].instance_handle, (jint) infos[i].instance_state,
        (jint) infos[i].sample_state, (jint) infos[i].view_state, (jlong) infos[i].publication_handle,
        (jint) infos[i].disposed_generation_count, (jint) infos[i].no_writers_generation_count,
        (jint) infos[i].sample_rank, (jint) infos[i].generation_rank, (jint) infos[i].absolute_generation_rank);
    if (!(*env)->ExceptionCheck(env) && sample) (*env)->SetObjectArrayElement(env, result, i, sample);
    if (bytes) (*env)->DeleteLocalRef(env, bytes);
    if (sample) (*env)->DeleteLocalRef(env, sample);
  }
  for (int i = 0; i < count; i++) if (samples[i]) ddsi_serdata_unref(samples[i]);
  free(samples); free(infos);
  return result;
}

JNIEXPORT jobjectArray JNICALL JNI_METHOD(take)(JNIEnv *env, jclass cls, jint reader, jint max_samples) {
  UNUSED_CLASS; return read_samples(env, reader, max_samples, true, DDS_ANY_STATE, DDS_HANDLE_NIL, false);
}
JNIEXPORT jobjectArray JNICALL JNI_METHOD(read)(JNIEnv *env, jclass cls, jint reader, jint max_samples) {
  UNUSED_CLASS; return read_samples(env, reader, max_samples, false, DDS_ANY_STATE, DDS_HANDLE_NIL, false);
}

JNIEXPORT jobjectArray JNICALL JNI_METHOD(select)(JNIEnv *env, jclass cls, jint reader, jint max_samples, jboolean take, jint mask, jlong instance) {
  UNUSED_CLASS; return read_samples(env, reader, max_samples, take, (uint32_t) mask, (dds_instance_handle_t) instance, false);
}

JNIEXPORT jint JNICALL JNI_METHOD(container)(JNIEnv *env, jclass cls, jint participant, jboolean publisher) {
  UNUSED_CLASS;
  dds_entity_t result = publisher ? dds_create_publisher(participant, NULL, NULL) : dds_create_subscriber(participant, NULL, NULL);
  if (result < 0) fail(env, "Create publisher/subscriber", result);
  return result;
}
JNIEXPORT jlong JNICALL JNI_METHOD(instanceHandle)(JNIEnv *env, jclass cls, jint entity) {
  UNUSED_CLASS;
  dds_instance_handle_t handle = 0;
  dds_return_t rc = dds_get_instance_handle(entity, &handle);
  if (rc < 0) fail(env, "dds_get_instance_handle", rc);
  return (jlong) handle;
}
JNIEXPORT void JNICALL JNI_METHOD(waitForAcks)(JNIEnv *env, jclass cls, jint entity, jlong timeout) {
  UNUSED_CLASS;
  dds_return_t rc = dds_wait_for_acks(entity, timeout);
  if (rc < 0) fail(env, "dds_wait_for_acks", rc);
}
JNIEXPORT jlong JNICALL JNI_METHOD(instanceOperation)(JNIEnv *env, jclass cls, jint writer, jbyteArray bytes, jboolean registration) {
  UNUSED_CLASS;
  jsize size = (*env)->GetArrayLength(env, bytes);
  jbyte *buffer = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (!buffer) return 0;
  struct java_sample sample = {(unsigned char *) buffer, (uint32_t) size, SDK_DATA};
  dds_instance_handle_t handle = 0;
  dds_return_t rc = DDS_RETCODE_OK;
  if (registration) rc = dds_register_instance(writer, &handle, &sample);
  else handle = dds_lookup_instance(writer, &sample);
  (*env)->ReleaseByteArrayElements(env, bytes, buffer, JNI_ABORT);
  if (rc < 0) fail(env, "dds_register_instance", rc);
  return (jlong) handle;
}
JNIEXPORT void JNICALL JNI_METHOD(dispose)(JNIEnv *env, jclass cls, jint writer, jlong instance, jboolean unregister, jlong timestamp) {
  UNUSED_CLASS;
  dds_time_t time = timestamp < 0 ? dds_time() : timestamp;
  dds_return_t rc = unregister ? dds_unregister_instance_ih_ts(writer, (dds_instance_handle_t) instance, time)
                               : dds_dispose_ih_ts(writer, (dds_instance_handle_t) instance, time);
  if (rc < 0) fail(env, "Dispose/unregister instance", rc);
}
JNIEXPORT jlongArray JNICALL JNI_METHOD(matchedHandles)(JNIEnv *env, jclass cls, jint endpoint, jboolean writer) {
  UNUSED_CLASS;
  dds_return_t count = writer ? dds_get_matched_subscriptions(endpoint, NULL, 0) : dds_get_matched_publications(endpoint, NULL, 0);
  if (count < 0) { fail(env, "Get matched handles", count); return NULL; }
  dds_instance_handle_t *handles = count ? calloc((size_t) count, sizeof(*handles)) : NULL;
  if (count && !handles) { fail(env, "Allocate matched handles", DDS_RETCODE_OUT_OF_RESOURCES); return NULL; }
  dds_return_t actual = writer ? dds_get_matched_subscriptions(endpoint, handles, (size_t) count)
                               : dds_get_matched_publications(endpoint, handles, (size_t) count);
  if (actual < 0) { free(handles); fail(env, "Get matched handles", actual); return NULL; }
  if (actual > count) actual = count; /* Discovery may grow the set between calls. */
  jlongArray result = (*env)->NewLongArray(env, actual);
  for (int i = 0; result && i < actual && !(*env)->ExceptionCheck(env); i++) {
    jlong value = (jlong) handles[i];
    (*env)->SetLongArrayRegion(env, result, i, 1, &value);
  }
  free(handles);
  return result;
}

/* Discovery owns all returned buffers. Snapshot QoS before returning native loans. */
static jstring discovery_string(JNIEnv *env, const char *value) {
  if (!value || (*env)->PushLocalFrame(env, 5) < 0) return NULL;
  size_t size = strlen(value);
  if (size > INT_MAX) { fail(env, "Discovery string exceeds Java limits", DDS_RETCODE_OUT_OF_RESOURCES); (*env)->PopLocalFrame(env, NULL); return NULL; }
  jbyteArray bytes = (*env)->NewByteArray(env, (jsize)size);
  if (!bytes) { (*env)->PopLocalFrame(env, NULL); return NULL; }
  if (size) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)size, (const jbyte *)value);
  jclass type = (*env)->FindClass(env, "java/lang/String");
  jmethodID ctor = type ? (*env)->GetMethodID(env, type, "<init>", "([BLjava/lang/String;)V") : NULL;
  jstring encoding = ctor ? (*env)->NewStringUTF(env, "UTF-8") : NULL;
  jstring result = encoding ? (*env)->NewObject(env, type, ctor, bytes, encoding) : NULL;
  return (*env)->PopLocalFrame(env, result);
}
static jobject snapshot_qos(JNIEnv *env, const dds_qos_t *qos) {
  if ((*env)->PushLocalFrame(env, 32) < 0) return NULL;
  jlong v[33]; for (size_t i = 0; i < 33; i++) v[i] = -1;
  v[15] = v[32] = 0;
  if (qos) {
    dds_reliability_kind_t reliability; dds_duration_t duration;
    dds_history_kind_t history; int32_t a, b, c, d; bool flag;
    dds_durability_kind_t durability; dds_liveliness_kind_t liveliness;
    dds_ownership_kind_t ownership; dds_destination_order_kind_t order;
    if (dds_qget_reliability(qos, &reliability, &duration)) { v[0] = reliability; v[1] = duration; }
    if (dds_qget_history(qos, &history, &a)) v[2] = history == DDS_HISTORY_KEEP_ALL ? 0 : a;
    if (dds_qget_durability(qos, &durability)) v[3] = durability;
    if (dds_qget_resource_limits(qos, &a, &b, &c)) { v[4] = a; v[5] = b; v[6] = c; }
    if (dds_qget_deadline(qos, &duration)) v[7] = duration;
    if (dds_qget_lifespan(qos, &duration)) v[8] = duration;
    if (dds_qget_liveliness(qos, &liveliness, &duration)) { v[9] = liveliness; v[10] = duration; }
    if (dds_qget_ownership(qos, &ownership)) v[11] = ownership;
    if (dds_qget_ownership_strength(qos, &a)) { v[12] = a; v[15] = 1; }
    if (dds_qget_time_based_filter(qos, &duration)) v[13] = duration;
    if (dds_qget_writer_data_lifecycle(qos, &flag)) v[14] = flag;
    if (dds_qget_durability_service(qos, &duration, &history, &a, &b, &c, &d)) {
      v[16] = duration; v[17] = history; v[18] = a; v[19] = b; v[20] = c; v[21] = d;
    }
    if (dds_qget_destination_order(qos, &order)) v[22] = order;
    if (dds_qget_latency_budget(qos, &duration)) v[23] = duration;
    if (dds_qget_transport_priority(qos, &a)) { v[24] = a; v[32] = 1; }
    dds_duration_t disposed;
    if (dds_qget_reader_data_lifecycle(qos, &duration, &disposed)) { v[25] = duration; v[26] = disposed; }
    dds_type_consistency_kind_t consistency; bool x, y, z, w;
    if (dds_qget_type_consistency(qos, &consistency, &x, &y, &z, &w, &flag)) v[27] = consistency;
    uint32_t count; dds_data_representation_id_t *ids;
    if (dds_qget_data_representation(qos, &count, &ids)) {
      v[28] = 0; for (uint32_t i = 0; i < count; i++) if (ids[i] == 0 || ids[i] == 2) v[28] |= (jlong)1 << ids[i];
      dds_free(ids);
    }
    dds_presentation_access_scope_kind_t scope;
    if (dds_qget_presentation(qos, &scope, &x, &y)) { v[29] = scope; v[30] = x; v[31] = y; }
  }
  jlongArray values = (*env)->NewLongArray(env, 33);
  if (!values) { (*env)->PopLocalFrame(env, NULL); return NULL; }
  (*env)->SetLongArrayRegion(env, values, 0, 33, v);
  jclass bytes_type = (*env)->FindClass(env, "[B");
  jobjectArray data = bytes_type ? (*env)->NewObjectArray(env, 3, bytes_type, NULL) : NULL;
  if (!data) { (*env)->PopLocalFrame(env, NULL); return NULL; }
  for (int i = 0; qos && i < 3 && !(*env)->ExceptionCheck(env); i++) {
    void *bytes = NULL; size_t size = 0;
    bool present = i == 0 ? dds_qget_userdata(qos, &bytes, &size) : i == 1 ? dds_qget_groupdata(qos, &bytes, &size) : dds_qget_topicdata(qos, &bytes, &size);
    if (present && size <= INT_MAX) {
      jbyteArray value = (*env)->NewByteArray(env, (jsize)size);
      if (value && size) (*env)->SetByteArrayRegion(env, value, 0, (jsize)size, bytes);
      if (!(*env)->ExceptionCheck(env)) (*env)->SetObjectArrayElement(env, data, i, value);
      if (value) (*env)->DeleteLocalRef(env, value);
    } else if (size > INT_MAX) fail(env, "Discovery QoS exceeds Java limits", DDS_RETCODE_OUT_OF_RESOURCES);
    dds_free(bytes);
  }
  jobjectArray partitions = NULL; uint32_t count = 0; char **names = NULL;
  if (!(*env)->ExceptionCheck(env) && qos && dds_qget_partition(qos, &count, &names)) {
    jclass strings = (*env)->FindClass(env, "java/lang/String");
    if (strings) partitions = (*env)->NewObjectArray(env, (jsize)count, strings, NULL);
    for (uint32_t i = 0; i < count; i++) {
      if (partitions && !(*env)->ExceptionCheck(env)) {
        jstring name = discovery_string(env, names[i]);
        if (name) { (*env)->SetObjectArrayElement(env, partitions, (jsize)i, name); (*env)->DeleteLocalRef(env, name); }
      }
      dds_free(names[i]);
    }
    dds_free(names);
  }
  jobject result = NULL;
  if (!(*env)->ExceptionCheck(env)) {
    jclass type = (*env)->FindClass(env, "org/eclipse/cyclonedds/internal/Native$Qos");
    jmethodID ctor = type ? (*env)->GetMethodID(env, type, "<init>", "([J[[B[Ljava/lang/String;)V") : NULL;
    if (ctor) result = (*env)->NewObject(env, type, ctor, values, data, partitions);
  }
  return (*env)->PopLocalFrame(env, result);
}
JNIEXPORT jobject JNICALL JNI_METHOD(getQos)(JNIEnv *env, jclass cls, jint entity) {
  UNUSED_CLASS;
  dds_qos_t *qos = dds_create_qos();
  if (!qos) { fail(env, "Allocate QoS snapshot", DDS_RETCODE_OUT_OF_RESOURCES); return NULL; }
  dds_return_t rc = dds_get_qos(entity, qos);
  jobject result = NULL;
  if (rc < 0) fail(env, "Get entity QoS", rc); else result = snapshot_qos(env, qos);
  dds_delete_qos(qos); return result;
}
JNIEXPORT jint JNICALL JNI_METHOD(readerTopic)(JNIEnv *env, jclass cls, jint reader) {
  UNUSED_CLASS;
  dds_entity_t topic = dds_get_topic(reader);
  if (topic < 0) fail(env, "Get built-in reader topic", topic);
  return topic;
}

static jintArray discovery_key(JNIEnv *env, const void *key) {
  const unsigned char *bytes = key;
  jint words[4];
  for (int i = 0; i < 4; i++) words[i] = (jint)((uint32_t)bytes[4*i] << 24 | (uint32_t)bytes[4*i+1] << 16 | (uint32_t)bytes[4*i+2] << 8 | bytes[4*i+3]);
  jintArray value = (*env)->NewIntArray(env, 4);
  if (value) (*env)->SetIntArrayRegion(env, value, 0, 4, words);
  return value;
}
static jobject discovery_data(JNIEnv *env, int kind, const void *value, const dds_sample_info_t *info) {
  if ((*env)->PushLocalFrame(env, 24) < 0) return NULL;
  const dds_builtintopic_participant_t *participant = value;
  const dds_builtintopic_topic_t *topic = value;
  const dds_builtintopic_endpoint_t *endpoint = value;
  bool valid = !info || info->valid_data;
  const dds_qos_t *qos = !valid ? NULL : kind == 1 ? participant->qos : kind == 2 ? topic->qos : endpoint->qos;
  jintArray key = discovery_key(env, value), parent = NULL;
  if (key && kind >= 3 && valid) parent = discovery_key(env, &endpoint->participant_key);
  jobject policies = !(*env)->ExceptionCheck(env) ? snapshot_qos(env, qos) : NULL;
  jstring name = NULL, type_name = NULL;
  if (policies && valid && kind != 1) {
    name = discovery_string(env, kind == 2 ? topic->topic_name : endpoint->topic_name);
    if (name) type_name = discovery_string(env, kind == 2 ? topic->type_name : endpoint->type_name);
  }
  jobject sample_info = NULL;
  if (info && !(*env)->ExceptionCheck(env)) {
    jclass wire = (*env)->FindClass(env, "org/eclipse/cyclonedds/internal/WireSample");
    jmethodID ctor = wire ? (*env)->GetMethodID(env, wire, "<init>", "([BZJJIIIJIIIII)V") : NULL;
    if (ctor) sample_info = (*env)->NewObject(env, wire, ctor, NULL, (jboolean)info->valid_data,
        (jlong)info->source_timestamp, (jlong)info->instance_handle, (jint)info->instance_state,
        (jint)info->sample_state, (jint)info->view_state, (jlong)info->publication_handle,
        (jint)info->disposed_generation_count, (jint)info->no_writers_generation_count,
        (jint)info->sample_rank, (jint)info->generation_rank, (jint)info->absolute_generation_rank);
  }
  jobject result = NULL;
  if (!(*env)->ExceptionCheck(env) && key && policies) {
    jclass type = (*env)->FindClass(env, "org/eclipse/cyclonedds/internal/DiscoveryData");
    jmethodID ctor = type ? (*env)->GetMethodID(env, type, "<init>", "(I[I[ILjava/lang/String;Ljava/lang/String;Lorg/eclipse/cyclonedds/internal/Native$Qos;Lorg/eclipse/cyclonedds/internal/WireSample;)V") : NULL;
    if (ctor) result = (*env)->NewObject(env, type, ctor, kind, key, parent, name, type_name, policies, sample_info);
  }
  return (*env)->PopLocalFrame(env, result);
}
JNIEXPORT jobject JNICALL JNI_METHOD(matchedData)(JNIEnv *env, jclass cls, jint entity, jboolean writer, jlong instance) {
  UNUSED_CLASS;
  dds_builtintopic_endpoint_t *data = writer ? dds_get_matched_subscription_data(entity, (dds_instance_handle_t)instance)
      : dds_get_matched_publication_data(entity, (dds_instance_handle_t)instance);
  if (!data) { fail(env, "Get matched endpoint data", DDS_RETCODE_PRECONDITION_NOT_MET); return NULL; }
  jobject result = discovery_data(env, writer ? 4 : 3, data, NULL);
  dds_builtintopic_free_endpoint(data); return result;
}
JNIEXPORT jint JNICALL JNI_METHOD(builtinReader)(JNIEnv *env, jclass cls, jint subscriber, jint kind) {
  UNUSED_CLASS;
  if (kind < 1 || kind > 4) { fail(env, "Built-in topic kind", DDS_RETCODE_BAD_PARAMETER); return 0; }
#ifndef DDS_HAS_TOPIC_DISCOVERY
  if (kind == 2) { fail(env, "Topic discovery is disabled in this Cyclone DDS core", DDS_RETCODE_UNSUPPORTED); return 0; }
#endif
  dds_entity_t topics[] = {DDS_BUILTIN_TOPIC_DCPSPARTICIPANT, DDS_BUILTIN_TOPIC_DCPSTOPIC, DDS_BUILTIN_TOPIC_DCPSPUBLICATION, DDS_BUILTIN_TOPIC_DCPSSUBSCRIPTION};
  dds_entity_t reader = dds_create_reader(subscriber, topics[kind-1], NULL, NULL);
  if (reader < 0) fail(env, "Create built-in reader", reader);
  return reader;
}
JNIEXPORT jobjectArray JNICALL JNI_METHOD(builtinRead)(JNIEnv *, jclass, jint, jint, jint, jint, jint, jlong, jboolean);
JNIEXPORT jobject JNICALL JNI_METHOD(builtinKey)(JNIEnv *env, jclass cls, jint reader, jint kind, jlong instance) {
  jobjectArray samples = JNI_METHOD(builtinRead)(env, cls, reader, kind, 1, 3, 127, instance, JNI_FALSE);
  if (!samples) return NULL;
  if ((*env)->GetArrayLength(env, samples) == 0) { (*env)->DeleteLocalRef(env, samples); fail(env, "Unknown discovery instance", DDS_RETCODE_BAD_PARAMETER); return NULL; }
  jobject result = (*env)->GetObjectArrayElement(env, samples, 0);
  (*env)->DeleteLocalRef(env, samples); return result;
}
JNIEXPORT jlong JNICALL JNI_METHOD(builtinLookup)(JNIEnv *env, jclass cls, jint reader, jintArray key) {
  UNUSED_CLASS;
  if (!key || (*env)->GetArrayLength(env, key) != 4) { fail(env, "Discovery key must contain four GUID words", DDS_RETCODE_BAD_PARAMETER); return 0; }
  jint words[4]; (*env)->GetIntArrayRegion(env, key, 0, 4, words);
  if ((*env)->ExceptionCheck(env)) return 0;
  union { dds_builtintopic_participant_t p; dds_builtintopic_topic_t t; dds_builtintopic_endpoint_t e; } value = {0};
  unsigned char *bytes = (unsigned char *)&value;
  for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) bytes[4*i+j] = (unsigned char)((uint32_t)words[i] >> (24-8*j));
  return (jlong)dds_lookup_instance(reader, &value);
}
JNIEXPORT jobjectArray JNICALL JNI_METHOD(builtinRead)(JNIEnv *env, jclass cls, jint reader, jint kind, jint maximum, jint operation, jint mask, jlong instance, jboolean next) {
  UNUSED_CLASS;
  if (maximum < 1 || maximum > 65536 || kind < 1 || kind > 4 || operation < 0 || operation > 3) { fail(env, "Discovery read arguments", DDS_RETCODE_BAD_PARAMETER); return NULL; }
  struct collected_samples batch = {0};
  batch.samples = calloc((size_t)maximum, sizeof(*batch.samples));
  batch.infos = calloc((size_t)maximum, sizeof(*batch.infos));
  batch.types = calloc((size_t)maximum, sizeof(*batch.types));
  if (!batch.samples || !batch.infos || !batch.types) {
    free(batch.samples); free(batch.infos); free(batch.types); fail(env, "Allocate discovery batch", DDS_RETCODE_OUT_OF_RESOURCES); return NULL;
  }
  dds_return_t count;
  if (operation == 0 || operation == 3) count = next ? dds_peek_next_instance_with_collector(reader, (uint32_t)maximum, (dds_instance_handle_t)instance, (uint32_t)mask, collect_sample, &batch)
      : dds_peek_with_collector(reader, (uint32_t)maximum, (dds_instance_handle_t)instance, (uint32_t)mask, collect_sample, &batch);
  else if (next) count = operation == 2 ? dds_take_next_instance_with_collector(reader, (uint32_t)maximum, (dds_instance_handle_t)instance, (uint32_t)mask, collect_sample, &batch)
      : dds_read_next_instance_with_collector(reader, (uint32_t)maximum, (dds_instance_handle_t)instance, (uint32_t)mask, collect_sample, &batch);
  else count = operation == 2 ? dds_take_with_collector(reader, (uint32_t)maximum, (dds_instance_handle_t)instance, (uint32_t)mask, collect_sample, &batch)
      : dds_read_with_collector(reader, (uint32_t)maximum, (dds_instance_handle_t)instance, (uint32_t)mask, collect_sample, &batch);
  jobjectArray result = NULL;
  if (count < 0) fail(env, "Read discovery data", operation == 3 && count == DDS_RETCODE_PRECONDITION_NOT_MET ? DDS_RETCODE_BAD_PARAMETER : count);
  else {
    jclass type = (*env)->FindClass(env, "org/eclipse/cyclonedds/internal/DiscoveryData");
    if (type) result = (*env)->NewObjectArray(env, count, type, NULL);
    for (int i = 0; result && i < count && !(*env)->ExceptionCheck(env); i++) {
      union { dds_builtintopic_participant_t participant; dds_builtintopic_topic_t topic; dds_builtintopic_endpoint_t endpoint; } value = {0};
      const struct ddsi_sertype *sertype = batch.types[i];
      bool ok = batch.infos[i].valid_data ? ddsi_serdata_to_sample(batch.samples[i], &value, NULL, NULL)
          : ddsi_serdata_untyped_to_sample(sertype, batch.samples[i], &value, NULL, NULL);
      if (!ok) fail(env, "Decode discovery sample", DDS_RETCODE_OUT_OF_RESOURCES);
      else {
        jobject sample = discovery_data(env, kind, &value, &batch.infos[i]);
        if (sample) { (*env)->SetObjectArrayElement(env, result, i, sample); (*env)->DeleteLocalRef(env, sample); }
      }
      void *pointer = &value;
      sertype->ops->free_samples(sertype, &pointer, 1, DDS_FREE_CONTENTS_BIT);
    }
  }
  for (uint32_t i = 0; i < batch.count; i++) ddsi_serdata_unref(batch.samples[i]);
  free(batch.samples); free(batch.infos); free(batch.types); return result;
}

JNIEXPORT jint JNICALL JNI_METHOD(matched)(JNIEnv *env, jclass cls, jint entity, jboolean writer) {
  UNUSED_CLASS;
  dds_return_t rc;
  uint32_t count = 0;
  if (writer) {
    dds_publication_matched_status_t status;
    rc = dds_get_publication_matched_status(entity, &status);
    if (rc >= 0) count = status.current_count;
  } else {
    dds_subscription_matched_status_t status;
    rc = dds_get_subscription_matched_status(entity, &status);
    if (rc >= 0) count = status.current_count;
  }
  if (rc < 0) fail(env, "Get matched endpoints", rc);
  return (jint) count;
}

/* Runtime-owned waitables match the environment-wide OMG API. DDS pins native
 * entities during waits; no Java participant monitor is held while blocking. */
JNIEXPORT jint JNICALL JNI_METHOD(waitset)(JNIEnv *env, jclass cls) {
  UNUSED_CLASS;
  dds_entity_t entity = dds_create_waitset(DDS_CYCLONEDDS_HANDLE);
  if (entity > 0) {
    dds_return_t rc = dds_waitset_attach(entity, entity, 0);
    if (rc < 0) { dds_delete(entity); entity = rc; }
  }
  if (entity < 0) fail(env, "Create waitset", entity);
  return entity;
}
JNIEXPORT jint JNICALL JNI_METHOD(guard)(JNIEnv *env, jclass cls) {
  UNUSED_CLASS;
  dds_entity_t entity = dds_create_guardcondition(DDS_CYCLONEDDS_HANDLE);
  if (entity < 0) fail(env, "Create guard condition", entity);
  return entity;
}
JNIEXPORT jint JNICALL JNI_METHOD(readCondition)(JNIEnv *env, jclass cls, jint reader, jint mask) {
  UNUSED_CLASS;
  dds_entity_t entity = dds_create_readcondition(reader, (uint32_t) mask);
  if (entity < 0) fail(env, "Create read condition", entity);
  return entity;
}
JNIEXPORT jboolean JNICALL JNI_METHOD(triggered)(JNIEnv *env, jclass cls, jint entity) {
  UNUSED_CLASS;
  dds_return_t rc = dds_triggered(entity);
  if (rc < 0) fail(env, "Condition trigger value", rc);
  return rc > 0;
}
JNIEXPORT void JNICALL JNI_METHOD(guardTrigger)(JNIEnv *env, jclass cls, jint entity, jboolean value) {
  UNUSED_CLASS;
  dds_return_t rc = dds_set_guardcondition(entity, value);
  if (rc < 0) fail(env, "Set guard condition", rc);
}
JNIEXPORT void JNICALL JNI_METHOD(waitsetTrigger)(JNIEnv *env, jclass cls, jint entity, jboolean value) {
  UNUSED_CLASS;
  dds_return_t rc = dds_waitset_set_trigger(entity, value);
  if (rc < 0) fail(env, "Wake waitset", rc);
}
JNIEXPORT void JNICALL JNI_METHOD(attach)(JNIEnv *env, jclass cls, jint ws, jint entity, jboolean attach) {
  UNUSED_CLASS;
  dds_return_t rc = attach ? dds_waitset_attach(ws, entity, (dds_attach_t) entity) : dds_waitset_detach(ws, entity);
  if (rc < 0) fail(env, attach ? "Attach condition" : "Detach condition", rc);
}
JNIEXPORT jint JNICALL JNI_METHOD(await)(JNIEnv *env, jclass cls, jint ws, jlong timeout) {
  UNUSED_CLASS;
  dds_return_t rc = dds_waitset_wait(ws, NULL, 0, timeout);
  if (rc < 0) fail(env, "Wait for conditions", rc);
  return rc;
}
JNIEXPORT jint JNICALL JNI_METHOD(statusMask)(JNIEnv *env, jclass cls, jint entity, jint mask, jboolean set) {
  UNUSED_CLASS;
  uint32_t value = (uint32_t) mask;
  dds_return_t rc = set ? dds_set_status_mask(entity, value) : dds_get_status_mask(entity, &value);
  if (rc < 0) fail(env, "Status mask", rc);
  return (jint) value;
}
JNIEXPORT jint JNICALL JNI_METHOD(statusChanges)(JNIEnv *env, jclass cls, jint entity) {
  UNUSED_CLASS;
  uint32_t value = 0;
  dds_return_t rc = dds_get_status_changes(entity, &value);
  if (rc < 0) fail(env, "Status changes", rc);
  return (jint) value;
}
JNIEXPORT jlongArray JNICALL JNI_METHOD(matchedStatus)(JNIEnv *env, jclass cls, jint entity, jboolean writer) {
  UNUSED_CLASS;
  jlong values[5];
  dds_return_t rc;
  if (writer) {
    dds_publication_matched_status_t status;
    rc = dds_get_publication_matched_status(entity, &status);
    if (rc < 0) { fail(env, "Publication matched status", rc); return NULL; }
    values[0] = status.total_count; values[1] = status.total_count_change;
    values[2] = status.current_count; values[3] = status.current_count_change;
    values[4] = (jlong) status.last_subscription_handle;
  } else {
    dds_subscription_matched_status_t status;
    rc = dds_get_subscription_matched_status(entity, &status);
    if (rc < 0) { fail(env, "Subscription matched status", rc); return NULL; }
    values[0] = status.total_count; values[1] = status.total_count_change;
    values[2] = status.current_count; values[3] = status.current_count_change;
    values[4] = (jlong) status.last_publication_handle;
  }
  jlongArray result = (*env)->NewLongArray(env, 5);
  if (result) (*env)->SetLongArrayRegion(env, result, 0, 5, values);
  return result;
}
JNIEXPORT void JNICALL JNI_METHOD(historicalData)(JNIEnv *env, jclass cls, jint reader, jlong timeout) {
  UNUSED_CLASS;
  dds_return_t rc = dds_reader_wait_for_historical_data(reader, timeout);
  if (rc < 0) fail(env, "Wait for historical data", rc);
}

JNIEXPORT jint JNICALL JNI_METHOD(endpointConfigured)(JNIEnv *env, jclass cls, jint parent, jint topic, jboolean writer, jobject configuration) {
  UNUSED_CLASS;
  dds_qos_t *qos = configured_qos(env, configuration);
  if (!qos) return 0;
  dds_entity_t entity = writer ? dds_create_writer(parent, topic, qos, NULL) : dds_create_reader(parent, topic, qos, NULL);
  dds_delete_qos(qos);
  if (entity < 0) fail(env, "Create configured endpoint", entity);
  return entity;
}
JNIEXPORT jint JNICALL JNI_METHOD(containerConfigured)(JNIEnv *env, jclass cls, jint participant, jboolean publisher, jobject configuration) {
  UNUSED_CLASS;
  dds_qos_t *qos = configured_qos(env, configuration);
  if (!qos) return 0;
  dds_entity_t entity = publisher ? dds_create_publisher(participant, qos, NULL) : dds_create_subscriber(participant, qos, NULL);
  dds_delete_qos(qos);
  if (entity < 0) fail(env, "Create configured container", entity);
  return entity;
}
JNIEXPORT void JNICALL JNI_METHOD(setQos)(JNIEnv *env, jclass cls, jint entity, jobject configuration) {
  UNUSED_CLASS;
  dds_qos_t *qos = configured_qos(env, configuration);
  if (!qos) return;
  dds_return_t rc = dds_set_qos(entity, qos);
  if (rc < 0) fail(env, "Set QoS", rc);
  dds_delete_qos(qos);
}
JNIEXPORT void JNICALL JNI_METHOD(assertLiveliness)(JNIEnv *env, jclass cls, jint entity) {
  UNUSED_CLASS;
  dds_return_t rc = dds_assert_liveliness(entity);
  if (rc < 0) fail(env, "Assert liveliness", rc);
}

JNIEXPORT jobjectArray JNICALL JNI_METHOD(selectNext)(JNIEnv *env, jclass cls, jint reader, jint maximum, jboolean take, jint mask, jlong instance) {
  UNUSED_CLASS;
  return read_samples(env, reader, maximum, take, (uint32_t) mask, (dds_instance_handle_t) instance, true);
}
JNIEXPORT jbyteArray JNICALL JNI_METHOD(keyValue)(JNIEnv *env, jclass cls, jint entity, jlong instance, jbyteArray holder) {
  UNUSED_CLASS;
  const struct ddsi_sertype *type;
  dds_return_t rc = dds_get_entity_sertype(entity, &type);
  if (rc < 0) { fail(env, "Key type", rc); return NULL; }
  struct java_sample key = {0};
  rc = dds_instance_get_key(entity, (dds_instance_handle_t) instance, &key);
  if (rc < 0) { fail(env, "dds_instance_get_key", rc); return NULL; }
  uint32_t holder_size = (uint32_t) (*env)->GetArrayLength(env, holder);
  jbyte *bytes = (*env)->GetByteArrayElements(env, holder, NULL);
  struct java_sample value = {(unsigned char *) bytes, holder_size, SDK_DATA};
  bool ok = bytes && java_merge_key(type, &key, &value);
  if (bytes) (*env)->ReleaseByteArrayElements(env, holder, bytes, JNI_ABORT);
  ddsrt_free(key.bytes);
  if (!ok) { fail(env, "Decode instance key", DDS_RETCODE_BAD_PARAMETER); return NULL; }
  jbyteArray result = (*env)->NewByteArray(env, (jsize) value.size);
  if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize) value.size, (jbyte *) value.bytes);
  ddsrt_free(value.bytes);
  return result;
}

/* Native callbacks only enqueue immutable event snapshots. They never execute user code. */
struct listener_context { JavaVM *vm; jobject receiver; jmethodID receive; };
static void emit_event(struct listener_context *ctx, int kind, const jlong *values, int count) {
  JNIEnv *env = NULL;
  bool attached = (*ctx->vm)->GetEnv(ctx->vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED;
  if (attached && (*ctx->vm)->AttachCurrentThreadAsDaemon(ctx->vm, (void **) &env, NULL) != JNI_OK) return;
  jlongArray data = (*env)->NewLongArray(env, count);
  if (data) {
    (*env)->SetLongArrayRegion(env, data, 0, count, values);
    if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, ctx->receiver, ctx->receive, (jint) kind, data);
    (*env)->DeleteLocalRef(env, data);
  }
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
  if (attached) (*ctx->vm)->DetachCurrentThread(ctx->vm);
}
static void on_inconsistent_topic(dds_entity_t entity, const dds_inconsistent_topic_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change};
  emit_event(arg, 0, values, 2);
}
static void on_offered_deadline_missed(dds_entity_t entity, const dds_offered_deadline_missed_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.last_instance_handle};
  emit_event(arg, 1, values, 3);
}
static void on_requested_deadline_missed(dds_entity_t entity, const dds_requested_deadline_missed_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.last_instance_handle};
  emit_event(arg, 2, values, 3);
}
static void on_offered_incompatible_qos(dds_entity_t entity, const dds_offered_incompatible_qos_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.last_policy_id};
  emit_event(arg, 3, values, 3);
}
static void on_requested_incompatible_qos(dds_entity_t entity, const dds_requested_incompatible_qos_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.last_policy_id};
  emit_event(arg, 4, values, 3);
}
static void on_sample_lost(dds_entity_t entity, const dds_sample_lost_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change};
  emit_event(arg, 5, values, 2);
}
static void on_sample_rejected(dds_entity_t entity, const dds_sample_rejected_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.last_reason, (jlong) status.last_instance_handle};
  emit_event(arg, 6, values, 4);
}
static void on_data_on_readers(dds_entity_t entity, void *arg) {
  (void) entity;
  jlong values[] = {0};
  emit_event(arg, 7, values, 0);
}
static void on_data_available(dds_entity_t entity, void *arg) {
  (void) entity;
  jlong values[] = {0};
  emit_event(arg, 8, values, 0);
}
static void on_liveliness_lost(dds_entity_t entity, const dds_liveliness_lost_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change};
  emit_event(arg, 9, values, 2);
}
static void on_liveliness_changed(dds_entity_t entity, const dds_liveliness_changed_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.alive_count, (jlong) status.not_alive_count, (jlong) status.alive_count_change, (jlong) status.not_alive_count_change, (jlong) status.last_publication_handle};
  emit_event(arg, 10, values, 5);
}
static void on_publication_matched(dds_entity_t entity, const dds_publication_matched_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.current_count, (jlong) status.current_count_change, (jlong) status.last_subscription_handle};
  emit_event(arg, 11, values, 5);
}
static void on_subscription_matched(dds_entity_t entity, const dds_subscription_matched_status_t status, void *arg) {
  (void) entity;
  jlong values[] = {(jlong) status.total_count, (jlong) status.total_count_change, (jlong) status.current_count, (jlong) status.current_count_change, (jlong) status.last_publication_handle};
  emit_event(arg, 12, values, 5);
}
JNIEXPORT jlong JNICALL JNI_METHOD(listenerCreate)(JNIEnv *env, jclass cls, jobject receiver) {
  UNUSED_CLASS;
  struct listener_context *ctx = calloc(1, sizeof(*ctx));
  if (!ctx) { fail(env, "Listener allocation", DDS_RETCODE_OUT_OF_RESOURCES); return 0; }
  (*env)->GetJavaVM(env, &ctx->vm);
  ctx->receiver = (*env)->NewGlobalRef(env, receiver);
  jclass type = (*env)->GetObjectClass(env, receiver);
  ctx->receive = (*env)->GetMethodID(env, type, "onEvent", "(I[J)V");
  if (!ctx->receiver || !ctx->receive) { if (ctx->receiver) (*env)->DeleteGlobalRef(env, ctx->receiver); free(ctx); return 0; }
  return (jlong) (intptr_t) ctx;
}
JNIEXPORT void JNICALL JNI_METHOD(listenerSet)(JNIEnv *env, jclass cls, jint entity, jlong context, jint mask) {
  UNUSED_CLASS;
  struct listener_context *ctx = (void *) (intptr_t) context;
  dds_listener_t *listener = dds_create_listener(ctx);
  if (!listener) { fail(env, "Listener allocation", DDS_RETCODE_OUT_OF_RESOURCES); return; }
  if (mask & (1 << 0)) dds_lset_inconsistent_topic_arg(listener, on_inconsistent_topic, ctx, true);
  if (mask & (1 << 1)) dds_lset_offered_deadline_missed_arg(listener, on_offered_deadline_missed, ctx, true);
  if (mask & (1 << 2)) dds_lset_requested_deadline_missed_arg(listener, on_requested_deadline_missed, ctx, true);
  if (mask & (1 << 3)) dds_lset_offered_incompatible_qos_arg(listener, on_offered_incompatible_qos, ctx, true);
  if (mask & (1 << 4)) dds_lset_requested_incompatible_qos_arg(listener, on_requested_incompatible_qos, ctx, true);
  if (mask & (1 << 5)) dds_lset_sample_lost_arg(listener, on_sample_lost, ctx, true);
  if (mask & (1 << 6)) dds_lset_sample_rejected_arg(listener, on_sample_rejected, ctx, true);
  if (mask & (1 << 7)) dds_lset_data_on_readers_arg(listener, on_data_on_readers, ctx, false);
  if (mask & (1 << 8)) dds_lset_data_available_arg(listener, on_data_available, ctx, true);
  if (mask & (1 << 9)) dds_lset_liveliness_lost_arg(listener, on_liveliness_lost, ctx, true);
  if (mask & (1 << 10)) dds_lset_liveliness_changed_arg(listener, on_liveliness_changed, ctx, true);
  if (mask & (1 << 11)) dds_lset_publication_matched_arg(listener, on_publication_matched, ctx, true);
  if (mask & (1 << 12)) dds_lset_subscription_matched_arg(listener, on_subscription_matched, ctx, true);
  dds_return_t rc = dds_set_listener(entity, listener);
  dds_delete_listener(listener);
  if (rc < 0) fail(env, "dds_set_listener", rc);
}
JNIEXPORT void JNICALL JNI_METHOD(listenerFree)(JNIEnv *env, jclass cls, jlong context) {
  UNUSED_CLASS;
  struct listener_context *ctx = (void *) (intptr_t) context;
  if (ctx) { (*env)->DeleteGlobalRef(env, ctx->receiver); free(ctx); }
}
JNIEXPORT jlongArray JNICALL JNI_METHOD(status)(JNIEnv *env, jclass cls, jint entity, jint kind) {
  UNUSED_CLASS;
  jlong values[5] = {0};
  int count = 0;
  dds_return_t rc = DDS_RETCODE_BAD_PARAMETER;
  switch (kind) {
    case 0: { dds_inconsistent_topic_status_t status; rc = dds_get_inconsistent_topic_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      count = 2; } break; }
    case 1: { dds_offered_deadline_missed_status_t status; rc = dds_get_offered_deadline_missed_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.last_instance_handle;
      count = 3; } break; }
    case 2: { dds_requested_deadline_missed_status_t status; rc = dds_get_requested_deadline_missed_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.last_instance_handle;
      count = 3; } break; }
    case 3: { dds_offered_incompatible_qos_status_t status; rc = dds_get_offered_incompatible_qos_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.last_policy_id;
      count = 3; } break; }
    case 4: { dds_requested_incompatible_qos_status_t status; rc = dds_get_requested_incompatible_qos_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.last_policy_id;
      count = 3; } break; }
    case 5: { dds_sample_lost_status_t status; rc = dds_get_sample_lost_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      count = 2; } break; }
    case 6: { dds_sample_rejected_status_t status; rc = dds_get_sample_rejected_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.last_reason;
      values[3] = (jlong) status.last_instance_handle;
      count = 4; } break; }
    case 9: { dds_liveliness_lost_status_t status; rc = dds_get_liveliness_lost_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      count = 2; } break; }
    case 10: { dds_liveliness_changed_status_t status; rc = dds_get_liveliness_changed_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.alive_count;
      values[1] = (jlong) status.not_alive_count;
      values[2] = (jlong) status.alive_count_change;
      values[3] = (jlong) status.not_alive_count_change;
      values[4] = (jlong) status.last_publication_handle;
      count = 5; } break; }
    case 11: { dds_publication_matched_status_t status; rc = dds_get_publication_matched_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.current_count;
      values[3] = (jlong) status.current_count_change;
      values[4] = (jlong) status.last_subscription_handle;
      count = 5; } break; }
    case 12: { dds_subscription_matched_status_t status; rc = dds_get_subscription_matched_status(entity, &status); if (rc >= 0) {
      values[0] = (jlong) status.total_count;
      values[1] = (jlong) status.total_count_change;
      values[2] = (jlong) status.current_count;
      values[3] = (jlong) status.current_count_change;
      values[4] = (jlong) status.last_publication_handle;
      count = 5; } break; }
  }
  if (rc < 0) { fail(env, "Get DDS status", rc); return NULL; }
  jlongArray result = (*env)->NewLongArray(env, count);
  if (result) (*env)->SetLongArrayRegion(env, result, 0, count, values);
  return result;
}

struct filter_context { JavaVM *vm; jobject filter; jmethodID matches; };
static struct filter_context *new_filter(JNIEnv *env, jobject filter) {
  struct filter_context *ctx = calloc(1, sizeof(*ctx));
  if (!ctx) { fail(env, "Filter allocation", DDS_RETCODE_OUT_OF_RESOURCES); return NULL; }
  (*env)->GetJavaVM(env, &ctx->vm);
  ctx->filter = (*env)->NewGlobalRef(env, filter);
  jclass type = (*env)->GetObjectClass(env, filter);
  ctx->matches = (*env)->GetMethodID(env, type, "matches", "([B)Z");
  if (!ctx->filter || !ctx->matches) {
    if (ctx->filter) (*env)->DeleteGlobalRef(env, ctx->filter);
    free(ctx); return NULL;
  }
  return ctx;
}
static bool filter_sample(const void *sample, void *arg) {
  const struct java_sample *value = sample;
  struct filter_context *ctx = arg;
  if (value->kind != SDK_DATA) return true; /* Lifecycle samples contain keys only. */
  JNIEnv *env = NULL;
  bool attached = (*ctx->vm)->GetEnv(ctx->vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED;
  if (attached && (*ctx->vm)->AttachCurrentThreadAsDaemon(ctx->vm, (void **) &env, NULL) != JNI_OK) return false;
  bool result = false;
  jbyteArray bytes = (*env)->NewByteArray(env, (jsize) value->size);
  if (bytes) {
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize) value->size, (jbyte *) value->bytes);
    if (!(*env)->ExceptionCheck(env)) result = (*env)->CallBooleanMethod(env, ctx->filter, ctx->matches, bytes);
    (*env)->DeleteLocalRef(env, bytes);
  }
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); result = false; }
  if (attached) (*ctx->vm)->DetachCurrentThread(ctx->vm);
  return result;
}
static _Atomic(struct filter_context *) query_filters[64];
static atomic_bool query_used[64];
static bool query_0(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[0])); }
static bool query_1(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[1])); }
static bool query_2(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[2])); }
static bool query_3(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[3])); }
static bool query_4(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[4])); }
static bool query_5(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[5])); }
static bool query_6(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[6])); }
static bool query_7(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[7])); }
static bool query_8(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[8])); }
static bool query_9(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[9])); }
static bool query_10(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[10])); }
static bool query_11(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[11])); }
static bool query_12(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[12])); }
static bool query_13(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[13])); }
static bool query_14(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[14])); }
static bool query_15(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[15])); }
static bool query_16(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[16])); }
static bool query_17(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[17])); }
static bool query_18(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[18])); }
static bool query_19(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[19])); }
static bool query_20(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[20])); }
static bool query_21(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[21])); }
static bool query_22(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[22])); }
static bool query_23(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[23])); }
static bool query_24(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[24])); }
static bool query_25(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[25])); }
static bool query_26(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[26])); }
static bool query_27(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[27])); }
static bool query_28(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[28])); }
static bool query_29(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[29])); }
static bool query_30(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[30])); }
static bool query_31(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[31])); }
static bool query_32(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[32])); }
static bool query_33(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[33])); }
static bool query_34(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[34])); }
static bool query_35(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[35])); }
static bool query_36(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[36])); }
static bool query_37(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[37])); }
static bool query_38(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[38])); }
static bool query_39(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[39])); }
static bool query_40(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[40])); }
static bool query_41(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[41])); }
static bool query_42(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[42])); }
static bool query_43(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[43])); }
static bool query_44(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[44])); }
static bool query_45(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[45])); }
static bool query_46(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[46])); }
static bool query_47(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[47])); }
static bool query_48(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[48])); }
static bool query_49(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[49])); }
static bool query_50(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[50])); }
static bool query_51(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[51])); }
static bool query_52(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[52])); }
static bool query_53(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[53])); }
static bool query_54(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[54])); }
static bool query_55(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[55])); }
static bool query_56(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[56])); }
static bool query_57(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[57])); }
static bool query_58(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[58])); }
static bool query_59(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[59])); }
static bool query_60(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[60])); }
static bool query_61(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[61])); }
static bool query_62(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[62])); }
static bool query_63(const void *sample) { return filter_sample(sample, atomic_load(&query_filters[63])); }
static dds_querycondition_filter_fn const query_functions[64] = {query_0,query_1,query_2,query_3,query_4,query_5,query_6,query_7,query_8,query_9,query_10,query_11,query_12,query_13,query_14,query_15,query_16,query_17,query_18,query_19,query_20,query_21,query_22,query_23,query_24,query_25,query_26,query_27,query_28,query_29,query_30,query_31,query_32,query_33,query_34,query_35,query_36,query_37,query_38,query_39,query_40,query_41,query_42,query_43,query_44,query_45,query_46,query_47,query_48,query_49,query_50,query_51,query_52,query_53,query_54,query_55,query_56,query_57,query_58,query_59,query_60,query_61,query_62,query_63};
JNIEXPORT jlongArray JNICALL JNI_METHOD(queryCreate)(JNIEnv *env, jclass cls, jint reader, jint mask, jobject filter) {
  UNUSED_CLASS;
  int slot;
  for (slot = 0; slot < 64; slot++) { bool expected = false; if (atomic_compare_exchange_strong(&query_used[slot], &expected, true)) break; }
  if (slot == 64) { fail(env, "Query callback slots (64 per process)", DDS_RETCODE_OUT_OF_RESOURCES); return NULL; }
  struct filter_context *ctx = new_filter(env, filter);
  if (!ctx) { atomic_store(&query_used[slot], false); return NULL; }
  atomic_store(&query_filters[slot], ctx);
  dds_entity_t condition = dds_create_querycondition(reader, (uint32_t) mask, query_functions[slot]);
  if (condition < 0) {
    atomic_store(&query_filters[slot], NULL); atomic_store(&query_used[slot], false);
    (*env)->DeleteGlobalRef(env, ctx->filter); free(ctx); fail(env, "dds_create_querycondition", condition); return NULL;
  }
  jlong values[] = {condition, slot};
  jlongArray result = (*env)->NewLongArray(env, 2);
  if (result) (*env)->SetLongArrayRegion(env, result, 0, 2, values);
  else {
    dds_delete(condition); atomic_store(&query_filters[slot], NULL); atomic_store(&query_used[slot], false);
    (*env)->DeleteGlobalRef(env, ctx->filter); free(ctx);
  }
  return result;
}
JNIEXPORT void JNICALL JNI_METHOD(queryDelete)(JNIEnv *env, jclass cls, jint condition, jint slot) {
  UNUSED_CLASS;
  dds_return_t rc = dds_delete(condition);
  if (rc < 0) { fail(env, "Delete query condition", rc); return; }
  struct filter_context *ctx = atomic_exchange(&query_filters[slot], NULL);
  if (ctx) { (*env)->DeleteGlobalRef(env, ctx->filter); free(ctx); }
  atomic_store(&query_used[slot], false);
}
JNIEXPORT jlong JNICALL JNI_METHOD(topicFilter)(JNIEnv *env, jclass cls, jint topic, jobject filter) {
  UNUSED_CLASS;
  struct filter_context *ctx = new_filter(env, filter);
  if (!ctx) return 0;
  dds_return_t rc = dds_set_topic_filter_and_arg(topic, filter_sample, ctx);
  if (rc < 0) { (*env)->DeleteGlobalRef(env, ctx->filter); free(ctx); fail(env, "Set topic filter", rc); return 0; }
  return (jlong) (intptr_t) ctx;
}
JNIEXPORT void JNICALL JNI_METHOD(filterFree)(JNIEnv *env, jclass cls, jlong context) {
  UNUSED_CLASS;
  struct filter_context *ctx = (void *) (intptr_t) context;
  if (ctx) { (*env)->DeleteGlobalRef(env, ctx->filter); free(ctx); }
}

JNIEXPORT jint JNICALL JNI_METHOD(takeStatus)(JNIEnv *env, jclass cls, jint entity, jint mask) {
  UNUSED_CLASS;
  uint32_t status = 0;
  dds_return_t rc = dds_take_status(entity, &status, (uint32_t) mask);
  if (rc < 0) fail(env, "dds_take_status", rc);
  return (jint) status;
}
