/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.j2cl.ast.annotations.Visitable;
import com.google.j2cl.common.SourcePosition;

/** Class for instanceof Expression. */
@Visitable
public class InstanceOfExpression extends Expression implements HasSourcePosition {
  @Visitable Expression expression;
  @Visitable TypeDescriptor testTypeDescriptor;
  SourcePosition sourcePosition;

  public InstanceOfExpression(
      SourcePosition sourcePosition, Expression expression, TypeDescriptor testTypeDescriptor) {
    this.expression = checkNotNull(expression);
    this.testTypeDescriptor = checkNotNull(testTypeDescriptor);
    this.sourcePosition = sourcePosition;
  }

  public Expression getExpression() {
    return expression;
  }

  public TypeDescriptor getTestTypeDescriptor() {
    return testTypeDescriptor;
  }

  @Override
  public TypeDescriptor getTypeDescriptor() {
    return TypeDescriptors.get().primitiveBoolean;
  }

  @Override
  public boolean isIdempotent() {
    return expression.isIdempotent();
  }

  @Override
  public InstanceOfExpression clone() {
    return new InstanceOfExpression(sourcePosition, expression.clone(), testTypeDescriptor);
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_InstanceOfExpression.visit(processor, this);
  }

  @Override
  public SourcePosition getSourcePosition() {
    return sourcePosition;
  }

  @Override
  public void setSourcePosition(SourcePosition sourcePosition) {
    this.sourcePosition = sourcePosition;
  }
}