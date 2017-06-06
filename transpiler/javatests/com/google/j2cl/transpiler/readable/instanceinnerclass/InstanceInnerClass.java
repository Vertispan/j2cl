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
package com.google.j2cl.transpiler.readable.instanceinnerclass;

public class InstanceInnerClass {
  public int instanceField;

  public void funOuter() {}

  public class InnerClass {
    public int field = instanceField + InstanceInnerClass.this.instanceField;
    public InstanceInnerClass enclosingInstance = InstanceInnerClass.this;

    public void funInner() {
      funOuter();
      InstanceInnerClass.this.funOuter();
    }
  }

  public void test() {
    new InstanceInnerClass().new InnerClass();
  }
}
