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
package com.google.j2cl.transpiler.integration.morebridgemethods;

public class TestCase10092 {
  static interface BI1 {
    @SuppressWarnings("unused")
    default String get(String value) {
      return "BI1 get String";
    }
  }

  abstract static class B implements BI1 {}

  static class C extends B {}

  @SuppressWarnings("unchecked")
  public static void test() {
    C c = new C();
    assert ((B) c).get("").equals("BI1 get String");
    assert c.get("").equals("BI1 get String");
    assert ((BI1) c).get("").equals("BI1 get String");
  }
}
