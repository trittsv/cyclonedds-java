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


#include <string.h>
#include <stdio.h>
#include <limits.h>
#include "typesupport.h"
#include "dds/ddsrt/heap.h"
#include "dds/ddsrt/string.h"
#include "dds/ddsrt/endian.h"
#include "dds/ddsrt/md5.h"
#include "dds/ddsrt/sync.h"
#include "dds/ddsi/ddsi_serdata.h"
#include "dds/ddsi/ddsi_radmin.h"
#include "dds/ddsi/ddsi_typelib.h"
#include "dds/ddsi/ddsi_typebuilder.h"

#ifndef DDS_HAS_TYPELIB
#error Cyclone DDS Java requires Cyclone DDS type library support
#endif

struct java_type {
  struct ddsi_sertype base;
  unsigned char *info, *map;
  uint32_t info_size, map_size;
  struct dds_cdrstream_desc desc;
  uint8_t extensibility;
  bool ready;
};

struct java_data {
  struct ddsi_serdata base;
  unsigned char *bytes, *key;
  uint32_t size, key_size;
};

static const struct ddsi_serdata_ops data_ops;
static const struct ddsi_sertype_ops type_ops;
static ddsrt_once_t type_once = DDSRT_ONCE_INIT;
static ddsrt_mutex_t type_lock;
static void init_lock(void) { ddsrt_mutex_init(&type_lock); }
// Imported DLL data addresses are resolved at runtime on Windows.
#define CDR_ALLOCATOR (&dds_cdrstream_default_allocator)

static const struct java_type *as_type(const struct ddsi_sertype *t) { return (const struct java_type *) t; }
static const struct java_data *as_data(const struct ddsi_serdata *d) { return (const struct java_data *) d; }

static void digest(const void *bytes, uint32_t size, unsigned char out[16]) {
  ddsrt_md5_state_t state;
  ddsrt_md5_init(&state);
  if (size) ddsrt_md5_append(&state, bytes, size);
  ddsrt_md5_finish(&state, out);
}

static struct java_data *new_data(const struct ddsi_sertype *type, enum ddsi_serdata_kind kind, size_t size) {
  if (size > UINT32_MAX - 3) return NULL;
  struct java_data *d = ddsrt_calloc(1, sizeof(*d));
  if (!d) return NULL;
  /* RTPS may ask for the final word including padding. Keep it initialized. */
  d->bytes = ddsrt_calloc(1, size ? (size + 3) & ~(size_t)3 : 4);
  if (!d->bytes) { ddsrt_free(d); return NULL; }
  d->size = (uint32_t) size;
  ddsi_serdata_init(&d->base, type, kind);
  return d;
}

static void data_free(struct ddsi_serdata *base) {
  struct java_data *d = (struct java_data *) base;
  ddsrt_free(d->bytes); ddsrt_free(d->key); ddsrt_free(d);
}

static struct ddsi_serdata *finish_data(struct java_data *d) {
  const struct java_type *t = as_type(d->base.type);
  unsigned char *normalized = NULL;
  if (!t->ready || d->size < 4 || d->bytes[0] != 0 ||
      (d->bytes[1] > 11 || (d->bytes[1] > 3 && d->bytes[1] < 6)) || d->bytes[2] || (d->bytes[3] & 0xfc)) goto invalid;
  const enum dds_cdr_enc_version version = d->bytes[1] < 4 ? DDSI_RTPS_CDR_ENC_VERSION_1 : DDSI_RTPS_CDR_ENC_VERSION_2;
  if (d->base.kind == SDK_DATA && (version == DDSI_RTPS_CDR_ENC_VERSION_2
      ? (d->bytes[1] - 6) / 2 != t->extensibility : (d->bytes[1] >= 2) != (t->extensibility == 2))) goto invalid;
  uint32_t padding = d->bytes[3] & 3;
  if (padding > d->size - 4) goto invalid;
#if DDSRT_ENDIAN == DDSRT_LITTLE_ENDIAN
  bool swap = !(d->bytes[1] & 1);
#else
  bool swap = (d->bytes[1] & 1) != 0;
#endif
  /* Normalization can patch try-construct values in place. Keep the received
     representation for Java's recovery codecs (including explicit @default),
     and use the validated native-order copy solely for key extraction. */
  normalized = ddsrt_malloc(d->size - 4);
  if (!normalized) goto invalid;
  memcpy(normalized, d->bytes + 4, d->size - 4);
  uint32_t actual;
  if (dds_stream_normalize(normalized, d->size - 4 - padding, swap,
      version, &t->desc, d->base.kind == SDK_KEY, &actual) != DDS_STREAM_NORMALIZE_SUCCESS) goto invalid;
  dds_istream_t is;
  dds_istream_init_well_formed(&is, actual, normalized, version);
  dds_ostream_t os;
  dds_ostream_init(&os, CDR_ALLOCATOR, 0, DDSI_RTPS_CDR_ENC_VERSION_2);
  bool ok;
  if (d->base.kind == SDK_KEY) {
    dds_stream_extract_key_from_key(&is, &os, DDS_CDR_KEY_SERIALIZATION_SAMPLE, CDR_ALLOCATOR, &t->desc);
    ok = true;
  } else ok = dds_stream_extract_key_from_data(&is, &os, CDR_ALLOCATOR, &t->desc);
  if (ok) {
    d->key_size = os.m_index;
    d->key = ddsrt_malloc(d->key_size ? d->key_size : 1);
    if (!d->key) ok = false;
    else if (d->key_size) memcpy(d->key, os.m_buffer, d->key_size);
  }
  dds_ostream_fini(&os, CDR_ALLOCATOR);
  if (!ok) goto invalid;
  unsigned char hash[16];
  digest(d->key, d->key_size, hash);
  uint32_t key_hash;
  memcpy(&key_hash, hash, sizeof(key_hash));
  d->base.hash = d->base.type->serdata_basehash ^ key_hash;
  ddsrt_free(normalized);
  return &d->base;
invalid:
  ddsrt_free(normalized);
  data_free(&d->base);
  return NULL;
}

static struct ddsi_serdata *from_iov(const struct ddsi_sertype *type, enum ddsi_serdata_kind kind,
    ddsrt_msg_iovlen_t count, const ddsrt_iovec_t *iov, size_t size) {
  struct java_data *d = new_data(type, kind, size);
  if (!d) return NULL;
  size_t offset = 0;
  for (ddsrt_msg_iovlen_t i = 0; i < count && offset < size; i++) {
    size_t n = iov[i].iov_len < size - offset ? iov[i].iov_len : size - offset;
    memcpy(d->bytes + offset, iov[i].iov_base, n);
    offset += n;
  }
  if (offset != size) { data_free(&d->base); return NULL; }
  return finish_data(d);
}

static struct ddsi_serdata *from_fragments(const struct ddsi_sertype *type, enum ddsi_serdata_kind kind,
    const struct ddsi_rdata *fragment, size_t size) {
  struct java_data *d = new_data(type, kind, size);
  if (!d) return NULL;
  size_t offset = 0;
  for (; fragment && offset < size; fragment = fragment->nextfrag) {
    if (fragment->min > offset) break;
    if (fragment->maxp1 <= offset) continue;
    size_t end = fragment->maxp1 < size ? fragment->maxp1 : size;
    const unsigned char *payload = DDSI_RMSG_PAYLOADOFF(fragment->rmsg, DDSI_RDATA_PAYLOAD_OFF(fragment));
    memcpy(d->bytes + offset, payload + offset - fragment->min, end - offset);
    offset = end;
  }
  if (offset != size) { data_free(&d->base); return NULL; }
  return finish_data(d);
}

static struct ddsi_serdata *from_sample(const struct ddsi_sertype *type, enum ddsi_serdata_kind kind, const void *sample) {
  const struct java_sample *s = sample;
  ddsrt_iovec_t iov = {.iov_base = s->bytes, .iov_len = s->size};
  enum ddsi_serdata_kind input_kind = s->kind == SDK_EMPTY ? kind : s->kind;
  struct ddsi_serdata *data = from_iov(type, input_kind, 1, &iov, s->size);
  if (data && kind == SDK_KEY && input_kind == SDK_DATA) {
    struct ddsi_serdata *key = data->ops->to_untyped(data);
    ddsi_serdata_unref(data);
    return key;
  }
  return data;
}

static struct ddsi_serdata *from_keyhash(const struct ddsi_sertype *type, const struct ddsi_keyhash *hash) {
  const struct java_type *t = as_type(type);
  /* Variable/large keys are MD5 digests and cannot be reversed. */
  if (!(t->desc.flagset & DDS_TOPIC_FIXED_KEY_XCDR2)) return NULL;
  struct java_data *d = new_data(type, SDK_KEY, 20);
  if (!d) return NULL;
  d->bytes[1] = 6; /* keyhash is big-endian */
  memcpy(d->bytes + 4, hash->value, 16);
  return finish_data(d);
}

static bool eqkey(const struct ddsi_serdata *a, const struct ddsi_serdata *b) {
  const struct java_data *x = as_data(a), *y = as_data(b);
  return x->key_size == y->key_size && (!x->key_size || memcmp(x->key, y->key, x->key_size) == 0);
}
static uint32_t data_size(const struct ddsi_serdata *d) { return as_data(d)->size; }
static void to_ser(const struct ddsi_serdata *d, size_t offset, size_t size, void *out) {
  memcpy(out, as_data(d)->bytes + offset, size);
}
static struct ddsi_serdata *to_ref(const struct ddsi_serdata *d, size_t offset, size_t size, ddsrt_iovec_t *ref) {
  ref->iov_base = as_data(d)->bytes + offset;
  ref->iov_len = (ddsrt_iov_len_t) size;
  return ddsi_serdata_ref(d);
}
static void release_ref(struct ddsi_serdata *d, const ddsrt_iovec_t *ref) { (void) ref; ddsi_serdata_unref(d); }

static bool to_sample(const struct ddsi_serdata *d, void *sample, void **buffer, void *limit) {
  if (buffer || limit) return false;
  struct java_sample *s = sample;
  unsigned char *bytes = ddsrt_malloc(as_data(d)->size ? as_data(d)->size : 1);
  if (!bytes) return false;
  memcpy(bytes, as_data(d)->bytes, as_data(d)->size);
  ddsrt_free(s->bytes);
  s->bytes = bytes; s->size = as_data(d)->size; s->kind = d->kind;
  return true;
}

static struct ddsi_serdata *to_untyped(const struct ddsi_serdata *base) {
  const struct java_data *d = as_data(base);
  struct java_data *key = new_data(base->type, SDK_KEY, 4 + d->key_size);
  if (!key) return NULL;
#if DDSRT_ENDIAN == DDSRT_LITTLE_ENDIAN
  key->bytes[1] = 7;
#else
  key->bytes[1] = 6;
#endif
  if (d->key_size) memcpy(key->bytes + 4, d->key, d->key_size);
  key->key = ddsrt_malloc(d->key_size ? d->key_size : 1);
  if (!key->key) { data_free(&key->base); return NULL; }
  if (d->key_size) memcpy(key->key, d->key, d->key_size);
  key->key_size = d->key_size;
  key->base.hash = base->hash;
  return &key->base;
}
static bool untyped_to_sample(const struct ddsi_sertype *type, const struct ddsi_serdata *data,
    void *sample, void **buffer, void *limit) {
  (void) type;
  return to_sample(data, sample, buffer, limit);
}

static size_t print_data(const struct ddsi_sertype *type, const struct ddsi_serdata *data, char *out, size_t size) {
  return (size_t) snprintf(out, size, "%s: %u CDR bytes", type->type_name, as_data(data)->size);
}
static void keyhash(const struct ddsi_serdata *base, struct ddsi_keyhash *out, bool force_md5) {
  const struct java_data *d = as_data(base);
  const struct java_type *t = as_type(base->type);
  memset(out->value, 0, 16);
  if (!base->type->has_key) return;
  dds_istream_t is;
  dds_istream_init_well_formed(&is, d->key_size, d->key, DDSI_RTPS_CDR_ENC_VERSION_2);
  dds_ostreamBE_t os;
  dds_ostreamBE_init(&os, CDR_ALLOCATOR, 16, DDSI_RTPS_CDR_ENC_VERSION_2);
  dds_stream_extract_keyBE_from_key(&is, &os, DDS_CDR_KEY_SERIALIZATION_KEYHASH, CDR_ALLOCATOR, &t->desc);
  if (force_md5 || !(t->desc.flagset & DDS_TOPIC_FIXED_KEY_XCDR2)) digest(os.x.m_buffer, os.x.m_index, out->value);
  else if (os.x.m_index <= 16) memcpy(out->value, os.x.m_buffer, os.x.m_index);
  dds_ostreamBE_fini(&os, CDR_ALLOCATOR);
}

static void type_free(struct ddsi_sertype *base) {
  struct java_type *t = (struct java_type *) base;
  if (t->ready) dds_cdrstream_desc_fini(&t->desc, CDR_ALLOCATOR);
  ddsrt_free(t->info); ddsrt_free(t->map);
  ddsi_sertype_fini(base); ddsrt_free(t);
}
static void zero_samples(const struct ddsi_sertype *type, void *samples, size_t count) {
  (void) type; memset(samples, 0, count * sizeof(struct java_sample));
}
static void realloc_samples(void **pointers, const struct ddsi_sertype *type, void *old, size_t old_count, size_t count) {
  (void) type;
  struct java_sample *samples = ddsrt_realloc(old, count * sizeof(*samples));
  if (!samples && count) { for (size_t i = 0; i < count; i++) pointers[i] = NULL; return; }
  if (count > old_count) memset(samples + old_count, 0, (count - old_count) * sizeof(*samples));
  for (size_t i = 0; i < count; i++) pointers[i] = samples + i;
}
static void free_samples(const struct ddsi_sertype *type, void **pointers, size_t count, dds_free_op_t op) {
  (void) type;
  if (op & DDS_FREE_CONTENTS_BIT) for (size_t i = 0; i < count; i++) {
    struct java_sample *s = pointers[i];
    if (s) { ddsrt_free(s->bytes); s->bytes = NULL; s->size = 0; }
  }
  if (count && (op & DDS_FREE_ALL_BIT)) ddsrt_free(pointers[0]);
}
static bool type_equal(const struct ddsi_sertype *a, const struct ddsi_sertype *b) {
  const struct java_type *x = as_type(a), *y = as_type(b);
  return x->base.allowed_data_representation == y->base.allowed_data_representation &&
    x->info_size == y->info_size && x->map_size == y->map_size &&
    memcmp(x->info, y->info, x->info_size) == 0 && memcmp(x->map, y->map, x->map_size) == 0;
}
static uint32_t type_hash(const struct ddsi_sertype *type) {
  unsigned char hash[16]; uint32_t result;
  digest(as_type(type)->info, as_type(type)->info_size, hash);
  memcpy(&result, hash, sizeof(result)); return result;
}
static ddsi_typeinfo_t *type_info(const struct ddsi_sertype *type) {
  return ddsi_typeinfo_deser(as_type(type)->info, as_type(type)->info_size);
}
static ddsi_typemap_t *type_map(const struct ddsi_sertype *type) {
  return ddsi_typemap_deser(as_type(type)->map, as_type(type)->map_size);
}
static ddsi_typeid_t *type_id(const struct ddsi_sertype *type, ddsi_typeid_kind_t kind) {
  ddsi_typeinfo_t *info = type_info(type);
  if (!info) return NULL;
  ddsi_typeid_t *id = ddsi_typeinfo_typeid(info, kind);
  ddsi_typeinfo_free(info); return id;
}
static struct ddsi_sertype *derive(const struct ddsi_sertype *type, dds_data_representation_id_t representation,
    dds_type_consistency_enforcement_qospolicy_t consistency) {
  (void) consistency;
  return representation == DDS_DATA_REPRESENTATION_XCDR1 || representation == DDS_DATA_REPRESENTATION_XCDR2 ? (struct ddsi_sertype *) type : NULL;
}
static dds_return_t serialized_size(const struct ddsi_sertype *type, enum ddsi_serdata_kind kind,
    const void *sample, size_t *size, uint16_t *identifier) {
  (void) type; (void) kind;
  const struct java_sample *s = sample;
  if (s->size < 4) return DDS_RETCODE_BAD_PARAMETER;
  *size = s->size - 4;
  memcpy(identifier, s->bytes, 2);
  return DDS_RETCODE_OK;
}
static bool serialize_into(const struct ddsi_sertype *type, enum ddsi_serdata_kind kind,
    const void *sample, void *destination, size_t size) {
  (void) type; (void) kind;
  const struct java_sample *s = sample;
  if (s->size < 4 || size < s->size - 4) return false;
  memcpy(destination, s->bytes + 4, s->size - 4); return true;
}

static const struct ddsi_serdata_ops data_ops = {
  .eqkey = eqkey, .get_size = data_size, .from_ser = from_fragments, .from_ser_iov = from_iov,
  .from_keyhash = from_keyhash, .from_sample = from_sample, .to_ser = to_ser, .to_ser_ref = to_ref,
  .to_ser_unref = release_ref, .to_sample = to_sample, .to_untyped = to_untyped,
  .untyped_to_sample = untyped_to_sample, .free = data_free, .print = print_data, .get_keyhash = keyhash
};
static const struct ddsi_sertype_ops type_ops = {
  .version = ddsi_sertype_v0, .free = type_free, .zero_samples = zero_samples,
  .realloc_samples = realloc_samples, .free_samples = free_samples, .equal = type_equal,
  .hash = type_hash, .type_id = type_id, .type_map = type_map, .type_info = type_info,
  .derive_sertype = derive, .get_serialized_size = serialized_size, .serialize_into = serialize_into
};

static dds_return_t init_descriptor(struct java_type *t) {
  if (t->ready) return DDS_RETCODE_OK;
  ddsi_typeinfo_t *info = type_info(&t->base);
  if (!info) return DDS_RETCODE_BAD_PARAMETER;
  struct ddsi_domaingv *gv = ddsrt_atomic_ldvoidp(&t->base.gv);
  const ddsi_typeid_t *id = ddsi_typeinfo_complete_typeid(info);
  const struct ddsi_type *type = gv && id ? ddsi_type_lookup(gv, id) : NULL;
  dds_topic_descriptor_t desc = {0};
  dds_return_t rc = type ? ddsi_topic_descriptor_from_type(gv, &desc, type) : DDS_RETCODE_BAD_PARAMETER;
  if (rc == DDS_RETCODE_OK) {
    t->extensibility = (desc.m_ops[0] & DDS_OP_MASK) == DDS_OP_PLC ? 2 : (desc.m_ops[0] & DDS_OP_MASK) == DDS_OP_DLC ? 1 : 0;
    if ((desc.m_nkeys != 0) != (t->base.has_key != 0))
      rc = DDS_RETCODE_BAD_PARAMETER;
    else rc = dds_cdrstream_desc_init_with_nops(&t->desc, CDR_ALLOCATOR, desc.m_size, desc.m_align,
        desc.m_flagset, desc.m_ops, desc.m_nops, desc.m_keys, desc.m_nkeys);
    ddsi_topic_descriptor_fini(&desc);
    t->ready = rc == DDS_RETCODE_OK;
  }
  ddsi_typeinfo_free(info);
  return rc;
}

dds_entity_t java_create_topic(dds_entity_t participant, const char *topic_name, const char *type_name,
    bool has_key, uint32_t representations, const unsigned char *info, uint32_t info_size, const unsigned char *map, uint32_t map_size, const dds_qos_t *qos) {
  if (!representations || (representations & ~DDS_DATA_REPRESENTATION_RESTRICT_DEFAULT))
    return DDS_RETCODE_BAD_PARAMETER;
  /* Blob deserialization validates buffers before the DDS registration machinery sees them. */
  ddsi_typeinfo_t *checked_info = ddsi_typeinfo_deser(info, info_size);
  ddsi_typemap_t *checked_map = ddsi_typemap_deser(map, map_size);
  if (!checked_info || !checked_map) {
    if (checked_info) ddsi_typeinfo_free(checked_info);
    if (checked_map) { ddsi_typemap_fini(checked_map); ddsrt_free(checked_map); }
    return DDS_RETCODE_BAD_PARAMETER;
  }
  ddsi_typeinfo_free(checked_info);
  ddsi_typemap_fini(checked_map);
  ddsrt_free(checked_map);
  struct java_type *t = ddsrt_calloc(1, sizeof(*t));
  if (!t) return DDS_RETCODE_OUT_OF_RESOURCES;
  t->info = ddsrt_memdup(info, info_size); t->info_size = info_size;
  t->map = ddsrt_memdup(map, map_size); t->map_size = map_size;
  if (!t->info || !t->map) { ddsrt_free(t->info); ddsrt_free(t->map); ddsrt_free(t); return DDS_RETCODE_OUT_OF_RESOURCES; }
  ddsi_sertype_init_props(&t->base, type_name, &type_ops, &data_ops, sizeof(struct java_sample),
      has_key ? DDS_DATA_TYPE_CONTAINS_KEY : 0, representations, 0);
  struct ddsi_sertype *base = &t->base;
  /* Registration may intern an existing type from a different participant. Initialize
     its descriptor only once, before exposing the topic to endpoint creation. */
  ddsrt_once(&type_once, init_lock);
  ddsrt_mutex_lock(&type_lock);
  dds_entity_t topic = dds_create_topic_sertype(participant, topic_name, &base, qos, NULL, NULL);
  if (topic < 0) ddsi_sertype_unref(base);
  else {
    dds_return_t rc = init_descriptor((struct java_type *) base);
    if (rc != DDS_RETCODE_OK) { dds_delete(topic); topic = rc; }
  }
  ddsrt_mutex_unlock(&type_lock);
  return topic;
}

/* Reuse the core's descriptor interpreter for nested keys and declaration order.
   Start from the holder, so non-key fields survive getKeyValue(holder, handle). */
bool java_merge_key(const struct ddsi_sertype *type, const struct java_sample *key, struct java_sample *value) {
  const struct java_type *t = as_type(type);
  ddsrt_iovec_t key_iov = {.iov_base = key->bytes, .iov_len = key->size};
  struct ddsi_serdata *key_sd = from_iov(type, SDK_KEY, 1, &key_iov, key->size);
  if (!key_sd) return false; /* A foreign handle must never reach the unchecked stream reader. */
  struct ddsi_serdata *sd = ddsi_serdata_from_sample(type, SDK_DATA, value);
  if (!sd) { ddsi_serdata_unref(key_sd); return false; }
  void *sample = ddsrt_calloc(1, t->desc.size);
  if (!sample) { ddsi_serdata_unref(sd); ddsi_serdata_unref(key_sd); return false; }
  const struct java_data *merge_data[] = {as_data(sd), as_data(key_sd)};
  for (size_t i = 0; i < 2; i++) {
    const struct java_data *v = merge_data[i]; uint32_t actual;
#if DDSRT_ENDIAN == DDSRT_LITTLE_ENDIAN
    bool swap = !(v->bytes[1] & 1);
#else
    bool swap = (v->bytes[1] & 1) != 0;
#endif
    if (dds_stream_normalize(v->bytes + 4, v->size - 4 - (v->bytes[3] & 3), swap,
        v->bytes[1] < 4 ? DDSI_RTPS_CDR_ENC_VERSION_1 : DDSI_RTPS_CDR_ENC_VERSION_2, &t->desc, i == 1, &actual) != DDS_STREAM_NORMALIZE_SUCCESS) {
      ddsrt_free(sample); ddsi_serdata_unref(sd); ddsi_serdata_unref(key_sd); return false;
    }
  }
  dds_istream_t is;
  dds_istream_init_well_formed(&is, as_data(sd)->size - 4, as_data(sd)->bytes + 4,
      as_data(sd)->bytes[1] < 4 ? DDSI_RTPS_CDR_ENC_VERSION_1 : DDSI_RTPS_CDR_ENC_VERSION_2);
  dds_stream_read_sample(&is, sample, CDR_ALLOCATOR, &t->desc);
  dds_istream_init_well_formed(&is, as_data(key_sd)->size - 4, as_data(key_sd)->bytes + 4, DDSI_RTPS_CDR_ENC_VERSION_2);
  dds_stream_read_key(&is, sample, CDR_ALLOCATOR, &t->desc);
  dds_ostream_t os;
  dds_ostream_init(&os, CDR_ALLOCATOR, 0, DDSI_RTPS_CDR_ENC_VERSION_2);
  bool ok = dds_stream_write_sample(&os, CDR_ALLOCATOR, sample, &t->desc);
  if (ok) {
    unsigned char *bytes = ddsrt_malloc(4 + os.m_index);
    if (!bytes) { dds_ostream_fini(&os, CDR_ALLOCATOR); dds_stream_free_sample(sample, CDR_ALLOCATOR, t->desc.ops.ops); ddsrt_free(sample); ddsi_serdata_unref(sd); ddsi_serdata_unref(key_sd); return false; }
    memset(bytes, 0, 4);
#if DDSRT_ENDIAN == DDSRT_LITTLE_ENDIAN
    bytes[1] = 7 + 2 * t->extensibility;
#else
    bytes[1] = 6 + 2 * t->extensibility;
#endif
    memcpy(bytes + 4, os.m_buffer, os.m_index);
    value->bytes = bytes; value->size = 4 + os.m_index;
  }
  dds_ostream_fini(&os, CDR_ALLOCATOR);
  dds_stream_free_sample(sample, CDR_ALLOCATOR, t->desc.ops.ops);
  ddsrt_free(sample); ddsi_serdata_unref(sd); ddsi_serdata_unref(key_sd);
  return ok;
}
