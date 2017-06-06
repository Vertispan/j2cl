/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.j2cl.transpiler.integration.nonnullablecomplex;

import com.google.j2cl.transpiler.integration.nonnullablecomplex.sub.NonNullableExample;
import com.google.j2cl.transpiler.integration.nonnullablecomplex.sub.NullableExample;

public class Main {

  public static void main(String[] args) {
    /**
     * Both these classes are from the same package but one of them is from a target that includes a
     * package-info file that sets a default of non-nullable, and the other is from a target that
     * doesn't have the package-info file at all and so assumes a default of nullable.
     *
     * <p>
     * J2CL should be able to see the mismatch and automatically generate a cast to satisfy
     * JSCompiler's null checker. If it does not see the mismatch (because it does not understand
     * which package-info files are associated with which packages) then it will not generate the
     * cast.
     */
    NonNullableExample.takeValue(NullableExample.NULLABLE_VALUE);
  }
}
