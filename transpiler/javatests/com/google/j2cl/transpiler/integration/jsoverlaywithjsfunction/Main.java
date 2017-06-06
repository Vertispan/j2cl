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
package com.google.j2cl.transpiler.integration.jsoverlaywithjsfunction;

import static jsinterop.annotations.JsPackage.GLOBAL;

import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsType;

public class Main {
  public static void main(String... args) {
    assert new Foo().bar() == 30;
  }

  @JsType(isNative = true, namespace = GLOBAL, name = "Object")
  public static class Foo {
    @JsOverlay
    public final int bar() {
      return new Intf() {
        @Override
        public int run(int x, int y) {
          return x + y;
        }
      }.run(10, 20);
    }
  }

  @JsFunction
  private interface Intf {
    int run(int x, int y);
  }
}
