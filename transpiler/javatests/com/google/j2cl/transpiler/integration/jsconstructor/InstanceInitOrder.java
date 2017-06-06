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
package com.google.j2cl.transpiler.integration.jsconstructor;

import jsinterop.annotations.JsConstructor;

public class InstanceInitOrder {
  public static int initStep = 1;

  public static void test() {
    InstanceInitOrder m = new InstanceInitOrder();
    assert InstanceInitOrder.initStep == 6;
  }

  public int field1 = this.initializeField1();
  public int field2 = initializeField2();

  {
    assert initStep++ == 3; // #3
  }

  {
    assert initStep++ == 4; // #4
  }

  @JsConstructor
  public InstanceInitOrder() {
    assert initStep++ == 5; // #5
  }

  public int initializeField1() {
    assert initStep++ == 1; // #1
    return 0;
  }

  public int initializeField2() {
    assert initStep++ == 2; // #2
    return 0;
  }
}
