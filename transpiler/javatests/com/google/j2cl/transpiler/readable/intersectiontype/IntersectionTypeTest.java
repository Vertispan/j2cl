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
package com.google.j2cl.transpiler.readable.intersectiontype;

interface Getable<T> {
  T get();
}

interface Setable {
  void set(int i);
}

interface Serial {}

interface Cmp {
  int cmp();
}

interface Cmp2 {
  int cmp(int a);
}

@SuppressWarnings({"TypeParameterUnusedInFormals", "unused"})
public class IntersectionTypeTest<U> {
  public static <T extends Getable<?> & Setable> void getAndSet(T object) {
    object.set(1);
    object.get();
  }

  class MapEntry {
    public <T> Getable<T> method(Object o) {
      return (Getable<T> & Setable) o;
    }
  }

  public static <T> Getable<T> cast(Object o) {

    // Show that the order does matter.
    if (o == null) {
      return (Setable & Getable<T>) o;
    }
    return (Getable<T> & Setable) o;
  }

  public static <T> Getable<Comparable<String>> cast1(Object s) {
    return (Getable<Comparable<String>> & Setable) s;
  }

  public static <T> Getable<Comparable<T>> cast2(Object s) {
    return (Getable<Comparable<T>> & Setable) s;
  }

  public Object cast3(Object s) {
    return s;
  }

  // TODO(tdeegan): Lambdas do not have the correct types applied yet.  Jdt only recognizes this
  // Lambda as Cmp whereas it should recognize Cmp and Serial.
  // https://bugs.eclipse.org/bugs/show_bug.cgi?id=496596
  public static Cmp method() {
    return (Cmp & Serial) () -> 1;
  }

  // This Lambda is type correctly
  public static Cmp2 method2() {
    return (Cmp2 & Serial) (a) -> 1;
  }

  private static class A {}

  private interface EmptyA {}

  private interface EmptyB {}

  public static void testClosureAssignment(Object o) {
    A e = (A & EmptyA & EmptyB) o;
    EmptyA g = (A & EmptyA & EmptyB) o;
    EmptyB s = (A & EmptyA & EmptyB) o;
  }

  private static <T> T get(T t) {
    return t;
  }

  private static <T extends A & EmptyA> T m() {
    return (T) get(new Object());
  }

  private static <T extends A & EmptyA> Getable<T> n() {
    return null;
  }

  private static <T extends A & EmptyA> void set(T t) {}

  public void testMethodCall() {
    Object o = m();
    set((A & EmptyA) o);
    set(m());
    // TODO(b/38243420): Uncomment the following line when bug is fixed.
    // Getable<?> g = n();
  }
}