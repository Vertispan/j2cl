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
package com.google.j2cl.transpiler.integration.jsproperties;

import jsinterop.annotations.JsProperty;

/**
 * Tests for non native static JsProperty.
 */
public class Foo {
  private static int f = 10;

  public static int getF() {
    return f;
  }

  @JsProperty
  public static int getA() {
    return f + 1;
  }

  @JsProperty
  public static void setA(int a) {
    f = a + 2;
  }

  @JsProperty(name = "abc")
  public static int getB() {
    return f + 3;
  }

  @JsProperty(name = "abc")
  public static void setB(int a) {
    f = a + 4;
  }
}
