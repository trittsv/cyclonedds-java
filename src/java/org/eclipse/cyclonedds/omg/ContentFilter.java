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

package org.eclipse.cyclonedds.omg;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import org.eclipse.cyclonedds.core.cdr.CdrReader;

/** Compiled DDS SQL WHERE subset. Native predicates only call generated accessors. */
final class ContentFilter {
    private final CycloneTypeSupport<?> type;
    final String expression;
    final List<String> parameters;
    private final Predicate<Object> predicate;
    private volatile RuntimeException failure;
    ContentFilter(CycloneTypeSupport<?> type, String expression, List<String> parameters) {
        if (type.builtinKind != 0) throw Policies.unsupported("SQL predicates on built-in discovery data");
        this.type = type; this.expression = Objects.requireNonNull(expression, "Expression");
        this.parameters = List.copyOf(Objects.requireNonNull(parameters, "Parameters"));
        predicate = new Parser(type.getType(), expression, this.parameters).parse();
    }
    public boolean matches(byte[] cdr) {
        try { return predicate.test(type.binding().codec().read(new CdrReader(cdr))); }
        catch (RuntimeException error) { failure = error; return false; }
    }
    void checkFailure() {
        RuntimeException error = failure;
        if (error != null) throw new IllegalArgumentException("Content filter evaluation failed", error);
    }
    private static final class Parser {
        final Class<?> type;
        final List<String> tokens = new ArrayList<>();
        final List<String> parameters;
        final Object defaults;
        int index;
        Parser(Class<?> type, String expression, List<String> parameters) {
            this.type = type; this.parameters = parameters;
            try { defaults = type.getConstructor().newInstance(); }
            catch (ReflectiveOperationException e) { throw invalid("Expected a generated type with initialized defaults"); }
            var matcher = Pattern.compile("\\s+|'(?:''|[^'])*'|%[0-9]+|[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)*|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|<=|>=|<>|!=|[=<>(),]").matcher(expression);
            int end = 0;
            while (matcher.find()) {
                if (matcher.start() != end) throw invalid("Unexpected character");
                String token = matcher.group(); end = matcher.end();
                if (!token.isBlank()) tokens.add(token);
            }
            if (end != expression.length() || tokens.isEmpty()) throw invalid("Invalid expression");
        }
        IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message + " near token " + index); }
        boolean accept(String token) { if (index < tokens.size() && tokens.get(index).equalsIgnoreCase(token)) { index++; return true; } return false; }
        void require(String token) { if (!accept(token)) throw invalid("Expected " + token); }
        String next() { if (index == tokens.size()) throw invalid("Missing operand"); return tokens.get(index++); }
        Predicate<Object> parse() { var value = or(); if (index != tokens.size()) throw invalid("Unexpected token"); return value; }
        Predicate<Object> or() { var p = and(); while (accept("OR")) p = p.or(and()); return p; }
        Predicate<Object> and() { var p = term(); while (accept("AND")) p = p.and(term()); return p; }
        Predicate<Object> term() {
            if (accept("NOT")) return term().negate();
            if (accept("(")) { var p = or(); require(")"); return p; }
            Function<Object,Object> left = operand(true);
            boolean negate = accept("NOT");
            Predicate<Object> result;
            if (accept("BETWEEN")) {
                var low = operand(false); require("AND"); var high = operand(false);
                compare(left.apply(defaults), low.apply(defaults)); compare(left.apply(defaults), high.apply(defaults));
                result = x -> compare(left.apply(x), low.apply(x)) >= 0 && compare(left.apply(x), high.apply(x)) <= 0;
            } else if (accept("IN")) {
                require("("); List<Function<Object,Object>> values = new ArrayList<>();
                do { values.add(operand(false)); } while (accept(",")); require(")");
                for (var value : values) compare(left.apply(defaults), value.apply(defaults));
                result = x -> values.stream().anyMatch(v -> compare(left.apply(x), v.apply(x)) == 0);
            } else if (accept("LIKE")) {
                var value = operand(false);
                if (!(left.apply(defaults) instanceof String) || !(value.apply(defaults) instanceof String)) throw invalid("LIKE requires string operands");
                result = x -> like(String.valueOf(left.apply(x)), String.valueOf(value.apply(x)));
            } else {
                if (negate) throw invalid("NOT requires BETWEEN, IN or LIKE");
                String op = next();
                if (!List.of("=", "!=", "<>", "<", "<=", ">", ">=").contains(op)) throw invalid("Expected comparison");
                var right = operand(false);
                compare(left.apply(defaults), right.apply(defaults));
                result = x -> { int c = compare(left.apply(x), right.apply(x)); return switch (op) {
                    case "=" -> c == 0; case "!=", "<>" -> c != 0; case "<" -> c < 0;
                    case "<=" -> c <= 0; case ">" -> c > 0; default -> c >= 0;
                }; };
            }
            return negate ? result.negate() : result;
        }
        Function<Object,Object> operand(boolean mustBeField) {
            String token = next();
            if (token.startsWith("%")) {
                int number;
                try { number = Integer.parseInt(token.substring(1)); } catch (NumberFormatException e) { throw invalid("Invalid parameter"); }
                if (number >= parameters.size()) throw invalid("Missing parameter " + token);
                Object value = literal(parameters.get(number)); return x -> value;
            }
            if (token.startsWith("'") || token.matches("[-+]?(?:[0-9].*|\\.[0-9].*)") || token.equalsIgnoreCase("TRUE") || token.equalsIgnoreCase("FALSE")) {
                Object value = literal(token); return x -> value;
            }
            List<Method> methods = new ArrayList<>(); Class<?> current = type;
            try {
                for (String member : token.split("\\.")) { Method m = accessor(current, member); methods.add(m); current = m.getReturnType(); }
            } catch (NoSuchMethodException e) {
                if (mustBeField) throw invalid("Unknown field " + token);
                return x -> token; // IDL enumeration literal.
            }
            if (!(current.isPrimitive() || current.isEnum() || CharSequence.class.isAssignableFrom(current) || Number.class.isAssignableFrom(current))) throw invalid("A scalar field is required");
            return x -> {
                try { Object value = x; for (Method method : methods) value = method.invoke(value); return value instanceof CharSequence text ? text.toString() : value; }
                catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot access filter field", e); }
            };
        }
        Object literal(String token) {
            if (token.startsWith("'")) {
                if (token.length() < 2 || !token.endsWith("'")) throw invalid("Unterminated string literal");
                return token.substring(1, token.length() - 1).replace("''", "'");
            }
            if (token.equalsIgnoreCase("TRUE") || token.equalsIgnoreCase("FALSE")) return Boolean.valueOf(token);
            try { return new BigDecimal(token); } catch (NumberFormatException e) { return token; }
        }
    }
    private static Method accessor(Class<?> type, String member) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            var name = method.getAnnotation(org.eclipse.cyclonedds.topic.IdlName.class);
            if (name != null && name.value().equals(member) && method.getParameterCount() == 0) return method;
        }
        return type.getMethod("get_" + member);
    }
    private static String enumName(Enum<?> value) {
        try {
            var name = value.getDeclaringClass().getField(value.name()).getAnnotation(org.eclipse.cyclonedds.topic.IdlName.class);
            return name == null ? value.name() : name.value();
        } catch (NoSuchFieldException e) { throw new IllegalArgumentException("Invalid enum", e); }
    }
    private static int compare(Object a, Object b) {
        if (a instanceof Number n && b instanceof Number m) {
            if ((n instanceof Double || n instanceof Float) && !Double.isFinite(n.doubleValue()) ||
                    (m instanceof Double || m instanceof Float) && !Double.isFinite(m.doubleValue())) return Double.compare(n.doubleValue(), m.doubleValue());
            return new BigDecimal(n.toString()).compareTo(new BigDecimal(m.toString()));
        }
        if (a instanceof Enum<?> e) a = enumName(e);
        if (b instanceof Enum<?> e) b = enumName(e);
        if (a instanceof Character c) a = c.toString();
        if (b instanceof Character c) b = c.toString();
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
        throw new IllegalArgumentException("Incompatible operands in content filter");
    }
    private static boolean like(String value, String pattern) {
        StringBuilder regex = new StringBuilder("(?s)");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            regex.append(c == '%' ? ".*" : c == '_' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return value.matches(regex.toString());
    }
}
