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
package com.google.j2cl.transpiler.integration.defaultinitvalue;

/**
 * Test default initial value.
 */
public class Main {
  public int instanceInt;
  public boolean instanceBoolean;
  public Object instanceObject;
  public static int staticInt;
  public static boolean staticBoolean;
  public static Object staticObject;

  public static void main(String[] args) {
    Main m = new Main();
    assert m.instanceInt == 0;
    assert !m.instanceBoolean;
    assert Main.staticInt == 0;
    assert !Main.staticBoolean;

    // Avoiding a "condition always evaluates to true" error in JSComp type checking.
    Object maybeNull = Main.staticInt == 0 ? null : new Object();
    assert Main.staticObject == maybeNull;
    assert m.instanceObject == maybeNull;
  }
}
