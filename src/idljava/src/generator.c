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

/*
 * Java backend for Cyclone's IDL compiler. Uses the same AST and language-neutral
 * typeinfo/typemap generator hook as idlcxx. No application-specific C is emitted.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>
#include <ctype.h>
#include "idlc/generator.h"
#include "idl/processor.h"
#include "idl/file.h"
#include "idl/version.h"

/* Match idlcxx's representation restrictions without private core exports.
   Compact enum XCDR1 streams are additionally excluded because the pinned core
   reads their compact width instead of XCDR1's required four-byte width. */
static bool core_xcdr1_restricted(const void *node, const void **seen, size_t depth, bool bounded) {
  if (depth == 128) return true;
  node = idl_strip(node, IDL_STRIP_ALIASES | IDL_STRIP_FORWARD);
  /* Check framing before the recursion guard: a recursive sequence edge loses
     the boundary even if the same type was already visited at top level. */
  if ((idl_is_struct(node) || idl_is_union(node)) && idl_is_extensible(node, IDL_APPENDABLE) && !bounded) return true;
  for (size_t i = 0; i < depth; i++) if (seen[i] == node) return false;
  seen[depth++] = node;
  if (idl_is_enum(node)) return ((const idl_enum_t *)node)->bit_bound.value < 32;
  if (idl_is_sequence(node)) return core_xcdr1_restricted(idl_type_spec(node), seen, depth, false);
  if (idl_is_struct(node)) {
    const idl_struct_t *type = node;
    if (type->inherit_spec && ((const idl_struct_t *)type->inherit_spec->base)->extensibility.value == IDL_APPENDABLE) return true;
    if (type->inherit_spec && core_xcdr1_restricted(idl_type_spec(type->inherit_spec), seen, depth, false)) return true;
    for (const idl_member_t *m = type->members; m; m = idl_next(m)) {
      bool array = false;
      for (const idl_declarator_t *d = m->declarators; d; d = idl_next(d)) if (idl_is_array(d)) array = true;
      if (core_xcdr1_restricted(idl_type_spec(m), seen, depth, m->optional.value && !array)) return true;
    }
  } else if (idl_is_union(node)) {
    const idl_union_t *type = node;
    if (type->extensibility.value == IDL_MUTABLE) return true;
    if (core_xcdr1_restricted(idl_type_spec(type->switch_type_spec), seen, depth, false)) return true;
    for (const idl_case_t *c = type->cases; c; c = idl_next(c))
      if (core_xcdr1_restricted(idl_type_spec(c), seen, depth, false)) return true;
  }
  return false;
}
static uint32_t representations(const void *node) {
  uint32_t flags = idl_allowable_data_representations(node) & 5u;
  const void *seen[128];
  if (idl_requires_xcdr2(node) || core_xcdr1_restricted(node, seen, 0, true)) flags &= ~1u;
  return flags;
}

/* Prefix reserved Java identifiers and internal generated names. */
struct escaped_name { char *name; struct escaped_name *next; };
static struct escaped_name *escaped_names;
static bool allocation_failed;
static const char *java_name(const char *name) {
  static const char *keywords[] = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "_", "record", "sealed", "permits", "yield", "var", NULL};
  bool needs_escape = strcmp(name, "Constants") == 0 || (strlen(name) >= 8 && strcmp(name + strlen(name) - 8, "Abstract") == 0) || name[0] == '_' || strcmp(name, "serialVersionUID") == 0 || strcmp(name, "java") == 0 || strcmp(name, "org") == 0;
  for (size_t i = 0; !needs_escape && keywords[i]; i++) needs_escape = strcmp(name, keywords[i]) == 0;
  if (needs_escape) {
    char *escaped = malloc(strlen(name) + 2);
    struct escaped_name *entry = malloc(sizeof(*entry));
    if (!escaped || !entry) { free(escaped); free(entry); allocation_failed = true; return name; }
    sprintf(escaped, "_%s", name);
    entry->name = escaped; entry->next = escaped_names; escaped_names = entry;
    return escaped;
  }
  return name;
}

/* Mapping options inherit through enclosing IDL scopes. */
static const idl_node_t *mapping_context;
static const void *annotation_argument(const idl_annotation_appl_t *, const char *);
static const void *mapping_option(const idl_node_t *node, const char *option) {
  for (const idl_node_t *n = node; n; n = n->parent)
    for (const idl_annotation_appl_t *a = n->annotations; a; a = (const idl_annotation_appl_t *) a->node.next)
      if (strcmp(idl_identifier(a->annotation), "java_mapping") == 0) {
        const void *value = annotation_argument(a, option);
        if (value) return value;
      }
  return NULL;
}
static bool promote_width(void) {
  const idl_literal_t *v = mapping_option(mapping_context, "promote_integer_width");
  return v && v->value.bln;
}
static const char *string_type(void) {
  const idl_literal_t *v = mapping_option(mapping_context, "string_type");
  return v ? v->value.str : "java.lang.String";
}
static bool java_naming(const idl_node_t *node) {
  const void *v = mapping_option(node, "apply_naming_convention");
  return v && idl_is_enumerator(v) && strcmp(idl_identifier(v), "JAVA_NAMING_CONVENTION") == 0;
}
static const char *transform_name(const char *name, int style) {
  char *result = malloc(strlen(name) + 2);
  struct escaped_name *entry = malloc(sizeof(*entry));
  if (!result || !entry) { free(result); free(entry); allocation_failed = true; return name; }
  size_t at = 0; bool upper = style == 2;
  for (const unsigned char *p = (const unsigned char *) name; *p; p++) {
    if ((style == 2 || style == 3) && *p == '_') { upper = true; continue; }
    result[at++] = (char) (style == 0 ? tolower(*p) : style == 1 || upper ? toupper(*p) : *p);
    upper = false;
  }
  result[at] = 0;
  if (style == 3 && at) result[0] = (char) tolower((unsigned char) result[0]);
  entry->name = result; entry->next = escaped_names; escaped_names = entry;
  return result;
}
static const char *mapped_name(const void *ptr) {
  const idl_node_t *node = ptr;
  const char *name = idl_identifier(node);
  if (java_naming(node)) name = transform_name(name, idl_is_module(node) ? 0 : idl_is_enumerator(node) || idl_is_const(node) ? 1 : idl_is_declarator(node) ? 3 : 2);
  return java_name(name);
}

static char *qualified(const idl_node_t *node, const char *separator, bool escape) {
  const idl_node_t *parts[128];
  size_t count = 0, size = 1;
  for (const idl_node_t *p = node; p; p = p->parent) {
    if (!idl_is_module(p) && !idl_is_struct(p) && !idl_is_enum(p) && !idl_is_union(p) && !(idl_mask(p) & IDL_ANNOTATION)) continue;
    if (count == 128) return NULL;
    parts[count++] = p;
    size += strlen(idl_identifier(p)) + 6 + strlen(separator);
  }
  char *result = calloc(size, 1);
  if (!result) return NULL;
  for (size_t i = count; i > 0; i--) {
    if (i != count) strcat(result, separator);
    const char *name = idl_identifier(parts[i-1]);
    strcat(result, escape ? mapped_name(parts[i-1]) : name);
  }
  return result;
}

static const void *resolved(const void *type) { return idl_strip(type, IDL_STRIP_ALIASES | IDL_STRIP_FORWARD); }

struct array_shape { const void *base; uint32_t sizes[32]; size_t count; bool valid; };
static struct array_shape shape(const void *spec) {
  struct array_shape result = { .base = spec, .valid = true };
  while (idl_is_alias(spec) || idl_is_array(spec)) {
    if (idl_is_array(spec)) {
      const idl_declarator_t *decl = spec;
      for (const idl_node_t *dim = decl->const_expr; dim; dim = dim->next) {
        uint32_t size = ((const idl_literal_t *) dim)->value.uint32;
        if (result.count == 32 || size > INT32_MAX) { result.valid = false; return result; }
        result.sizes[result.count++] = size;
      }
    }
    spec = idl_type_spec(spec);
  }
  result.base = resolved(spec); return result;
}
static const void *member_spec(const idl_member_t *member, const idl_declarator_t *decl) {
  return idl_is_array(decl) ? decl : member->type_spec;
}
static const char *primitive(const void *type, bool method) {
  if (promote_width()) switch (idl_type(type)) {
    case IDL_UINT8: return method ? "Short" : "short";
    case IDL_USHORT: case IDL_UINT16: return method ? "Int" : "int";
    case IDL_ULONG: case IDL_UINT32: return method ? "Long" : "long";
    case IDL_ULLONG: case IDL_UINT64: return method ? "BigInteger" : "java.math.BigInteger";
    default: break;
  }
  switch (idl_type(type)) {
    case IDL_BOOL: return method ? "Boolean" : "boolean";
    case IDL_CHAR: return method ? "Char" : "char";
    case IDL_WCHAR: return method ? "Wchar" : "char";
    case IDL_OCTET: case IDL_INT8: case IDL_UINT8: return method ? "Byte" : "byte";
    case IDL_SHORT: case IDL_USHORT: case IDL_INT16: case IDL_UINT16: return method ? "Short" : "short";
    case IDL_LONG: case IDL_ULONG: case IDL_INT32: case IDL_UINT32: return method ? "Int" : "int";
    case IDL_LLONG: case IDL_ULLONG: case IDL_INT64: case IDL_UINT64: return method ? "Long" : "long";
    case IDL_FLOAT: return method ? "Float" : "float";
    case IDL_DOUBLE: return method ? "Double" : "double";
    case IDL_STRING: case IDL_WSTRING: return method ? "String" : string_type();
    default: return NULL;
  }
}

static const char *sequence_name(const void *spec) {
  const char *method = primitive(resolved(spec), true);
  if (strcmp(method, "Wchar") == 0) return "Char";
  return strcmp(method, "Int") == 0 ? "Integer" : method;
}

static bool basic_sequence(const void *spec) {
  const void *type = resolved(spec);
  if (!idl_is_sequence(type)) return false;
  struct array_shape element = shape(((const idl_sequence_t *) type)->type_spec);
  return !(promote_width() && (idl_type(element.base) == IDL_ULLONG || idl_type(element.base) == IDL_UINT64)) && element.count == 0 && idl_is_base_type(element.base) && primitive(element.base, false);
}
static bool supported(const void *spec) {
  struct array_shape array = shape(spec);
  if (!array.valid) return false;
  const void *type = array.base;
  if (primitive(type, false) || idl_is_struct(type) || idl_is_enum(type) || idl_is_union(type)) return true;
  if (idl_is_sequence(type)) return supported(((const idl_sequence_t *) type)->type_spec);
  return false;
}
static void emit_type(FILE *out, const void *spec);
static void emit_array_type(FILE *out, struct array_shape array, size_t dimension) {
  emit_type(out, array.base);
  for (size_t i = dimension; i < array.count; i++) fputs("[]", out);
}
static void emit_type(FILE *out, const void *spec) {
  struct array_shape array = shape(spec);
  if (array.count) { emit_array_type(out, array, 0); return; }
  const void *type = array.base;
  const char *name = primitive(type, false);
  if (name) fputs(name, out);
  else if (idl_is_sequence(type)) {
    const void *element = ((const idl_sequence_t *) type)->type_spec;
    if (basic_sequence(type)) fprintf(out, "org.omg.type.%sSeq", sequence_name(element));
    else { fputs("java.util.List<", out); emit_type(out, element); fputc('>', out); }
  } else {
    char *scoped = qualified(type, ".", true);
    fputs(scoped, out); free(scoped);
  }
}
static void emit_class_type(FILE *out, const void *spec) {
  struct array_shape a = shape(spec);
  if (idl_is_sequence(a.base)) {
    if (basic_sequence(a.base)) fprintf(out, "org.omg.type.%sSeq", sequence_name(((const idl_sequence_t *) a.base)->type_spec));
    else fputs("java.util.List", out);
  } else emit_type(out, a.base);
  for (size_t i = 0; i < a.count; i++) fputs("[]", out);
}
static void emit_codec(FILE *out, const void *spec);
static void emit_default(FILE *out, const void *spec);
static void emit_recover_codec(FILE *out, const void *spec, idl_try_construct_t mode) {
  if (mode != IDL_DISCARD) fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.recover(", out);
  emit_codec(out, spec);
  if (mode != IDL_DISCARD) { fprintf(out, ", %d, () -> ", mode == IDL_TRIM ? 2 : 1); emit_default(out, spec); fputc(')', out); }
}
static void emit_array_codec(FILE *out, struct array_shape array, size_t dimension) {
  fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.<", out); emit_array_type(out, array, dimension);
  fputs(">array(", out);
  emit_class_type(out, array.base);
  for (size_t i = dimension + 1; i < array.count; i++) fputs("[]", out);
  fprintf(out, ".class, %"PRIu32", ", array.sizes[dimension]);
  if (dimension + 1 < array.count) emit_array_codec(out, array, dimension + 1);
  else emit_codec(out, array.base);
  fprintf(out, ", %s)", dimension == 0 && !idl_is_base_type(array.base) ? "true" : "false");
}
static void emit_codec(FILE *out, const void *spec) {
  struct array_shape array = shape(spec);
  if (array.count) { emit_array_codec(out, array, 0); return; }
  const void *type = array.base;
  const char *method = primitive(type, true);
  bool custom_string = (idl_is_string(type) || idl_type(type) == IDL_WSTRING) && strcmp(string_type(), "java.lang.String") != 0;
  if (custom_string) fprintf(out, "org.eclipse.cyclonedds.core.cdr.CdrCodecs.map(");
  if (idl_type(type) == IDL_WSTRING) fprintf(out, "org.eclipse.cyclonedds.core.cdr.CdrCodecs.wstring(%"PRId32")", (int32_t) ((const idl_string_t *) type)->maximum);
  else if (idl_is_string(type)) fprintf(out, "org.eclipse.cyclonedds.core.cdr.CdrCodecs.string(%"PRId32")", (int32_t) ((const idl_string_t *) type)->maximum);
  else if (method) {
    fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.", out);
    if (promote_width() && (idl_type(type) == IDL_UINT8 || idl_type(type) == IDL_USHORT || idl_type(type) == IDL_UINT16 || idl_type(type) == IDL_ULONG || idl_type(type) == IDL_UINT32 || idl_type(type) == IDL_ULLONG || idl_type(type) == IDL_UINT64)) {
      fputs(idl_type(type) == IDL_UINT8 ? "UINT8" : idl_type(type) == IDL_USHORT || idl_type(type) == IDL_UINT16 ? "UINT16" : idl_type(type) == IDL_ULONG || idl_type(type) == IDL_UINT32 ? "UINT32" : "UINT64", out);
    } else for (const char *p = method; *p; p++) fputc(*p >= 'a' && *p <= 'z' ? *p - 'a' + 'A' : *p, out);
  } else if (idl_is_sequence(type)) {
    const idl_sequence_t *seq = type;
    if (basic_sequence(type)) {
      const char *boxed = sequence_name(seq->type_spec);
      if (strcmp(boxed, "Char") == 0) boxed = "Character";
      fprintf(out, "org.eclipse.cyclonedds.core.cdr.CdrCodecs.<java.lang.%s, org.omg.type.%sSeq>primitiveSequence(", boxed, sequence_name(seq->type_spec));
      emit_recover_codec(out, seq->type_spec, seq->elem_try_construct.value);
      fprintf(out, ", %"PRId32", org.eclipse.cyclonedds.type.seq.%sSequence::new)", (int32_t) seq->maximum, sequence_name(seq->type_spec));
    } else {
      fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.sequence(", out); emit_recover_codec(out, seq->type_spec, seq->elem_try_construct.value);
      fprintf(out, ", %"PRId32", %s)", (int32_t) seq->maximum, idl_is_base_type(resolved(seq->type_spec)) ? "false" : "true");
    }
  } else {
    char *scoped = qualified(type, ".", true);
    if (idl_is_enum(type)) {
      fprintf(out, "org.eclipse.cyclonedds.core.cdr.CdrCodecs.<%s>enumeration(%"PRIu16", %s::getValue, %s::valueOf, () -> ", scoped, ((const idl_enum_t *) type)->bit_bound.value, scoped, scoped);
      if (((const idl_enum_t *) type)->extensibility.value == IDL_FINAL)
        fputs("{ throw new IllegalArgumentException(\"Unknown value of final enum\"); }", out);
      else emit_default(out, type);
      fputc(')', out);
    }
    else fprintf(out, "org.eclipse.cyclonedds.core.cdr.CdrCodecs.lazy(%s::$codec$%s)", scoped, mapped_name(type));
    free(scoped);
  }
  if (custom_string) fprintf(out, ", %s::new, java.lang.Object::toString)", string_type());
}
static void emit_write(FILE *out, const void *spec, const char *expression, const char *indent) {
  fputs(indent, out); emit_codec(out, spec); fprintf(out, ".write(w, %s);\n", expression);
}
static void emit_dimensions(FILE *out, struct array_shape array) {
  fputs("new int[]{", out);
  for (size_t i = 0; i < array.count; i++) fprintf(out, "%s%"PRIu32, i ? ", " : "", array.sizes[i]);
  fputc('}', out);
}
static void emit_default(FILE *out, const void *spec) {
  struct array_shape array = shape(spec);
  const void *type = array.base;
  if (array.count) {
    fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.<", out); emit_type(out, spec); fputs(">newArray(", out);
    emit_class_type(out, type); fputs(".class, ", out); emit_dimensions(out, array); fputs(", () -> ", out);
    emit_default(out, type); fputc(')', out);
  } else if (idl_is_string(type) || idl_type(type) == IDL_WSTRING) {
    if (strcmp(string_type(), "java.lang.String") != 0) fprintf(out, "new %s()", string_type()); else fputs("\"\"", out);
  }
  else if (idl_is_sequence(type)) {
    if (basic_sequence(type)) fprintf(out, "new org.eclipse.cyclonedds.type.seq.%sSequence()", sequence_name(((const idl_sequence_t *) type)->type_spec));
    else fputs("new java.util.ArrayList<>()", out);
  } else if (idl_is_struct(type) || idl_is_union(type)) { fputs("new ", out); emit_type(out, type); fputs("()", out); }
  else if (idl_is_enum(type)) {
    const idl_enum_t *en = type; char *scoped = qualified(type, ".", true);
    fprintf(out, "%s.%s", scoped, mapped_name(en->default_enumerator ? en->default_enumerator : en->enumerators)); free(scoped);
  } else if (promote_width() && (idl_type(type) == IDL_ULLONG || idl_type(type) == IDL_UINT64)) fputs("java.math.BigInteger.ZERO", out);
  else if (idl_type(type) == IDL_BOOL) fputs("false", out);
  else if (idl_type(type) == IDL_CHAR || idl_type(type) == IDL_WCHAR) fputs("(char) 0", out);
  else if (strcmp(primitive(type, false), "int") == 0) fputs("0", out);
  else { fprintf(out, "(%s) 0", primitive(type, false)); }
}

static void emit_hex(FILE *out, const unsigned char *bytes, size_t count) {
  /* Keep each string and concatenation below the class-file UTF8 constant limit. */
  fputs("java.util.HexFormat.of().parseHex(java.lang.String.join(\"\",\n", out);
  for (size_t start = 0; start < count; start += 1024) {
    fputs("            \"", out);
    size_t end = start + 1024 < count ? start + 1024 : count;
    for (size_t i = start; i < end; i++) fprintf(out, "%02x", bytes[i]);
    fprintf(out, "\"%s\n", end < count ? "," : "");
  }
  fputs("        ))", out);
}

/* Emit leaf copies using the same AST key-path predicate as the C descriptor backend.
   This preserves non-key fields even when appendable key deserialization defaults
   trailing fields in the core's generic stream interpreter. */
static void emit_key_copies(FILE *out, const idl_pstate_t *pstate, const idl_struct_t *topic,
    const idl_struct_t *node, const idl_node_t **nodes, size_t count, const char *access) {
  for (const idl_member_t *m = node->members; m; m = (const idl_member_t *) m->node.next)
    for (const idl_declarator_t *d = m->declarators; d; d = (const idl_declarator_t *) d->node.next) {
      nodes[count] = &m->node; nodes[count + 1] = &d->node;
      idl_path_t path = {.length = count + 2, .nodes = nodes};
      uint32_t order;
      if (idl_is_topic_key(topic, pstate->keylists, &path, &order) == IDL_KEYTYPE_NONE) continue;
      const char *member = mapped_name(d);
      struct array_shape array = shape(member_spec(m, d));
      if (!array.count && idl_is_struct(array.base) && count + 3 < 128) {
        size_t size = strlen(access) + strlen(member) + sizeof(".get_()");
        char *nested = malloc(size);
        if (!nested) { allocation_failed = true; return; }
        snprintf(nested, size, "%s.get_%s()", access, member);
        nodes[count + 2] = (const idl_node_t *) array.base;
        emit_key_copies(out, pstate, topic, array.base, nodes, count + 3, nested);
        free(nested);
      } else if (!array.count && idl_is_union(array.base) && ((const idl_union_t *) array.base)->switch_type_spec->key.value) {
        fputs("            ", out); emit_codec(out, array.base);
        fprintf(out, ".copyKeys(target%s.get_%s(), source%s.get_%s());\n", access, member, access, member);
      } else fprintf(out, "            target%s.set_%s(source%s.get_%s());\n", access, member, access, member);
    }
}

static bool used_as_base(const idl_node_t *nodes, const idl_struct_t *type) {
  for (const idl_node_t *n = nodes; n; n = n->next) {
    if (idl_is_module(n) && used_as_base(((const idl_module_t *) n)->definitions, type)) return true;
    if (idl_is_struct(n) && ((const idl_struct_t *) n)->inherit_spec && ((const idl_struct_t *) n)->inherit_spec->base == type) return true;
  }
  return false;
}
struct java_field { const idl_member_t *member; const idl_declarator_t *decl; };
static size_t fields_of(const idl_struct_t *node, struct java_field *fields, size_t count) {
  if (node->inherit_spec) count = fields_of(node->inherit_spec->base, fields, count);
  for (const idl_member_t *m = node->members; m; m = (const idl_member_t *) m->node.next)
    for (const idl_declarator_t *d = m->declarators; d; d = (const idl_declarator_t *) d->node.next) {
      if (count >= 1024) return 1025;
      fields[count++] = (struct java_field) {m, d};
    }
  return count;
}
static const char *boxed_type(const void *spec) {
  const char *p = primitive(spec, true);
  if (!p) return NULL;
  if (strcmp(p, "Int") == 0) return "java.lang.Integer";
  if (strcmp(p, "Char") == 0 || strcmp(p, "Wchar") == 0) return "java.lang.Character";
  if (strcmp(p, "Boolean") == 0) return "java.lang.Boolean";
  if (strcmp(p, "Byte") == 0) return "java.lang.Byte";
  if (strcmp(p, "Short") == 0) return "java.lang.Short";
  if (strcmp(p, "Long") == 0) return "java.lang.Long";
  if (strcmp(p, "Float") == 0) return "java.lang.Float";
  if (strcmp(p, "Double") == 0) return "java.lang.Double";
  return NULL;
}
static void emit_member_type(FILE *out, const idl_member_t *m, const idl_declarator_t *d) {
  mapping_context = &m->node;
  const void *spec = member_spec(m, d); struct array_shape a = shape(spec);
  const char *boxed = !a.count && (m->optional.value || m->external.value) ? boxed_type(a.base) : NULL;
  if (boxed) fputs(boxed, out); else emit_type(out, spec);
}
static void java_string(FILE *out, const char *str) {
  fputc('"', out);
  for (const unsigned char *p = (const unsigned char *) str; *p; p++) {
    if (*p == '"' || *p == '\\') fprintf(out, "\\%c", *p);
    else if (*p < 32) fprintf(out, "\\%03o", *p);
    else fputc(*p, out);
  }
  fputc('"', out);
}
static void emit_literal(FILE *out, const void *spec, const idl_literal_t *value) {
  const void *type = shape(spec).base;
  if (idl_is_string(type) || idl_type(type) == IDL_WSTRING) {
    bool custom = strcmp(string_type(), "java.lang.String") != 0;
    if (custom) fprintf(out, "new %s(", string_type());
    java_string(out, value->value.str); if (custom) fputc(')', out); return;
  }
  if (idl_is_enum(type)) {
    char *q = qualified(type, ".", true);
    if (idl_is_enumerator(value)) fprintf(out, "%s.%s", q, mapped_name(value));
    else fprintf(out, "%s.valueOf(%"PRId32")", q, value->value.int32);
    free(q); return;
  }
  if (idl_type(type) == IDL_BOOL) { fputs(value->value.bln ? "true" : "false", out); return; }
  if (idl_type(type) == IDL_FLOAT) { fprintf(out, "%aF", (double) value->value.flt); return; }
  if (idl_type(type) == IDL_DOUBLE) { fprintf(out, "%a", value->value.dbl); return; }
  uint64_t v;
  switch (idl_type(type)) {
    case IDL_CHAR: v = (unsigned char) value->value.chr; break;
    case IDL_WCHAR: v = value->value.uint16; break;
    case IDL_OCTET: case IDL_INT8: case IDL_UINT8: v = value->value.uint8; break;
    case IDL_SHORT: case IDL_USHORT: case IDL_INT16: case IDL_UINT16: v = value->value.uint16; break;
    case IDL_LONG: case IDL_ULONG: case IDL_INT32: case IDL_UINT32: v = value->value.uint32; break;
    default: v = value->value.uint64; break;
  }
  if (promote_width() && (idl_type(type) == IDL_ULLONG || idl_type(type) == IDL_UINT64)) fprintf(out, "new java.math.BigInteger(\"%"PRIu64"\")", v);
  else if (strcmp(primitive(type, false), "long") == 0) fprintf(out, "0x%"PRIx64"L", v);
  else fprintf(out, "(%s) 0x%"PRIx64"L", primitive(type, false), v);
}
static void emit_unsigned_check(FILE *out, const void *spec) {
  struct array_shape a = shape(spec);
  if (a.count || !promote_width()) return;
  switch (idl_type(a.base)) {
    case IDL_UINT8: fputs("        if (value < 0 || value > 255) throw new IllegalArgumentException(\"uint8 out of range\");\n", out); break;
    case IDL_USHORT: case IDL_UINT16: fputs("        if (value < 0 || value > 65535) throw new IllegalArgumentException(\"uint16 out of range\");\n", out); break;
    case IDL_ULONG: case IDL_UINT32: fputs("        if (value < 0 || value > 0xffffffffL) throw new IllegalArgumentException(\"uint32 out of range\");\n", out); break;
    case IDL_ULLONG: case IDL_UINT64: fputs("        if (value.signum() < 0 || value.bitLength() > 64) throw new IllegalArgumentException(\"uint64 out of range\");\n", out); break;
    default: break;
  }
}
static void emit_range_check(FILE *out, const void *spec, const idl_literal_t *bound, bool minimum) {
  if (!bound) return;
  const void *type = shape(spec).base;
  const char *method = NULL;
  if (!promote_width()) switch (idl_type(type)) {
    case IDL_OCTET: case IDL_UINT8: method = "java.lang.Byte.toUnsignedInt"; break;
    case IDL_USHORT: case IDL_UINT16: method = "java.lang.Short.toUnsignedInt"; break;
    case IDL_ULONG: case IDL_UINT32: method = "java.lang.Integer.toUnsignedLong"; break;
    case IDL_ULLONG: case IDL_UINT64: method = "long"; break;
    default: break;
  }
  fputs("        if (", out);
  if (promote_width() && (idl_type(type) == IDL_ULLONG || idl_type(type) == IDL_UINT64)) {
    fputs("value.compareTo(", out); emit_literal(out, spec, bound); fprintf(out, ") %s 0", minimum ? "<" : ">");
  } else if (method && strcmp(method, "long") == 0) {
    fputs("java.lang.Long.compareUnsigned(value, ", out); emit_literal(out, spec, bound);
    fprintf(out, ") %s 0", minimum ? "<" : ">");
  } else if (method) {
    fprintf(out, "%s(value) %s %s(", method, minimum ? "<" : ">", method);
    emit_literal(out, spec, bound); fputc(')', out);
  } else {
    if (idl_type(type) == IDL_FLOAT || idl_type(type) == IDL_DOUBLE)
      fprintf(out, "java.lang.%s.isNaN(value) || ", idl_type(type) == IDL_FLOAT ? "Float" : "Double");
    fprintf(out, "value %s ", minimum ? "<" : ">"); emit_literal(out, spec, bound);
  }
  fputs(") throw new IllegalArgumentException(\"IDL range exceeded\");\n", out);
}
static const void *annotation_argument(const idl_annotation_appl_t *a, const char *name) {
  for (const idl_annotation_appl_param_t *arg = a->parameters; arg; arg = (const idl_annotation_appl_param_t *) arg->node.next)
    if (strcmp(idl_identifier(arg->member->declarator), name) == 0) return arg->const_expr;
  for (const idl_node_t *def = a->annotation->definitions; def; def = def->next)
    if (idl_is_annotation_member(def)) {
      const idl_annotation_member_t *m = (const idl_annotation_member_t *) def;
      if (strcmp(idl_identifier(m->declarator), name) == 0) return m->const_expr;
    }
  return NULL;
}
static bool non_serialized(const idl_node_t *node) {
  for (const idl_annotation_appl_t *a = node->annotations; a; a = (const idl_annotation_appl_t *) a->node.next)
    if (strcmp(idl_identifier(a->annotation), "non_serialized") == 0) {
      const idl_literal_t *v = annotation_argument(a, "value"); return !v || v->value.bln;
    }
  return false;
}
struct hidden_member { idl_member_t **slot; idl_member_t *value; struct hidden_member *next; };
static bool hide_members(idl_node_t *nodes, struct hidden_member **changes) {
  for (idl_node_t *n = nodes; n; n = n->next) {
    if (idl_is_module(n)) { if (!hide_members(((idl_module_t *) n)->definitions, changes)) return false; }
    if (!idl_is_struct(n)) continue;
    idl_member_t **slot = &((idl_struct_t *) n)->members;
    while (*slot) {
      idl_member_t *m = *slot;
      if (non_serialized(&m->node)) {
        struct hidden_member *change = malloc(sizeof(*change));
        if (!change) return false;
        *change = (struct hidden_member) {slot, m, *changes}; *changes = change;
        *slot = (idl_member_t *) m->node.next;
      } else slot = (idl_member_t **) &m->node.next;
    }
  }
  return true;
}
/* The XTypes schema and the Java codec must omit exactly the same transient members.
   Restore every AST link before returning to idlc, including failure paths. */
static idl_retcode_t generate_metadata(const idl_pstate_t *pstate, const idl_node_t *node,
    const idlc_generator_config_t *config, idl_typeinfo_typemap_t *blobs) {
  struct hidden_member *changes = NULL;
  bool ok = hide_members(pstate->root, &changes);
  idl_retcode_t rc = ok ? config->generate_typeinfo_typemap(pstate, node, blobs) : IDL_RETCODE_NO_MEMORY;
  while (changes) { struct hidden_member *c = changes; *c->slot = c->value; changes = c->next; free(c); }
  return rc;
}
static bool validate_annotations(const idl_node_t *node) {
  for (const idl_annotation_appl_t *a = node->annotations; a; a = (const idl_annotation_appl_t *) a->node.next) {
    const char *name = idl_identifier(a->annotation);
    if (strcmp(name, "java_mapping") == 0) {
      const idl_literal_t *string = annotation_argument(a, "string_type");
      if (string && strcmp(string->value.str, "java.lang.String") != 0 && strcmp(string->value.str, "java.lang.StringBuilder") != 0 && strcmp(string->value.str, "java.lang.StringBuffer") != 0) {
        fprintf(stderr, "idljava: string_type must be java.lang.String, java.lang.StringBuilder or java.lang.StringBuffer\n"); return false;
      }
    }
    if (strcmp(name, "non_serialized") == 0 && non_serialized(node) && (!idl_is_member(node) || ((const idl_member_t *) node)->key.value)) {
      fprintf(stderr, "idljava: @non_serialized requires a non-key struct member\n"); return false;
    }

  }
  return true;
}
static void emit_verbatim(FILE *out, const idl_node_t *node, const char *placement) {
  for (const idl_annotation_appl_t *a = node->annotations; a; a = (const idl_annotation_appl_t *) a->node.next) {
    if (strcmp(idl_identifier(a->annotation), "verbatim") != 0) continue;
    const idl_literal_t *language = annotation_argument(a, "language"), *text = annotation_argument(a, "text");
    const void *where = annotation_argument(a, "placement");
    if (language && text && where && (strcmp(language->value.str, "java") == 0 || strcmp(language->value.str, "*") == 0)
        && idl_is_enumerator(where) && strcmp(idl_identifier(where), placement) == 0) {
      fputs(text->value.str, out); fputc('\n', out);
    }
  }
}
static void emit_annotations(FILE *out, const idl_node_t *node) {
  const idl_node_t *saved_context = mapping_context; mapping_context = NULL;
  for (const idl_annotation_appl_t *a = node->annotations; a; a = (const idl_annotation_appl_t *) a->node.next) {
    if (a->annotation->callback || strcmp(idl_identifier(a->annotation), "java_mapping") == 0) continue;
    char *q = qualified(&a->annotation->node, ".", true);
    fprintf(out, "@%s(", q); free(q);
    bool first = true;
    for (const idl_annotation_appl_param_t *arg = a->parameters; arg; arg = (const idl_annotation_appl_param_t *) arg->node.next) {
      if (!first) {
        fputs(", ", out);
      }
      first = false;
      fprintf(out, "%s=", mapped_name(arg->member->declarator));
      emit_literal(out, arg->member->type_spec, arg->const_expr);
    }
    fputs(")\n", out);
  }
  mapping_context = saved_context;
}
static void member_default(FILE *out, const idl_member_t *m, const idl_declarator_t *d) {
  if (m->optional.value) fputs("null", out);
  else if (m->value.value) emit_literal(out, member_spec(m, d), m->value.value);
  else if (m->external.value && (idl_is_struct(shape(member_spec(m, d)).base) || idl_is_union(shape(member_spec(m, d)).base))) fputs("null", out);
  else emit_default(out, member_spec(m, d));
}
static bool short_accessor(const idl_struct_t *node, const char *name) {
  static const char *reserved[] = {"wait", "notify", "notifyAll", "clone", "finalize", "getClass", "hashCode", "equals", "toString", "codec", "typeSupport", NULL};
  for (size_t i = 0; reserved[i]; i++) if (strcmp(name, reserved[i]) == 0) return false;
  // Convenience methods must not collide with another member's mandatory accessor.
  struct java_field fields[1024]; size_t n = fields_of(node, fields, 0);
  for (size_t i = 0; i < n && i < 1024; i++) {
    const char *other = mapped_name(fields[i].decl);
    if ((strncmp(name, "get_", 4) == 0 || strncmp(name, "set_", 4) == 0) && strcmp(name + 4, other) == 0) return false;
  }
  return true;
}
static idl_retcode_t emit_struct(FILE *out, const idl_pstate_t *pstate, const idl_struct_t *node,
    const idlc_generator_config_t *config) {
  struct java_field fields[1024]; size_t count = fields_of(node, fields, 0);
  if (count > 1024) return IDL_RETCODE_UNSUPPORTED;
  if (!(idl_allowable_data_representations(node) & IDL_DATAREPRESENTATION_FLAG_XCDR2)) {
    fprintf(stderr, "idljava: XCDR2 representation is required\n"); return IDL_RETCODE_UNSUPPORTED;
  }
  for (size_t i = 0; i < count; i++) if (!validate_annotations(&fields[i].member->node) || !supported(member_spec(fields[i].member, fields[i].decl))) {
    fprintf(stderr, "idljava: unsupported member type\n"); return IDL_RETCODE_UNSUPPORTED;
  }
  idl_typeinfo_typemap_t blobs = {0};
  idl_retcode_t rc = generate_metadata(pstate, &node->node, config, &blobs);
  if (rc != IDL_RETCODE_OK) return rc;
  const char *name = mapped_name(node);
  const idl_struct_t *base = node->inherit_spec ? node->inherit_spec->base : NULL;
  size_t inherited = base ? fields_of(base, fields, 0) : 0;
  // Refill because the base-only collection reused the same array.
  fields_of(node, fields, 0);
  int ext = node->extensibility.value == IDL_MUTABLE ? 2 : node->extensibility.value == IDL_APPENDABLE ? 1 : 0;
  emit_annotations(out, &node->node);
  fprintf(out, "@SuppressWarnings({\"serial\", \"unchecked\"})\npublic class %s", name);
  if (base) { char *q = qualified(&base->node, ".", true); fprintf(out, " extends %s", q); free(q); }
  fputs(" implements java.io.Serializable {\n    private static final long serialVersionUID = 1L;\n", out);
  emit_verbatim(out, &node->node, "BEGIN_DECLARATION");
  for (size_t i = inherited; i < count; i++) {
    const idl_member_t *m = fields[i].member; const idl_declarator_t *d = fields[i].decl; mapping_context = &m->node;
    emit_annotations(out, &m->node);
    fputs(non_serialized(&m->node) ? "    private transient " : "    private ", out); emit_member_type(out, m, d); fprintf(out, " %s = ", mapped_name(d));
    member_default(out, m, d); fputs(";\n", out);
  }
  fprintf(out, "    public %s() {}\n", name);
  if (count || base) {
    fprintf(out, "    public %s(", name);
    if (base) { char *q = qualified(&base->node, ".", true); fprintf(out, "%s $parent", q); free(q); }
    for (size_t i = inherited; i < count; i++) {
      if (i > inherited || base) fputs(", ", out);
      emit_member_type(out, fields[i].member, fields[i].decl); fprintf(out, " %s", mapped_name(fields[i].decl));
    }
    fputs(") {\n", out);
    if (base) fputs("        java.util.Objects.requireNonNull($parent);\n", out);
    for (size_t i = 0; i < count; i++) {
      const char *m = mapped_name(fields[i].decl);
      if (i < inherited) fprintf(out, "        set_%s($parent.get_%s());\n", m, m);
      else fprintf(out, "        set_%s(%s);\n", m, m);
    }
    fputs("    }\n", out);
  }
  for (size_t i = inherited; i < count; i++) {
    const idl_member_t *m = fields[i].member; const idl_declarator_t *d = fields[i].decl; mapping_context = &m->node;
    const void *spec = member_spec(m, d); struct array_shape a = shape(spec); const void *type = a.base;
    const char *member = mapped_name(d);
    if (strcmp(mapped_name(d), idl_identifier(d)) != 0) { fputs("    @org.eclipse.cyclonedds.topic.IdlName(", out); java_string(out, idl_identifier(d)); fputs(")\n", out); }
    fputs("    public ", out); emit_member_type(out, m, d); fprintf(out, " get_%s() { return %s; }\n", member, member);
    if (short_accessor(node, member)) { fputs("    public ", out); emit_member_type(out, m, d); fprintf(out, " %s() { return get_%s(); }\n", member, member); }
    fprintf(out, "    public void set_%s(", member); emit_member_type(out, m, d); fputs(" value) {\n", out);
    bool ref = a.count || idl_is_string(type) || idl_type(type) == IDL_WSTRING || idl_is_sequence(type) || idl_is_struct(type) || idl_is_enum(type) || idl_is_union(type) || (promote_width() && (idl_type(type) == IDL_UINT64 || idl_type(type) == IDL_ULLONG)) || m->external.value || m->optional.value;
    if (ref && !m->optional.value && !m->external.value) fputs("        java.util.Objects.requireNonNull(value);\n", out);
    if (m->optional.value || m->external.value) fputs("        if (value != null) {\n", out);
    if (a.count) { fputs("        org.eclipse.cyclonedds.core.cdr.CdrCodecs.validateArray(value, ", out); emit_dimensions(out, a); fputs(");\n", out); }
    uint32_t bound = a.count ? 0 : idl_is_string(type) || idl_type(type) == IDL_WSTRING ? ((const idl_string_t *) type)->maximum : idl_is_sequence(type) ? ((const idl_sequence_t *) type)->maximum : 0;
    if (bound) fprintf(out, "        if (%s > %"PRIu32"L) throw new IndexOutOfBoundsException(\"IDL bound exceeded\");\n", idl_type(type) == IDL_WSTRING ? "value.length()" : idl_is_string(type) ? "value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length" : "value.size()", bound);
    emit_unsigned_check(out, spec);
    emit_range_check(out, spec, m->min.value, true);
    emit_range_check(out, spec, m->max.value, false);
    if (m->optional.value || m->external.value) fputs("        }\n", out);
    fprintf(out, "        this.%s = value;\n    }\n", member);
    if (java_naming(&d->node)) {
      const char *suffix = transform_name(idl_identifier(d), 2);
      if (strcmp(suffix, "Class") == 0) suffix = "_Class";
      fputs("    public ", out); emit_member_type(out, m, d);
      fprintf(out, " get%s() { return get_%s(); }\n    public void set%s(", suffix, member, suffix);
      emit_member_type(out, m, d); fprintf(out, " %s) { set_%s(%s); }\n", member, member, member);
    }
  }
  fputs("    private java.lang.Object[] $memberValues() { return new java.lang.Object[]{", out);
  for (size_t i = 0; i < count; i++) fprintf(out, "%sorg.eclipse.cyclonedds.core.cdr.CdrCodecs.comparable(get_%s())", i ? ", " : "", mapped_name(fields[i].decl));
  fprintf(out, "}; }\n    @Override public boolean equals(java.lang.Object value) { return value != null && value.getClass() == getClass() && java.util.Arrays.deepEquals($memberValues(), ((%s) value).$memberValues()); }\n    @Override public int hashCode() { return java.util.Arrays.deepHashCode($memberValues()); }\n    @Override public java.lang.String toString() { return \"%s\" + java.util.Arrays.deepToString($memberValues()); }\n", name, name);
  fprintf(out, "    private static final org.eclipse.cyclonedds.topic.TypeSupport.Codec<%s> $CODEC = new org.eclipse.cyclonedds.topic.TypeSupport.Codec<>() {\n        public void write(org.eclipse.cyclonedds.core.cdr.CdrWriter w, %s value) {\n            w.enter(value);\n            try {\n            int __end = w.beginType(%d);\n", name, name, ext);
  for (size_t i = 0; i < count; i++) {
    const idl_member_t *m = fields[i].member; const idl_declarator_t *d = fields[i].decl; mapping_context = &m->node;
    const char *member = mapped_name(d);
    if (non_serialized(&m->node)) continue;
    if (ext == 2) {
      if (m->optional.value) fprintf(out, "            if (value.get_%s() != null) {\n", member);
      fprintf(out, "            int __m%zu = w.beginMember(%"PRIu32", %s);\n", i, d->id.value, m->must_understand.value || m->key.value ? "true" : "false");
    }
    fputs("            ", out);
    if (ext != 2 && m->optional.value) fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.optional(", out);
    emit_codec(out, member_spec(m, d));
    if (ext != 2 && m->optional.value) fprintf(out, ", %"PRIu32", %s)", d->id.value, m->must_understand.value ? "true" : "false");
    fprintf(out, ".write(w, value.get_%s());\n", member);
    if (ext == 2) { fprintf(out, "            w.endMember(__m%zu);\n", i); if (m->optional.value) fputs("            }\n", out); }
  }
  fprintf(out, "            w.endType(__end);\n            } finally { w.leave(value); }\n        }\n        public %s read(org.eclipse.cyclonedds.core.cdr.CdrReader r) {\n", name);
  if (ext == 2) fputs("            var __members = r.beginMutable();\n", out);
  else fprintf(out, "            int __end = r.beginType(%d);\n", ext);
  fprintf(out, "            var value = new %s();\n", name);
  for (size_t i = 0; i < count; i++) {
    const idl_member_t *m = fields[i].member; const idl_declarator_t *d = fields[i].decl; mapping_context = &m->node;
    if (non_serialized(&m->node)) continue;
    if (ext == 2) fprintf(out, "            if (r.beginMember(__members, %"PRIu32")) {\n", d->id.value);
    else if (ext == 1) fputs("            if (r.hasRemaining()) {\n", out);
    bool last = true;
    for (size_t j = i + 1; j < count; j++) if (!non_serialized(&fields[j].member->node)) { last = false; break; }
    fprintf(out, "            r.prepareValue(%s);\n", last ? "true" : "false");
    fprintf(out, "            value.set_%s(", mapped_name(d));
    if (m->optional.value && ext != 2) fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.optional(", out);
    if (m->try_construct.value != IDL_DISCARD) fputs("org.eclipse.cyclonedds.core.cdr.CdrCodecs.recover(", out);
    emit_codec(out, member_spec(m, d));
    if (m->try_construct.value != IDL_DISCARD) {
      fprintf(out, ", %d, () -> ", m->try_construct.value == IDL_TRIM ? 2 : 1);
      member_default(out, m, d);
      fputc(')', out);
    }
    if (m->optional.value && ext != 2) fprintf(out, ", %"PRIu32", %s)", d->id.value, m->must_understand.value ? "true" : "false");
    fputs(".read(r));\n", out);
    if (ext == 2) fputs("            r.endMember();\n", out);
    if (ext != 0) fputs("            }\n", out);
  }
  fputs(ext == 2 ? "            r.endMutable(__members); return value;\n        }\n" : "            r.endType(__end); return value;\n        }\n", out);
  fprintf(out, "        public void copyKeys(%s target, %s source) {\n", name, name);
  if (base) { char *q = qualified(&base->node, ".", true); fprintf(out, "            %s.$codec$%s().copyKeys(target, source);\n", q, mapped_name(base)); free(q); }
  const idl_node_t *key_nodes[128] = {&node->node}; emit_key_copies(out, pstate, node, node, key_nodes, 1, "");
  fputs("        }\n    };\n", out);
  char *dds_name = qualified(&node->node, "::", false);
  fprintf(out, "    private static final org.eclipse.cyclonedds.topic.TypeSupport<%s> $TYPE = new org.eclipse.cyclonedds.topic.TypeSupport<>(\"%s\", %s,\n        ", name, dds_name, idl_is_keyless(node, pstate->keylists) ? "false" : "true");
  emit_hex(out, blobs.typeinfo, blobs.typeinfo_size); fputs(",\n        ", out); emit_hex(out, blobs.typemap, blobs.typemap_size); fprintf(out, ", $CODEC, %"PRIu32");\n", representations(node));
  if (!used_as_base(pstate->root, node)) fprintf(out, "    public static org.eclipse.cyclonedds.topic.TypeSupport.Codec<%s> codec() { return $CODEC; }\n    public static org.eclipse.cyclonedds.topic.TypeSupport<%s> typeSupport() { return $TYPE; }\n", name, name);
  fprintf(out, "    public static org.eclipse.cyclonedds.topic.TypeSupport.Codec<%s> $codec$%s() { return $CODEC; }\n    public static org.eclipse.cyclonedds.topic.TypeSupport<%s> $typeSupport$%s() { return $TYPE; }\n", name, name, name, name);
  emit_verbatim(out, &node->node, "END_DECLARATION");
  fputs("}\n", out);
  free(dds_name); free(blobs.typeinfo); free(blobs.typemap); return IDL_RETCODE_OK;
}

static idl_retcode_t emit_enum(FILE *out, const idl_enum_t *node) {
  if (node->bit_bound.value == 0 || node->bit_bound.value > 32) return IDL_RETCODE_UNSUPPORTED;
  emit_annotations(out, &node->node);
  const char *name = mapped_name(node);
  fprintf(out, "public enum %s {\n", name);
  for (const idl_enumerator_t *e = node->enumerators; e; e = (const idl_enumerator_t *) e->node.next) {
    emit_annotations(out, &e->node);
    if (strcmp(mapped_name(e), idl_identifier(e)) != 0) { fputs("    @org.eclipse.cyclonedds.topic.IdlName(", out); java_string(out, idl_identifier(e)); fputs(")\n", out); }
    fprintf(out, "    %s(%"PRId32")%s\n", mapped_name(e), e->value.value, e->node.next ? "," : ";");
  }
  fprintf(out, "    private final int $value;\n    %s(int value) { this.$value = value; }\n    public int getValue() { return $value; }\n    public int value() { return getValue(); }\n    public static %s valueOf(int value) {\n        return switch (value) {\n", name, name);
  for (const idl_enumerator_t *e = node->enumerators; e; e = (const idl_enumerator_t *) e->node.next)
    fprintf(out, "            case %"PRId32" -> %s;\n", e->value.value, mapped_name(e));
  fprintf(out, "            default -> throw new IllegalArgumentException(\"Invalid IDL enum value: \" + value);\n        };\n    }\n    public static %s fromValue(int value) { return valueOf(value); }\n}\n", name);
  return IDL_RETCODE_OK;
}

static const idl_node_t *union_context;
static void emit_discriminator_type(FILE *out, const void *type) {
  const idl_node_t *saved = mapping_context; mapping_context = union_context;
  emit_type(out, type); mapping_context = saved;
}
static void emit_discriminator_default(FILE *out, const void *type) {
  const idl_node_t *saved = mapping_context; mapping_context = union_context;
  emit_default(out, type); mapping_context = saved;
}
static void emit_discriminator_codec(FILE *out, const void *type) {
  const idl_node_t *saved = mapping_context; mapping_context = union_context;
  emit_codec(out, type); mapping_context = saved;
}
static void emit_discriminant_value(FILE *out, const void *type, const void *expression) {
  type = resolved(type);
  if (idl_is_enumerator(expression)) {
    char *scoped = qualified(idl_parent(expression), ".", true);
    fprintf(out, "%s.%s", scoped, mapped_name(expression)); free(scoped); return;
  }
  const idl_literal_t *literal = expression;
  if (idl_is_enum(type)) {
    char *scoped = qualified(type, ".", true);
    fprintf(out, "%s.valueOf(%"PRId32")", scoped, literal->value.int32); free(scoped); return;
  }
  if (idl_type(type) == IDL_BOOL) { fputs(literal->value.bln ? "true" : "false", out); return; }
  uint64_t bits;
  switch (idl_type(type)) {
    case IDL_CHAR: bits = (unsigned char) literal->value.chr; break;
    case IDL_OCTET: case IDL_INT8: case IDL_UINT8: bits = literal->value.uint8; break;
    case IDL_WCHAR: case IDL_SHORT: case IDL_USHORT: case IDL_INT16: case IDL_UINT16: bits = literal->value.uint16; break;
    case IDL_LONG: case IDL_ULONG: case IDL_INT32: case IDL_UINT32: bits = literal->value.uint32; break;
    default: bits = literal->value.uint64; break;
  }
  if (promote_width() && (idl_type(type) == IDL_UINT64 || idl_type(type) == IDL_ULLONG)) fprintf(out, "new java.math.BigInteger(\"%"PRIu64"\")", bits);
  else {
    if (strcmp(primitive(type, false), "long") != 0) fprintf(out, "(%s) ", primitive(type, false));
    fprintf(out, "0x%"PRIx64"L", bits);
  }
}
static void emit_discriminant(FILE *out, const void *type, const void *expression) {
  const idl_node_t *saved = mapping_context; mapping_context = union_context;
  emit_discriminant_value(out, type, expression); mapping_context = saved;
}
static const void *case_spec(const idl_case_t *branch) {
  return idl_is_array(branch->declarator) ? branch->declarator : branch->type_spec;
}
static void emit_case_type(FILE *out, const idl_case_t *branch) {
  struct array_shape a = shape(case_spec(branch));
  const char *boxed = !a.count && branch->external.value ? boxed_type(a.base) : NULL;
  if (boxed) fputs(boxed, out); else emit_type(out, case_spec(branch));
}
static const char *union_member_name(const idl_declarator_t *decl) {
  return strcmp(idl_identifier(decl), "discriminator") == 0 ? "_discriminator" : mapped_name(decl);
}
static idl_retcode_t emit_union(FILE *out, const idl_pstate_t *pstate, const idl_union_t *node, const idlc_generator_config_t *config) {
  union_context = &node->node;
  const void *discriminator = resolved(node->switch_type_spec->type_spec);
  if (idl_type(discriminator) == IDL_WCHAR) {
    fprintf(stderr, "idljava: wchar unions are not supported by the unmodified Cyclone DDS core\n");
    return IDL_RETCODE_UNSUPPORTED;
  }
  if (idl_type(discriminator) == IDL_LLONG || idl_type(discriminator) == IDL_INT64)
    for (const idl_case_t *c = node->cases; c; c = idl_next(c))
      for (const idl_case_label_t *label = c->labels; label; label = idl_next(label))
        if (!idl_is_default_case_label(label) && ((const idl_literal_t *)label->const_expr)->value.int64 < 0) {
          fprintf(stderr, "idljava: negative 64-bit union labels are not supported by the unmodified Cyclone DDS core\n");
          return IDL_RETCODE_UNSUPPORTED;
        }

  if ((node->extensibility.value != IDL_FINAL && node->extensibility.value != IDL_APPENDABLE && node->extensibility.value != IDL_MUTABLE) ||
      (!idl_is_enum(discriminator) && !idl_is_base_type(discriminator)) || (!primitive(discriminator, false) && !idl_is_enum(discriminator)))
    return IDL_RETCODE_UNSUPPORTED;
  for (const idl_case_t *branch = node->cases; branch; branch = (const idl_case_t *) branch->node.next)
    if (!validate_annotations(&branch->node) || !supported(case_spec(branch)))
      return IDL_RETCODE_UNSUPPORTED;
  const char *name = mapped_name(node);
  bool implicit = idl_is_implicit_default_case_label(node->default_case);
  int extensibility = node->extensibility.value == IDL_MUTABLE ? 2 : node->extensibility.value == IDL_APPENDABLE ? 1 : 0;
  emit_annotations(out, &node->node);
  fprintf(out, "public final class %s implements java.io.Serializable {\n    private static final long serialVersionUID = 1L;\n", name);
  emit_verbatim(out, &node->node, "BEGIN_DECLARATION");
  fputs("    private ", out);
  emit_discriminator_type(out, discriminator); fputs(" __discriminator = ", out); emit_discriminator_default(out, discriminator);
  fputs(";\n    @SuppressWarnings(\"serial\") private java.lang.Object __value;\n    private static int __branch(", out);
  emit_discriminator_type(out, discriminator); fputs(" d) {\n", out);
  int index = 0, default_index = -1;
  for (const idl_case_t *branch = node->cases; branch; branch = (const idl_case_t *) branch->node.next, index++) {
    mapping_context = &branch->node;
    for (const idl_case_label_t *label = branch->labels; label; label = (const idl_case_label_t *) label->node.next) {
      if (idl_is_default_case_label(label)) { default_index = index; continue; }
      fputs("        if (java.util.Objects.equals(d, ", out); emit_discriminant(out, discriminator, label->const_expr); fprintf(out, ")) return %d;\n", index);
    }
  }
  fprintf(out, "        return %d;\n    }\n    public %s() {\n        switch (__branch(__discriminator)) {\n", default_index, name);
  index = 0;
  for (const idl_case_t *branch = node->cases; branch; branch = (const idl_case_t *) branch->node.next, index++) {
    mapping_context = &branch->node;
    fprintf(out, "            case %d -> __value = ", index);
    struct array_shape a = shape(case_spec(branch));
    if (branch->external.value && !a.count && (idl_is_struct(a.base) || idl_is_union(a.base))) fputs("null", out);
    else emit_default(out, case_spec(branch));
    fputs(";\n", out);
  }
  fputs("            default -> {}\n        }\n    }\n    public ", out); emit_discriminator_type(out, discriminator);
  fputs(" get_discriminator() { return __discriminator; }\n", out);
  index = 0;
  for (const idl_case_t *branch = node->cases; branch; branch = (const idl_case_t *) branch->node.next, index++) {
    mapping_context = &branch->node;
    const char *member = union_member_name(branch->declarator);
    const void *spec = case_spec(branch); struct array_shape array = shape(spec);
    emit_annotations(out, &branch->node);
    if (strcmp(member, idl_identifier(branch->declarator)) != 0) { fputs("    @org.eclipse.cyclonedds.topic.IdlName(", out); java_string(out, idl_identifier(branch->declarator)); fputs(")\n", out); }
    fputs("    @SuppressWarnings(\"unchecked\") public ", out); emit_case_type(out, branch);
    fprintf(out, " get_%s() {\n        if (__branch(__discriminator) != %d) throw new IllegalStateException(\"Inactive union member\");\n        return (", member, index);
    emit_case_type(out, branch); fputs(") __value;\n    }\n", out);
    fprintf(out, "    public void set_%s(", member); emit_case_type(out, branch); fputs(" value) { set_", out);
    fprintf(out, "%s(value, ", member);
    emit_discriminant(out, discriminator, index == default_index ? node->default_case->const_expr : branch->labels->const_expr);
    fputs("); }\n", out);
    fprintf(out, "    public void set_%s(", member); emit_case_type(out, branch); fputs(" value, ", out); emit_discriminator_type(out, discriminator);
    fprintf(out, " d) {\n        if (__branch(d) != %d) throw new IllegalArgumentException(\"Discriminator does not select this member\");\n", index);
    if (!branch->external.value && (array.count || !idl_is_base_type(array.base))) fputs("        java.util.Objects.requireNonNull(value);\n", out);
    if (branch->external.value) fputs("        if (value != null) {\n", out);
    if (array.count) { fputs("        org.eclipse.cyclonedds.core.cdr.CdrCodecs.validateArray(value, ", out); emit_dimensions(out, array); fputs(");\n", out); }
    uint32_t bound = array.count ? 0 : idl_is_string(array.base) || idl_type(array.base) == IDL_WSTRING ? ((const idl_string_t *) array.base)->maximum :
        idl_is_sequence(array.base) ? ((const idl_sequence_t *) array.base)->maximum : 0;
    if (bound) fprintf(out, "        if (%s > %"PRIu32"L) throw new IndexOutOfBoundsException(\"IDL bound exceeded\");\n",
        idl_type(array.base) == IDL_WSTRING ? "value.length()" : idl_is_string(array.base) ? "value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length" : "value.size()", bound);
    emit_unsigned_check(out, spec);
    emit_range_check(out, spec, branch->min.value, true);
    emit_range_check(out, spec, branch->max.value, false);
    if (branch->external.value) fputs("        }\n", out);
    fputs("        __discriminator = d; __value = value;\n    }\n", out);
    if (java_naming(&branch->node)) {
      const char *suffix = transform_name(idl_identifier(branch->declarator), 2);
      if (strcmp(suffix, "Class") == 0) suffix = "_Class";
      fputs("    public ", out); emit_case_type(out, branch);
      fprintf(out, " get%s() { return get_%s(); }\n    public void set%s(", suffix, member, suffix);
      emit_case_type(out, branch); fprintf(out, " value) { set_%s(value); }\n", member);
    }
  }
  if (implicit) {
    fputs("    public void __default() { __default(", out); emit_discriminant(out, discriminator, node->default_case->const_expr);
    fputs("); }\n    public void __default(", out); emit_discriminator_type(out, discriminator);
    fputs(" d) { if (__branch(d) != -1) throw new IllegalArgumentException(\"Discriminator selects a member\"); __discriminator = d; __value = null; }\n", out);
  }
  fprintf(out, "    @Override public boolean equals(java.lang.Object other) { return other instanceof %s u && java.util.Objects.equals(__discriminator, u.__discriminator) && java.util.Objects.deepEquals(org.eclipse.cyclonedds.core.cdr.CdrCodecs.comparable(__value), org.eclipse.cyclonedds.core.cdr.CdrCodecs.comparable(u.__value)); }\n", name);
  fputs("    @Override public int hashCode() { return java.util.Arrays.deepHashCode(new java.lang.Object[]{__discriminator, org.eclipse.cyclonedds.core.cdr.CdrCodecs.comparable(__value)}); }\n", out);
  fprintf(out, "    private static final org.eclipse.cyclonedds.topic.TypeSupport.Codec<%s> $CODEC = new org.eclipse.cyclonedds.topic.TypeSupport.Codec<>() {\n        public void write(org.eclipse.cyclonedds.core.cdr.CdrWriter w, %s value) {\n            w.enter(value);\n            try {\n            int __end = w.beginType(%d);\n", name, name, extensibility);
  if (extensibility == 2) fputs("            int __disc = w.beginMember(0, true);\n", out);
  fputs("            ", out); emit_discriminator_codec(out, discriminator); fputs(".write(w, value.get_discriminator());\n", out);
  if (extensibility == 2) fputs("            w.endMember(__disc);\n", out);
  fputs("            switch (__branch(value.get_discriminator())) {\n", out);
  index = 0;
  for (const idl_case_t *branch = node->cases; branch; branch = (const idl_case_t *) branch->node.next, index++) {
    mapping_context = &branch->node;
    char expression[512]; snprintf(expression, sizeof(expression), "value.get_%s()", union_member_name(branch->declarator));
    fprintf(out, "                case %d -> {\n", index);
    if (extensibility == 2) fprintf(out, "                    int __member = w.beginMember(%"PRIu32", false);\n", branch->declarator->id.value);
    emit_write(out, case_spec(branch), expression, "                    ");
    if (extensibility == 2) fputs("                    w.endMember(__member);\n", out);
    fputs("                }\n", out);
  }
  fputs("                default -> {}\n            }\n            w.endType(__end);\n            } finally { w.leave(value); }\n        }\n", out);
  fprintf(out, "        public %s read(org.eclipse.cyclonedds.core.cdr.CdrReader r) {\n", name);
  if (extensibility == 2) fputs("            var __members = r.beginMutable();\n            if (!r.beginMember(__members, 0)) throw new IllegalArgumentException(\"Missing mutable union discriminator\");\n", out);
  else fprintf(out, "            int __end = r.beginType(%d);\n", extensibility);
  fputs("            var d = ", out); emit_discriminator_codec(out, discriminator); fputs(".read(r)", out); fputs(";\n", out);
  if (extensibility == 2) fputs("            r.endMember();\n", out);
  fprintf(out, "            var value = new %s();\n            switch (__branch(d)) {\n", name);
  index = 0;
  for (const idl_case_t *branch = node->cases; branch; branch = (const idl_case_t *) branch->node.next, index++) {
    mapping_context = &branch->node;
    fprintf(out, "                case %d -> {\n", index);
    if (extensibility == 2) fprintf(out, "                    if (!r.beginMember(__members, %"PRIu32")) throw new IllegalArgumentException(\"Missing mutable union branch\");\n", branch->declarator->id.value);
    fputs("                    r.prepareValue(true);\n", out);
    fprintf(out, "                    value.set_%s(", union_member_name(branch->declarator));
    emit_recover_codec(out, case_spec(branch), branch->try_construct.value); fputs(".read(r), d);\n", out);
    if (extensibility == 2) fputs("                    r.endMember();\n", out);
    fputs("                }\n", out);
  }
  fputs(implicit ? "                default -> value.__default(d);\n" : "                default -> throw new IllegalArgumentException(\"Invalid union discriminator\");\n", out);
  fputs("            }\n", out);
  fputs(extensibility == 2 ? "            r.endMutable(__members); return value;\n        }\n" : "            r.endType(__end); return value;\n        }\n", out);
  fprintf(out, "        public void copyKeys(%s target, %s source) {\n", name, name);
  if (!idl_is_keyless(node, pstate->keylists))
    fputs("            if (__branch(target.__discriminator) != __branch(source.__discriminator)) target.__value = source.__value;\n            target.__discriminator = source.__discriminator;\n", out);
  fputs("        }\n    };\n", out);
  idl_typeinfo_typemap_t blobs = {0};
  idl_retcode_t rc = generate_metadata(pstate, &node->node, config, &blobs);
  if (rc != IDL_RETCODE_OK) return rc;
  char *dds_name = qualified(&node->node, "::", false);
  fprintf(out, "    private static final org.eclipse.cyclonedds.topic.TypeSupport<%s> $TYPE = new org.eclipse.cyclonedds.topic.TypeSupport<>(\"%s\", %s,\n        ", name, dds_name, idl_is_keyless(node, pstate->keylists) ? "false" : "true");
  emit_hex(out, blobs.typeinfo, blobs.typeinfo_size); fputs(",\n        ", out); emit_hex(out, blobs.typemap, blobs.typemap_size);
  fprintf(out, ", $CODEC, %"PRIu32");\n    public static org.eclipse.cyclonedds.topic.TypeSupport.Codec<%s> codec() { return $CODEC; }\n    public static org.eclipse.cyclonedds.topic.TypeSupport<%s> typeSupport() { return $TYPE; }\n    public static org.eclipse.cyclonedds.topic.TypeSupport.Codec<%s> $codec$%s() { return $CODEC; }\n    public static org.eclipse.cyclonedds.topic.TypeSupport<%s> $typeSupport$%s() { return $TYPE; }\n", representations(node), name, name, name, name, name, name);
  emit_verbatim(out, &node->node, "END_DECLARATION");
  fputs("}\n", out);
  free(dds_name); free(blobs.typeinfo); free(blobs.typemap); return IDL_RETCODE_OK;
}

static idl_retcode_t emit_annotation(FILE *out, const idl_annotation_t *node) {
  mapping_context = NULL;
  const char *name = mapped_name(node);
  fprintf(out, "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PACKAGE})\n@java.lang.annotation.Repeatable(%sGroup.class)\npublic @interface %s {\n", name, name);
  for (const idl_node_t *def = node->definitions; def; def = def->next) {
    if (idl_is_enum(def)) { idl_retcode_t rc = emit_enum(out, (const idl_enum_t *) def); if (rc != IDL_RETCODE_OK) return rc; continue; }
    if (!idl_is_annotation_member(def)) { fprintf(stderr, "idljava: unsupported annotation definition\n"); return IDL_RETCODE_UNSUPPORTED; }
    const idl_annotation_member_t *m = (const idl_annotation_member_t *) def;
    const void *type = resolved(m->type_spec);
    if (!primitive(type, false) && !idl_is_enum(type)) {
      fprintf(stderr, "idljava: annotation members require scalar, enum or string types\n"); return IDL_RETCODE_UNSUPPORTED;
    }
    fputs("    ", out); emit_type(out, type); fprintf(out, " %s()", mapped_name(m->declarator));
    if (m->const_expr) { fputs(" default ", out); emit_literal(out, type, m->const_expr); }
    fputs(";\n", out);
  }
  fprintf(out, "    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n    @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PACKAGE})\n    public @interface $Repeat { %s[] value(); }\n}\n", name);
  return IDL_RETCODE_OK;
}

static void header_path(FILE *out, const char *path) {
  for (const char *p = path; *p; p++) {
    if (*p == '*' && p[1] == '/') fputs("* ", out);
    else fputc(*p == '\\' ? '/' : *p, out);
  }
}
static void emit_header(FILE *out, const idl_pstate_t *pstate, const idl_node_t *node, const char *file) {
  const idl_location_t *location = idl_location(node);
  const char *input = location && location->first.source && location->first.source->path
    ? location->first.source->path->name : pstate->sources->path->name;
  fputs(
    "/*\n"
    " * Copyright(c) 2026 Sven Trittler\n"
    " *\n"
    " * This program and the accompanying materials are made available under the\n"
    " * terms of the Eclipse Public License v. 2.0 which is available at\n"
    " * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License\n"
    " * v. 1.0 which is available at\n"
    " * http://www.eclipse.org/org/documents/edl-v10.php.\n"
    " *\n"
    " * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause\n"
    "*/\n", out);
  fputs("/****************************************************************\n\n  Generated by Eclipse Cyclone DDS IDL to Java Translator\n  File name: ", out);
  header_path(out, input); fputs("\n  Source: ", out); header_path(out, file);
  fprintf(out, "\n  Cyclone DDS: v%s\n\n*****************************************************************/\n\n", IDL_VERSION);
}

static const char *constants_container(const idl_node_t *node);
static bool unique_names(const idl_node_t *nodes) {
  for (const idl_node_t *a = nodes; a; a = a->next) {
    if (idl_is_forward(a) || idl_is_typedef(a)) continue;
    for (const idl_node_t *b = a->next; b; b = b->next) {
      if (idl_is_forward(b) || idl_is_typedef(b)) continue;
      if (idl_is_module(a) && idl_is_module(b) && strcmp(idl_identifier(a), idl_identifier(b)) == 0) continue;
      if (strcmp(mapped_name(a), mapped_name(b)) == 0) {
        fprintf(stderr, "idljava: naming collision between %s and %s\n", idl_identifier(a), idl_identifier(b)); return false;
      }
    }
    if (idl_is_const(a)) for (const idl_node_t *b = nodes; b; b = b->next)
      if (!idl_is_const(b) && !idl_is_forward(b) && strcmp(constants_container(a), mapped_name(b)) == 0) {
        fprintf(stderr, "idljava: naming collision with constants container %s\n", constants_container(a)); return false;
      }
    if (idl_mask(a) & IDL_ANNOTATION) for (const idl_node_t *b = nodes; b; b = b->next) {
      size_t n = strlen(mapped_name(a));
      if (strlen(mapped_name(b)) == n + 5 && strncmp(mapped_name(a), mapped_name(b), n) == 0 && strcmp(mapped_name(b) + n, "Group") == 0) {
        fprintf(stderr, "idljava: naming collision with annotation Group container\n"); return false;
      }
    }
    if (idl_is_union(a)) {
      const idl_union_t *u = (const idl_union_t *) a;
      for (const idl_case_t *c = u->cases; c; c = (const idl_case_t *) c->node.next)
        for (const idl_case_t *d = (const idl_case_t *) c->node.next; d; d = (const idl_case_t *) d->node.next)
          if (strcmp(union_member_name(c->declarator), union_member_name(d->declarator)) == 0) {
            fprintf(stderr, "idljava: naming collision between union branches\n"); return false;
          }
    }
    if (idl_is_module(a) && !unique_names(((const idl_module_t *) a)->definitions)) return false;
    if (idl_is_struct(a)) {
      struct java_field fields[1024]; size_t n = fields_of((const idl_struct_t *) a, fields, 0);
      if (n > 1024) return false;
      for (size_t i = 0; i < n; i++) for (size_t j = i + 1; j < n; j++)
        if (strcmp(mapped_name(fields[i].decl), mapped_name(fields[j].decl)) == 0) {
          fprintf(stderr, "idljava: naming collision between %s and %s\n", idl_identifier(fields[i].decl), idl_identifier(fields[j].decl)); return false;
        }
    }
  }
  return true;
}

static const char *constants_container(const idl_node_t *node) {
  const idl_literal_t *option = mapping_option(node, "constants_container");
  return option ? option->value.str : "Constants";
}
static idl_retcode_t emit_constants(const idl_pstate_t *pstate, const idlc_generator_config_t *config, const idl_node_t *nodes) {
  for (const idl_node_t *n = nodes; n; n = n->next) {
    if (!idl_is_const(n)) continue;
    const char *container = constants_container(n);
    if (!*container || !isalpha((unsigned char) *container)) return IDL_RETCODE_BAD_PARAMETER;
    for (const char *c = container; *c; c++) if (!isalnum((unsigned char) *c) && *c != '_') return IDL_RETCODE_BAD_PARAMETER;
    bool emitted = false;
    for (const idl_node_t *prev = nodes; prev != n; prev = prev->next)
      if (idl_is_const(prev) && strcmp(constants_container(prev), container) == 0) emitted = true;
    if (emitted) continue;
    char *path = n->parent ? qualified(n->parent, "/", true) : NULL;
    char *pkg = n->parent ? qualified(n->parent, ".", true) : NULL;
    if (n->parent && (!path || !pkg)) { free(path); free(pkg); return IDL_RETCODE_NO_MEMORY; }
    size_t size = strlen(config->output_dir ? config->output_dir : ".") + (path ? strlen(path) : 0) + strlen(container) + 16;
    char *file = malloc(size);
    if (!file) { free(path); free(pkg); return IDL_RETCODE_NO_MEMORY; }
    snprintf(file, size, "%s/%s", config->output_dir ? config->output_dir : ".", path ? path : "");
    idl_retcode_t rc = idl_mkpath(file);
    if (rc == IDL_RETCODE_OK) {
      strcat(file, "/"); strcat(file, container); strcat(file, ".java");
      FILE *out = fopen(file, "w");
      if (!out) rc = IDL_RETCODE_NO_ACCESS;
      else {
        emit_header(out, pstate, n, file);
        if (pkg && *pkg) fprintf(out, "package %s;\n", pkg);
        fprintf(out, "public final class %s {\n    private %s() {}\n", container, container);
        for (const idl_node_t *c = n; c; c = c->next) if (idl_is_const(c) && strcmp(constants_container(c), container) == 0) {
          mapping_context = c; const idl_const_t *value = (const idl_const_t *) c;
          fputs("    public static final ", out); emit_type(out, value->type_spec);
          fprintf(out, " %s = ", mapped_name(c)); emit_literal(out, value->type_spec, value->const_expr); fputs(";\n", out);
        }
        fputs("}\n", out); bool failed = ferror(out) != 0; if (fclose(out) != 0) failed = true;
        if (failed) rc = IDL_RETCODE_NO_ACCESS;
      }
    }
    free(file); free(pkg); free(path); if (rc != IDL_RETCODE_OK) return rc;
  }
  return IDL_RETCODE_OK;
}

static idl_retcode_t emit_nodes(const idl_pstate_t *pstate, const idlc_generator_config_t *config, const idl_node_t *nodes) {
  idl_retcode_t constants_result = emit_constants(pstate, config, nodes);
  if (constants_result != IDL_RETCODE_OK) return constants_result;
  for (const idl_node_t *node = nodes; node; node = node->next) {
    mapping_context = node;
    if (!validate_annotations(node)) return IDL_RETCODE_UNSUPPORTED;
    if (idl_is_module(node)) {
      bool annotated = false;
      for (const idl_annotation_appl_t *a = node->annotations; a; a = (const idl_annotation_appl_t *) a->node.next)
        if (!a->annotation->callback && strcmp(idl_identifier(a->annotation), "java_mapping") != 0) annotated = true;
      if (annotated) {
        char *path = qualified(node, "/", true), *pkg = qualified(node, ".", true);
        if (!path || !pkg) { free(path); free(pkg); return IDL_RETCODE_NO_MEMORY; }
        size_t n = strlen(config->output_dir ? config->output_dir : ".") + strlen(path) + 25;
        char *file = malloc(n);
        if (!file) { free(path); free(pkg); return IDL_RETCODE_NO_MEMORY; }
        snprintf(file, n, "%s/%s", config->output_dir ? config->output_dir : ".", path);
        idl_retcode_t result = idl_mkpath(file);
        if (result == IDL_RETCODE_OK) {
          strcat(file, "/package-info.java"); FILE *package = fopen(file, "w");
          if (!package) result = IDL_RETCODE_NO_ACCESS;
          else { emit_header(package, pstate, node, file); emit_annotations(package, node); fprintf(package, "package %s;\n", pkg);
            bool failed = ferror(package) != 0; if (fclose(package) != 0) failed = true;
            if (failed) result = IDL_RETCODE_NO_ACCESS;
          }
        }
        free(file); free(path); free(pkg); if (result != IDL_RETCODE_OK) return result;
      }
      idl_retcode_t rc = emit_nodes(pstate, config, ((const idl_module_t *) node)->definitions);
      if (rc != IDL_RETCODE_OK) return rc;
      continue;
    }
    if ((idl_mask(node) & IDL_ANNOTATION) && strcmp(idl_identifier(node), "java_mapping") == 0) continue;
    if (idl_is_const(node) || idl_is_typedef(node) || idl_is_forward(node)) continue; /* aliases resolve to their Java target type */
    if (!idl_is_struct(node) && !idl_is_enum(node) && !idl_is_union(node) && !(idl_mask(node) & IDL_ANNOTATION)) {
      fprintf(stderr, "idljava: unsupported declaration %s\n", idl_identifier(node));
      return IDL_RETCODE_UNSUPPORTED;
    }
    char *relative = qualified(node, "/", true);
    char *scoped = qualified(node, ".", true);
    if (!relative || !scoped) { free(relative); free(scoped); return IDL_RETCODE_NO_MEMORY; }
    size_t size = strlen(config->output_dir ? config->output_dir : ".") + strlen(relative) + 8;
    char *filename = malloc(size);
    if (!filename) { free(relative); free(scoped); return IDL_RETCODE_NO_MEMORY; }
    snprintf(filename, size, "%s/%s.java", config->output_dir ? config->output_dir : ".", relative);
    char *slash = strrchr(filename, '/');
    *slash = 0;
    idl_retcode_t rc = idl_mkpath(filename);
    *slash = '/';
    if (rc != IDL_RETCODE_OK) { free(filename); free(relative); free(scoped); return rc; }
    FILE *out = fopen(filename, "w");
    if (!out) { free(filename); free(relative); free(scoped); return IDL_RETCODE_NO_ACCESS; }
    emit_verbatim(out, node, "BEGIN_FILE");
    emit_header(out, pstate, node, filename);
    char *dot = strrchr(scoped, '.');
    if (dot) { *dot = 0; fprintf(out, "package %s;\n\n", scoped); }
    emit_verbatim(out, node, "BEFORE_DECLARATION");
    rc = idl_is_struct(node) ? emit_struct(out, pstate, (const idl_struct_t *) node, config) : idl_is_union(node) ? emit_union(out, pstate, (const idl_union_t *) node, config) : idl_is_enum(node) ? emit_enum(out, (const idl_enum_t *) node) : emit_annotation(out, (const idl_annotation_t *) node);
    emit_verbatim(out, node, "AFTER_DECLARATION");
    emit_verbatim(out, node, "END_FILE");
    bool write_error = ferror(out) != 0;
    if (fclose(out) != 0) write_error = true;
    if (write_error && rc == IDL_RETCODE_OK) rc = IDL_RETCODE_NO_ACCESS;
    if (rc != IDL_RETCODE_OK) remove(filename);
    if (rc == IDL_RETCODE_OK && (idl_mask(node) & IDL_ANNOTATION)) {
      char *groupfile = malloc(strlen(filename) + 6);
      if (!groupfile) rc = IDL_RETCODE_NO_MEMORY;
      else {
        strcpy(groupfile, filename); strcpy(groupfile + strlen(groupfile) - 5, "Group.java");
        FILE *group = fopen(groupfile, "w");
        if (!group) rc = IDL_RETCODE_NO_ACCESS;
        else {
          emit_header(group, pstate, node, groupfile);
          if (dot) fprintf(group, "package %s;\n", scoped);
          fprintf(group, "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PACKAGE})\npublic @interface %sGroup { %s[] value(); }\n", mapped_name(node), mapped_name(node));
          bool failed = ferror(group) != 0; if (fclose(group) != 0) failed = true;
          if (failed) rc = IDL_RETCODE_NO_ACCESS;
        }
        free(groupfile);
      }
    }
    free(filename); free(relative); free(scoped);
    if (rc != IDL_RETCODE_OK) return rc;
  }
  return IDL_RETCODE_OK;
}

#ifdef _WIN32
__declspec(dllexport)
#endif
idl_retcode_t generate(const idl_pstate_t *pstate, const idlc_generator_config_t *config) {
  if (!config->generate_type_info || !config->generate_typeinfo_typemap) {
    fprintf(stderr, "idljava: XTypes type information is required; do not pass -t\n");
    return IDL_RETCODE_UNSUPPORTED;
  }
  allocation_failed = false;
  idl_retcode_t rc = unique_names(pstate->root) ? emit_nodes(pstate, config, pstate->root) : IDL_RETCODE_SEMANTIC_ERROR;
  while (escaped_names) {
    struct escaped_name *entry = escaped_names;
    escaped_names = entry->next;
    free(entry->name); free(entry);
  }
  return allocation_failed ? IDL_RETCODE_NO_MEMORY : rc;
}

#ifdef _WIN32
__declspec(dllexport)
#endif
const idl_builtin_annotation_t **generator_annotations(void) {
  static const idl_builtin_annotation_t mapping[] = {{
    .syntax = "@annotation java_mapping { enum NamingConvention { IDL_NAMING_CONVENTION, JAVA_NAMING_CONVENTION }; NamingConvention apply_naming_convention default IDL_NAMING_CONVENTION; string constants_container default \"Constants\"; boolean promote_integer_width default FALSE; string string_type default \"java.lang.String\"; };",
    .summary = "OMG IDL4-Java language mapping options",
    .callback = NULL
  }, {0}};
  static const idl_builtin_annotation_t *annotations[] = {mapping};
  return annotations;
}
