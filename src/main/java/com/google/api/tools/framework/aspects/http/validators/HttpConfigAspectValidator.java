/*
 * Copyright (C) 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.api.tools.framework.aspects.http.validators;

import com.google.api.HttpRule;
import com.google.api.tools.framework.aspects.http.HttpConfigAspect;
import com.google.api.tools.framework.aspects.http.model.HttpAttribute;
import com.google.api.tools.framework.aspects.http.model.MethodKind;
import com.google.api.tools.framework.model.ConfigValidator;
import com.google.api.tools.framework.model.DiagReporter;
import com.google.api.tools.framework.model.DiagReporter.ResolvedLocation;
import com.google.api.tools.framework.model.Field;
import com.google.api.tools.framework.model.FieldSelector;
import com.google.api.tools.framework.model.MessageType;
import com.google.api.tools.framework.model.Method;
import com.google.api.tools.framework.model.TypeRef;
import com.google.api.tools.framework.model.TypeRef.WellKnownType;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Set;

/**
 * Validates different parts of http configuration to ensure things like:
 * <li> Body, Path, Query, Response parameters has valid values.
 * <li> Configuration conforms to the api style guide.
 *     <p>TODO(user): Split this validation into more logical components.
 */
public class HttpConfigAspectValidator extends ConfigValidator<Method> {

  private static final ImmutableSet<String> ALLOWED_REPEATED_FIELDS_IN_QUERY_PARAM =
      ImmutableSet.of(
          // This map field is specially handled by the framework; it is not expected to be mapped
          // to a query parameter.
          "google.api.HttpBody.extensions");

  public HttpConfigAspectValidator(DiagReporter diagReporter) {
    super(diagReporter, HttpConfigAspect.NAME, Method.class);
  }

  @Override
  public void run(Method method) {
    HttpAttribute binding = method.getAttribute(HttpAttribute.KEY);
    if (binding != null) {
      validate(method, binding);
      for (HttpAttribute additionalBinding : binding.getAdditionalBindings()) {
        validateAdditionalBindingConstraints(method, additionalBinding.getHttpRule());
        validate(method, additionalBinding);
      }
    }
  }

  private void validate(Method method, HttpAttribute binding) {
    MethodKind kind = binding.getMethodKind();
    checkBodyConstraints(binding, method);

    checkOverlappingPathSelectors(method, binding);

    checkResponseObject(method, kind);

    checkQueryParameterConstraints(method, binding);

    checkPathParameterConstraints(method, binding.getPathSelectors());
  }

  private void checkQueryParameterConstraints(Method method, HttpAttribute binding) {
    checkHttpQueryParameterConstraints(
        method,
        FluentIterable.from(binding.getParamSelectors())
            .transform(
                new Function<FieldSelector, Field>() {
                  @Override
                  public Field apply(FieldSelector selector) {
                    return Iterables.getLast(selector.getFields());
                  }
                }),
        Sets.<MessageType>newHashSet());
  }

  private void checkResponseObject(Method method, MethodKind kind) {
    if (kind != MethodKind.NONE) {
      WellKnownType wkt = TypeRef.of(method.getOutputMessage()).getWellKnownType();
      if (!wkt.allowedAsHttpRequestResponse()) {
        error(
            ResolvedLocation.create(method.getLocation()),
            "type '%s' is not allowed as a response because it does not render as "
                + "a JSON object.",
            method.getOutputMessage().getFullName());
      }
    }
  }

  // TODO(user): Investigate if we need an equality check here.
  @SuppressWarnings("ReferenceEquality")
  private void checkOverlappingPathSelectors(Method method, HttpAttribute binding) {
    for (FieldSelector selector : binding.getPathSelectors()) {
      for (FieldSelector otherSelector : binding.getPathSelectors()) {
        if (selector != otherSelector && selector.isPrefixOf(otherSelector)) {
          error(
              ResolvedLocation.create(method.getLocation()),
              "path contains overlapping field paths '%s' and '%s'.",
              selector,
              otherSelector);
        }
      }
    }
  }

  private void checkBodyConstraints(HttpAttribute binding, Method method) {

    if (binding.getBody() != null && !binding.bodyCapturesUnboundFields()) {
      if (!FieldSelector.hasSinglePathElement(binding.getBody())) {
        error(
            ResolvedLocation.create(method.getLocation()),
            "body field path '%s' should not reference sub messages.",
            binding.getBody());
      } else {
        // There should be just one body as body is not unbounded.
        if (binding.getBodySelectors() != null && binding.getBodySelectors().size() == 1) {
          FieldSelector bodyField = Iterables.getOnlyElement(binding.getBodySelectors());
          if (bodyField != null) {
            WellKnownType wkt = bodyField.getType().getWellKnownType();
            if (!bodyField.getType().isMessage()
                || bodyField.getType().isRepeated()
                || !wkt.allowedAsHttpRequestResponse()) {
              error(
                  ResolvedLocation.create(method.getLocation()),
                  "body field path '%s' must be a non-repeated message.",
                  bodyField);
            }
          }
        }
      }
    }
  }

  /** Check context conditions on http parameters. */
  private void checkHttpQueryParameterConstraints(
      Method method, Iterable<Field> fields, Set<MessageType> visited) {
    for (Field field : fields) {
      checkHttpParameterConditions(method, field, visited);
    }
  }

  /** Check context conditions on http parameters. */
  private void checkHttpParameterConditions(Method method, Field field, Set<MessageType> visited) {
    TypeRef type = field.getType();
    WellKnownType wkt = type.getWellKnownType();
    if (type.isMap()) {
      error(
          ResolvedLocation.create(method.getLocation()),
          "map field '%s' referred to by message '%s' cannot be mapped as an HTTP parameter.",
          field.getFullName(),
          getInputMessageName(method));
      return;
    }

    if (type.isMessage()) {
      if (wkt.allowedAsHttpParameter()) {
        return;
      }
      if (!visited.add(type.getMessageType())) {
        error(
            ResolvedLocation.create(method.getLocation()),
            "cyclic message field '%s' referred to by message '%s' in method '%s' cannot be mapped "
                + "as an HTTP parameter.",
            field.getFullName(),
            getInputMessageName(method),
            method);
        return;
      }
      if (type.isRepeated()) {
        if (ALLOWED_REPEATED_FIELDS_IN_QUERY_PARAM.contains(field.getFullName())) {
          return;
        }

        error(
            ResolvedLocation.create(method.getLocation()),
            "repeated message field '%s' referred to by message '%s' cannot be mapped "
                + "as an HTTP parameter.",
            field.getFullName(),
            getInputMessageName(method));
      }
      checkHttpQueryParameterConstraints(method, type.getMessageType().getFields(), visited);
      visited.remove(type.getMessageType());
    }
  }

  private void checkPathParameterConstraints(Method method, Iterable<FieldSelector> pathSelectors) {
    for (FieldSelector selector : pathSelectors) {
      if (selector != null) {
        checkPathParameterConditions(method, selector);
      }
    }
  }

  /** Checks context conditions for selectors bound to the HTTP path. */
  private void checkPathParameterConditions(Method method, FieldSelector selector) {
    TypeRef type = selector.getType();
    WellKnownType wkt = type.getWellKnownType();
    if (type.isMap()) {
      error(
          ResolvedLocation.create(method.getLocation()),
          "map field not allowed: reached via '%s' on message '%s'.",
          selector.toString(),
          getInputMessageName(method));
    } else if (type.isRepeated()) {
      error(
          ResolvedLocation.create(method.getLocation()),
          "repeated field not allowed: reached via '%s' on message '%s'.",
          selector,
          getInputMessageName(method));
    } else if (type.isMessage() && !wkt.allowedAsPathParameter()) {
      error(
          ResolvedLocation.create(method.getLocation()),
          "message field not allowed: reached via '%s' on message '%s'.",
          selector,
          getInputMessageName(method));
    }
  }

  private void validateAdditionalBindingConstraints(Method method, HttpRule rule) {
    // Additional bindings must not specify more bindings or a selector.
    if (rule.getAdditionalBindingsCount() > 0) {
      error(
          ResolvedLocation.create(method.getLocation()),
          "rules in additional_bindings must not specify additional_bindings");
    }
    if (!rule.getSelector().isEmpty()) {
      error(
          ResolvedLocation.create(method.getLocation()),
          "rules in additional_bindings must not specify a selector");
    }
  }

  /** Helper to access the full name of the input (request) message of a method. */
  public static String getInputMessageName(Method method) {
    return method.getInputType().getMessageType().getFullName();
  }
}
