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
package com.google.j2cl.transpiler.integration.lambdasnestedscope;

/**
 * Test lambda nested in local class.
 */
public class LambdaNestingInLocalClass {
  public int f = 1;

  class InnerClass {
    public int f = 10;

    public int run(int a) {
      MyInterface intf = i -> LambdaNestingInLocalClass.this.f + f + i;
      return intf.fun(a);
    }
  }

  public void test() {
    assert new InnerClass().run(100) == 111;
  }
}
