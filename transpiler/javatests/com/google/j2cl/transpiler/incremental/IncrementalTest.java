package com.google.j2cl.transpiler.incremental;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.j2cl.transpiler.incremental.TranspilerTester.TranspileResult;
import org.junit.Test;

import static org.junit.Assert.*;
import static com.google.j2cl.transpiler.incremental.TypeGraphStore.hasImpactingState;

import static com.google.j2cl.transpiler.incremental.TranspilerTester.newTesterWithDefaults;

public class IncrementalTest {

    private String code(String... code) {
        List<String> content = new ArrayList<>(Arrays.asList(code));
        return Joiner.on('\n').join(content);
    }

    @Test
    public void testDependencyGraphWasBuiltCorrectly() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() {return j;}",
                           ///"  public class InnerFoo {}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) { return \"xxx\"; } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo3 foo3 = new Foo3();",
                "  } ",
                "  public class InnerBar {}",
                "}");

        TranspileResult results = Helper.create()
                                        .addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .addJava("Foo3", foo3)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Bar$InnerBar", "test.Foo1", "test.Foo2", "test.Foo3");

        Set<TypeInfo>    impact = getImpactingTypes(refMan);
        assertEquals(2, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo2"} ), result); // Foo3 is not part of this, because has no naming changing JsInterop annot.

        TypeInfo foo1Info = refMan.getStore().get("?test.Foo1");
        List<String> callers = asCallerListOfString(foo1Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);  // Once for the method, again for the returned Class

        TypeInfo foo2Info = refMan.getStore().get("?test.Foo2");
        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar", "Bar"} ), callers);  // repeats twice, one for Method one for new Class

        TypeInfo foo3Info = refMan.getStore().get("?test.Foo3");
        callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);

        TypeInfo barInfo = refMan.getStore().get("?test.Bar");
        callers = asCallerListOfString(barInfo.getIncomingDependencies());
        assertTrue(callers.isEmpty());

        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo1", "Foo2", "Foo2", "Foo2", "Foo3", "Foo3"} ), callees);
    }

    @Test
    public void testRemoveTypeAndUpdateUsage() throws IOException {
        // later removes Foo and updates Bar

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
                           "  public class InnerFoo {}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) { return \"xxx\"; } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "      Foo3 foo3 = new Foo3();",
                "  } ",
                "  public class InnerBar {}",
                "}");

        String bar2 = code( // No longer uses Foo1, that was deleted, but also no longer uses Foo3, which is still there.
                "public class Bar {",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "  } ",
                "  public class InnerBar {}",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .addJava("Foo3", foo3)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Bar$InnerBar", "test.Foo1", "test.Foo1$InnerFoo", "test.Foo2", "test.Foo3");

        Set<TypeInfo>    impact = getImpactingTypes(refMan);
        assertEquals(3, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"InnerFoo", "Foo1", "Foo2", } ), result);  // Foo3 is not part of this, because has no naming changing JsInterop annot.

        TypeInfo foo3Info = refMan.getStore().get("?test.Foo3");
        List<String> callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);  // repeats twice, one for Method one for new Class

        results = helper.remove("Foo1")
                        .addJava("Bar", bar2)
                        .assertTranspileSucceeds();

        refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Bar$InnerBar", "test.Foo2", "test.Foo3");

        impact = getImpactingTypes(refMan);
        assertEquals(1, impact.size());
        result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo2"} ), result); // Only Foo2 left

        TypeInfo foo2Info = refMan.getStore().get("?test.Foo2");
        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
        assertTrue(foo2Info.getOutgoingDependencies().isEmpty());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar", "Bar"} ), callers);  // repeats three, one for Variable, Method and Class

        foo3Info = refMan.getStore().get("?test.Foo3"); // Bar called it before, it doesn't any more
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());

        TypeInfo barInfo = refMan.getStore().get("?test.Bar");
        callers = asCallerListOfString(barInfo.getIncomingDependencies());
        assertTrue(callers.isEmpty());

        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        // Foo2 is repeated three, one for new Foo2, one for var Foo2 and once for helloWorld method call
        // Foo1 and Foo2 are not longer called.
        assertEquals(Arrays.asList(new String[] {"Foo2", "Foo2", "Foo2"} ), callees);
    }

    @Test
    public void testInnerTypesConstruction() throws IOException  {
        // This test exhaustively asserts on the structure of the Dependencies.
        // Later tests take the same sources, but removes and updates parts

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        assertRootInnerTypes(refMan,"test.Foo1",
                             "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");
        assertRootInnerTypes(refMan,"test.Bar");

        Set<TypeInfo>    impact = getImpactingTypes(refMan);
        assertEquals(4, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"InnerInnerFoo1Fum1", "InnerFoo1", "InnerInnerFoo1Fum2", "Foo1"} ), result);

        TypeInfo barInfo = refMan.getStore().get("?test.Bar");
        assertTrue(barInfo.getIncomingDependencies().isEmpty());

        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo1", "Foo1", "Foo1", "InnerFoo1", "InnerFoo1",
                                                 "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum2", "InnerInnerFoo1Fum2"} ), callees);

        TypeInfo foo1Info = refMan.getStore().get("?test.Foo1");
        assertTrue(foo1Info.getOutgoingDependencies().isEmpty());
        List<String> callers = asCallerListOfString(foo1Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar","Bar","Bar","Bar"} ), callers);

        assertEquals(1, foo1Info.getInnerTypes().size());
        TypeInfo foo2Info = foo1Info.getInnerTypes().get(0);
        assertTrue(foo2Info.getOutgoingDependencies().isEmpty());
        assertEquals(foo1Info, foo2Info.getEnclosingTypeInfo());
        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);

        assertEquals(2, foo2Info.getInnerTypes().size());
        TypeInfo foo3Info = foo2Info.getInnerTypes().get(0);
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertEquals(foo2Info, foo3Info.getEnclosingTypeInfo() );
        assertEquals(foo2Info, foo3Info.getEnclosingTypeInfo() );
        callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);

        TypeInfo foo4Info = foo2Info.getInnerTypes().get(1);
        assertTrue(foo4Info.getOutgoingDependencies().isEmpty());
        assertEquals(foo2Info, foo4Info.getEnclosingTypeInfo() );
        callers = asCallerListOfString(foo4Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);
    }

    @Test
    public void testInnerTypesWithBarRemoval() throws IOException  {
        // Further testing testInnerTypesConstruction but with Bar removal

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        TypeInfo foo1Info = refMan.getStore().get("?test.Foo1");
        TypeInfo foo2Info = refMan.getStore().get("?test.Foo1$InnerFoo1");
        TypeInfo foo3Info = refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1");
        TypeInfo foo4Info = refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        // all false
        assertFalse(asCallerListOfString(foo1Info.getIncomingDependencies()).isEmpty());
        assertFalse(asCallerListOfString(foo2Info.getIncomingDependencies()).isEmpty());
        assertFalse(asCallerListOfString(foo3Info.getIncomingDependencies()).isEmpty());
        assertFalse(asCallerListOfString(foo4Info.getIncomingDependencies()).isEmpty());

        results = helper.remove("Bar")
                        .assertTranspileSucceeds();

        // No need to test data structures here, as they are tested in testInnerTypesConstruction
        // Now test Bar removal
        refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan);
        assertContainedTypeInfos(refMan, "test.Foo1", "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        foo1Info = refMan.getStore().get("?test.Foo1");
        foo2Info = refMan.getStore().get("?test.Foo1$InnerFoo1");
        foo3Info = refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1");
        foo4Info = refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        // all true
        assertTrue(asCallerListOfString(foo1Info.getIncomingDependencies()).isEmpty());
        assertTrue(asCallerListOfString(foo2Info.getIncomingDependencies()).isEmpty());
        assertTrue(asCallerListOfString(foo3Info.getIncomingDependencies()).isEmpty());
        assertTrue(asCallerListOfString(foo4Info.getIncomingDependencies()).isEmpty());
    }

    @Test
    public void testInnerTypesWithFooRemoval() throws IOException  {
        // Further testing testInnerTypesConstruction but with Bar update and Foo removal

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        String bar2 = code(
                "public class Bar {",
                "  public Bar() { }",
                "  public void method1() {",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        assertRootInnerTypes(refMan,"test.Foo1",
                             "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");
        assertRootInnerTypes(refMan,"test.Bar");

        Set<TypeInfo>    impact = getImpactingTypes(refMan);
        assertEquals(4, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"InnerInnerFoo1Fum1", "InnerFoo1", "InnerInnerFoo1Fum2", "Foo1"} ), result);

        TypeInfo barInfo = refMan.getStore().get("?test.Bar");
        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo1", "Foo1", "Foo1",
                                                 "InnerFoo1", "InnerFoo1",
                                                 "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum1",
                                                 "InnerInnerFoo1Fum2", "InnerInnerFoo1Fum2"} ), callees);

        assertNotNull(refMan.getStore().get("?test.Foo1"));
        assertNotNull(refMan.getStore().get("?test.Foo1$InnerFoo1"));
        assertNotNull(refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
        assertNotNull(refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));

        // Now test Foo removal
        results = helper.addJava("Bar", bar2)
                        .remove("Foo1")
                        .assertTranspileSucceeds();

        refMan = results.getTypeGraphManager();
        assertEquals(0, getImpactingTypes(refMan).size());
        assertTranspiledFiles(refMan, "test.Bar");
        assertContainedTypeInfos(refMan, "test.Bar");
        assertRootInnerTypes(refMan,"test.Foo1");
        assertRootInnerTypes(refMan,"test.Bar");

        barInfo = refMan.getStore().get("?test.Bar");

        assertTrue(barInfo.getIncomingDependencies().isEmpty());
        assertTrue(barInfo.getOutgoingDependencies().isEmpty());

        assertNull(refMan.getStore().get("?test.Foo1"));
        assertNull(refMan.getStore().get("?test.Foo1$InnerFoo1"));
        assertNull(refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
        assertNull(refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));
    }

    @Test
    public void testInnerTypesWithFooUpdate() throws IOException  {
        // Further testing testInnerTypesConstruction but with Bar update and Foo removal

        String foo1_1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String foo1_2 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar_1 = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        String bar_2 = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1_1)
                                        .addJava("Bar", bar_1)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");

        assertRootInnerTypes(refMan,"test.Foo1",
                             "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");
        assertRootInnerTypes(refMan,"test.Bar");


        assertNotNull(refMan.getStore().get("?test.Foo1"));
        assertNotNull(refMan.getStore().get("?test.Foo1$InnerFoo1"));
        assertNotNull(refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
        assertNotNull(refMan.getStore().get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));

        // Now test Foo removal
        results = helper.addJava("Bar", bar_2)
                        .addJava("Foo1", foo1_2)
                        .assertTranspileSucceeds();

        refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");

        assertRootInnerTypes(refMan,"test.Foo1",
                             "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");
        assertRootInnerTypes(refMan,"test.Bar");
    }

    @Test
    public void testInnerTypesWithFooUpdateIntermediary() throws IOException  {
        // Further testing testInnerTypesConstruction but with Bar update and Foo removal

        String foo1_1 = code("import jsinterop.annotations.JsType;",
                             "@JsType(name = \"Faz\")",
                             "public class Foo1 {",
                             "  public void method1() { }",
                             "  public class InnerFoo1 {" +
                             "    public void innerMethod2() { }",
                             "    public class InnerInnerFoo1Fum1 {" +
                             "      public void innerInnerMethod3() { }",
                             "    }",
                             "    public class InnerInnerFoo1Fum2 {" +
                             "      public void innerInnerMethod4() { }",
                             "    }",
                             "  }",
                             "}");

        String foo1_2 = code("import jsinterop.annotations.JsType;",
                             "@JsType(name = \"Faz\")",
                             "public class Foo1 {",
                             "  public void method1() { }",
                             "  public class InnerFoo1 {" +
                             "    public void innerMethod2() { }",
                             "    public class InnerInnerFoo1Fum2 {" +
                             "      public void innerInnerMethod4() { }",
                             "    }",
                             "  }",
                             "}");

        String bar_1 = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        String bar_2 = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1_1)
                                        .addJava("Bar", bar_1)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");

        // Now test Foo removal
        helper.addJava("Bar", bar_2)
              .addJava("Foo1", foo1_2);
        //.assertTranspileSucceeds();

        // test intermediary data structures, off .dat file
        TypeGraphStore store = buildTypeGraphStore(helper);
        ChangeSet changeSet = store.getChangeSets().values().toArray(new ChangeSet[1])[0];
        assertEquals(1, store.getInnerTypesChanged().size()); // now check intermediary
        assertEquals(3, store.getInnerTypesChanged().get("?test.Foo1").size()); // now check intermediary
        helper.tester  = null;
    }

    private TypeGraphStore buildTypeGraphStore(Helper helper) throws IOException {
        Path inputPath = helper.tempPath.resolve("input");
        List<String> dirs = new ArrayList<>();
        dirs.add(inputPath.toString());

        helper.tester.generateFiles(inputPath);

        TypeGraphStore dependencyManager = new TypeGraphStore();
        dependencyManager.calculateChangeSet(helper.tester.getOutputPath(), dirs);
        return dependencyManager;
    }

    @Test
    public void testContinuationAfterError() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
                           "  public class InnerFoo {}",
                           "}");

        String foo1_1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public void getJ() { return j;}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo2_1 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return 5; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) { return \"xxx\"; } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "  } ",
                "}");

        String bar2 = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "      Foo3 foo3 = new Foo3();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2");

        results = helper.addJava("Foo1", foo1_1)
                        .assertTranspileFails();  // note it failed

        results = helper.addJava("Foo2", foo2_1)
                        .addJava("Foo3", foo3)
                        .addJava("Bar", bar2)
                        .assertTranspileFails();  // note it failed


        results = helper.addJava("Foo1", foo1)
                        .addJava("Foo2", foo2)
                        .addJava("Foo3", foo3)
                        .addJava("Bar", bar2)
                        .assertTranspileSucceeds(); // tbis time it succeeds

        refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
    }

    @Test
    public void testImpactUpdated() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
                           "  public class InnerFoo {}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) {",
                           "    Bar bar = new Bar();",
                           "    return \"xxx\";",
                           "  } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "    Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "    Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .addJava("Foo3", foo3)
                                        .addJava("Bar", bar)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan   = results.getTypeGraphManager();
        TypeInfo         foo1Info = refMan.getStore().get("?test.Foo1");
        TypeInfo foo2Info = refMan.getStore().get("?test.Foo2");
        TypeInfo foo3Info = refMan.getStore().get("?test.Foo3");
        TypeInfo barInfo = refMan.getStore().get("?test.Bar");

        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo",
                                 "test.Foo2", "test.Foo3");

        Set<TypeInfo>    impact = getImpactingTypes(refMan);
        assertEquals(3, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"InnerFoo", "Foo1", "Foo2"} ), result);

        // Foo3 and Bar are connected, but neither is in the impacted, as no JsInterop impact
        assertEquals(0, foo3Info.getIncomingDependencies().size());
        assertEquals(2, foo3Info.getOutgoingDependencies().size());

        // no dependencies are marked as having an impact
        List<Dependency> deps = foo3Info.getOutgoingDependencies().stream().filter(d -> d.getCalleeImpactingState() == ImpactingState.IS_IMPACTING).collect(Collectors.toList());
        assertEquals(0, deps.size());

        // Foo1 has JsType so it impacts the caller Bar
        assertEquals(6, foo1Info.getIncomingDependencies().size());
        assertEquals(0, foo1Info.getOutgoingDependencies().size());
        deps = foo1Info.getIncomingDependencies().stream().filter(d -> d.getCalleeImpactingState() == ImpactingState.IS_IMPACTING).collect(Collectors.toList());
        assertEquals(2, deps.size());

        // Foo2 has JsMethod so it impacts the caller Bar
        assertEquals(3, foo2Info.getIncomingDependencies().size());
        assertEquals(0, foo2Info.getOutgoingDependencies().size());
        deps = foo2Info.getIncomingDependencies().stream().filter(d -> d.getCalleeImpactingState() == ImpactingState.IS_IMPACTING).collect(Collectors.toList());
        assertEquals(1, deps.size());

        // Check Bar, should be combined inverse of Foo1 and Foo2
        assertEquals(2, barInfo.getIncomingDependencies().size());
        assertEquals(ImpactingState.NOT_IMPACTING, barInfo.getIncomingDependencies().get(0).getCalleeImpactingState());
        assertEquals(9, barInfo.getOutgoingDependencies().size());
        deps = barInfo.getOutgoingDependencies().stream().filter(d -> d.getCalleeImpactingState() == ImpactingState.IS_IMPACTING).collect(Collectors.toList());
        assertEquals(3, deps.size());

        // re-add Foo1, this impacts Bar
        results = helper.addJava("Foo1", foo1)
                        .assertTranspileSucceeds();

        refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");

        // re-add Foo3, nothing impacted
        results = helper.addJava("Foo3", foo3)
                        .assertTranspileSucceeds();

        refMan = results.getTypeGraphManager();

        assertTranspiledFiles(refMan, "test.Foo3");
    }

    @Test
    public void testNativeJs() throws IOException {
        String foo1 = code("public class Foo1 {",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;" +
                           "public class Foo2 {",
                           "  public String helloWorld() { return \"Hello World\"; } ",
                           "  @JsMethod\n",
                           "  public static native String goodbyeWorld();",
                           "}");

        String foo2_native_1 = code("Foo2.goodbyeWorld = function() {\n" +
                                  "  return \"Goodbye World1\";\n" +
                                  "};");

        String foo2_native_2 = code("Foo2.goodbyeWorld = function() {\n" +
                                    "  return \"Goodbye World2\";\n" +
                                    "};");


        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .addNative("Foo2", foo2_native_1)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan   = results.getTypeGraphManager();
        TypeInfo         foo1Info = refMan.getStore().get("?test.Foo1");
        TypeInfo         foo2Info = refMan.getStore().get("?test.Foo1");

        assertTranspiledFiles(refMan, "test.Foo1", "test.Foo2");


        results = helper.addNative("Foo2", foo2_native_2)
              .          assertTranspileSucceeds();

        refMan   = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Foo2");
    }

    @Test
    public void testNativeJsNoJavaChange() throws IOException {
        String foo1 = code("public class Foo1 {",
                           "}");

        // Notice there is no native or jsinterop, so the associated .native.js can change without this class actually changing
        String foo2 = code("public class Foo2 {",
                           "  public String helloWorld() { return \"Hello World\"; } ",
                           "}");

        String foo2_native_1 = code("Foo2.goodbyeWorld = function() {\n" +
                                    "  return \"Goodbye World1\";\n" +
                                    "};");


        Helper helper = Helper.create();
        TranspileResult results = helper.addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan   = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Foo1", "test.Foo2");


        results = helper.addNative("Foo2", foo2_native_1)
                        .          assertTranspileSucceeds();

        refMan   = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Foo2");
    }

    @Test
    public void testImplements() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "import jsinterop.annotations.JsMethod;",
                           "@JsType(name = \"Faz\")",
                           "public interface Foo1 {",
                           "  @JsMethod(name =\"getJ1\")",
                           "  public int getJ();",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsType;",
                           "import jsinterop.annotations.JsMethod;",
                           "@JsType(name = \"Foz\")",
                           "public interface Foo2 extends Foo1{",
                           "  @JsMethod(name =\"getI1\")",
                           "  public int getI();",
                           "}");

        String base = code(
                "public abstract class Base {",
                "  public Base() { }",
                "  public abstract int getK();",
                "}");

        String bar = code(
                "public class Bar extends Base implements Foo2 {",
                "  public Bar() {}",
                "  public int getI() {",
                "      return 10;",
                "  } ",
                "  public int getJ() {",
                "      return 11;",
                "  } ",
                "  public int getK() {",
                "      return 12;",
                "  } ",
                "}");

        String foo3 = code(
                "public class Foo3{",
                "  public Foo3() {}",
                "  public void doSomething() {",
                "      Foo1 foo1 = new Bar();",
                "      new Foo4().doSomething(foo1);",
                "  } ",
                "}");

        String foo4 = code(
                "public class Foo4{",
                "  public Foo4() {}",
                "  public void doSomething(Foo1 foo1x) {",
                "      foo1x.getJ();",
                "  } ",
                "}");



        TranspileResult results = Helper.create()
                                        .addJava("Foo1", foo1)
                                        .addJava("Foo2", foo2)
                                        .addJava("Foo3", foo3)
                                        .addJava("Foo4", foo4)
                                        .addJava("Bar", bar)
                                        .addJava("Base", base)
                                        .assertTranspileSucceeds();

        TypeGraphManager refMan = results.getTypeGraphManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Base", "test.Foo1", "test.Foo2", "test.Foo3", "test.Foo4");

        assertContainedTypeInfos(refMan, "test.Bar", "test.Base", "test.Foo1", "test.Foo2", "test.Foo3", "test.Foo4");

        TypeInfo foo4Info = refMan.getStore().get("?test.Foo4");
        assertEquals(2, foo4Info.getOutgoingDependencies().size());
        assertContainsMember(foo4Info.getOutgoingDependencies(),
                             Role.CLASS, "?test.Foo1");
        assertContainsMember(foo4Info.getOutgoingDependencies(),
                             Role.METHOD,"?test.Foo1", "getJ", "getJ()", "int" );

        assertContainsMember(foo4Info.getIncomingDependencies(),
                             Role.METHOD, "?test.Foo4", "$create", "$create()", "!test.Foo4");
        assertContainsMember(foo4Info.getIncomingDependencies(),
                             Role.METHOD,"?test.Foo4", "doSomething", "doSomething(test.Foo1)", "void" );


        TypeInfo barInfo = refMan.getStore().get("?test.Bar");
        assertContainsMember(barInfo.getOutgoingDependencies(),
                             Role.SUPER, "?test.Foo2");
        assertContainsMember(barInfo.getOutgoingDependencies(),
                             Role.SUPER, "?test.Base");
    }

    @Test
    public void testTranspilationAcrossModules() throws IOException {
        // simulates compiling across two modules, where one module depend on the other.
        String foo1Impacting =
                      code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public String getValue() {return \"hello\";}",
                           "}");

        String foo1NotImpacting =
                      code("public class Foo1 {",
                           "  public String getValue() {return \"hello\";}",
                           "}");

        String bar = code("public class Bar {",
                          "private Foo1 foo1 = new Foo1();",
                          "}");

        Helper helper1 = Helper.create();
        TranspileResult results1 = helper1.addJava("Foo1", foo1NotImpacting)
                                         .assertTranspileSucceeds();
        TypeGraphManager refMan1 = results1.getTypeGraphManager();
        assertTranspiledFiles(refMan1, "test.Foo1");
        TypeInfo foo1Info = refMan1.getStore().get("?test.Foo1");
        assertTrue(hasImpactingState(foo1Info, ImpactingState.NOT_IMPACTING));
        List<TypeInfo> cloned = refMan1.getStore().getImpactingTypeInfos();
        assertTrue(cloned.isEmpty());

        Helper helper2 = Helper.create(helper1.tempPath.resolve("classes").toAbsolutePath().toString());
        TranspileResult results2 = helper2.addJava("Bar", bar)
                                         .assertTranspileSucceeds();
        TypeGraphManager refMan2 = results2.getTypeGraphManager();
        assertTranspiledFiles(refMan2, "test.Bar");

        results1 = helper1.addJava("Foo1", foo1Impacting)
                                         .assertTranspileSucceeds();
        refMan1 = results1.getTypeGraphManager();
        assertTranspiledFiles(refMan1, "test.Foo1");
        foo1Info = refMan1.getStore().get("?test.Foo1");
        assertTrue(hasImpactingState(foo1Info, ImpactingState.IS_IMPACTING));
        cloned = refMan1.getStore().getImpactingTypeInfos();
        assertFalse(cloned.isEmpty());

        TypeGraphStore store = new TypeGraphStore();
        store.addAllToDelegate(cloned, store.getUniqueIdToPath());

        List<String> dirs = new ArrayList<>();
        Path inputPath = helper2.getTempPath().resolve("input");
        Path outputPath = helper2.getTempPath().resolve("output");
        dirs.add(inputPath.toString());
        store.calculateChangeSet(outputPath, dirs);

        ChangeSet changeSet = store.getChangeSets().get(inputPath.toString());
        assertEquals(1, changeSet.getImpacted().size());
        assertTrue(changeSet.getImpacted().contains("test/Bar.java"));
    }

    private List<String> asListOfString(Set<TypeInfo> impact) {
        return impact.stream().map(t -> t.getType().getSimpleSourceName()).collect(Collectors.toList());
    }

    private List<String> asCalleeListOfString(List<Dependency> deps) {
        return deps.stream().map(t -> t.getCallee().getType().getSimpleSourceName()).collect(Collectors.toList());
    }

    private List<String> asCallerListOfString(List<Dependency> deps) {
        return deps.stream().map(t -> t.getCaller().getType().getSimpleSourceName()).collect(Collectors.toList());
    }
    private void assertTranspiledFiles(final TypeGraphManager refMan, String... types) {
        assertEquals(types.length, refMan.getTranspiled().size()); // how many files did it transpile
        Arrays.stream(types).forEach(type -> assertTrue("Does not contain " + type, refMan.getTranspiled().contains("?" + type)));
    }

    private void assertContainedTypeInfos(TypeGraphManager refMan, String... types) {
        TypeGraphStore depManager = refMan.getStore();
        assertEquals(types.length, depManager.getTypeInfoLookup().size()); // how many files did it transpile
        Arrays.stream(types).forEach(type -> assertTrue("Does not contain " + type, depManager.getTypeInfoLookup().containsKey(type)));
    }

    private void assertRootInnerTypes(TypeGraphManager refMan, String rootType, String... types) {
        List<String> innerList = new ArrayList<>();
        TypeInfo typeInfo = refMan.getStore().get("?" + rootType);
        if (typeInfo != null) {
            refMan.getStore().buildInnerTypesList(innerList, typeInfo);
        }

        if (types == null || types.length == 0) {
            assertEquals( "There should be no inner types for " + rootType, 0, innerList.size());
            return;
        }
        Set<String> set = new HashSet(innerList);
        assertEquals(types.length, set.size()); // how many files did it transpile
        Arrays.stream(types).forEach(type -> assertTrue("Does not contain " + type, set.contains("?" + type)));
    }

    public Set<TypeInfo> getImpactingTypes(TypeGraphManager refMan) {
        TypeGraphStore depManager        = refMan.getStore();
        Set<TypeInfo>  typesImpactCaller = new HashSet<>();
        for ( TypeInfo typeInfo : depManager.getTypeInfoLookup().values() ) {
            for (MemberInfo memberInfo : typeInfo.getMembers().values()) {
                if (memberInfo.getImpactingState() == ImpactingState.IS_IMPACTING || memberInfo.getImpactingState() == ImpactingState.PREVIOUSLY_IMPACTING) {
                    typesImpactCaller.add(refMan.getStore().get("?" + memberInfo.getEnclosingType()));
                    break;
                }
            }
        }
        return typesImpactCaller;
    }

    public void assertContainsMember(List<Dependency> deps,
                                     Role memberRole, String enclosingType) {
        assertContainsMember(deps, memberRole, enclosingType, "", "", "");
    }

    public void assertContainsMember(List<Dependency> deps,
                                     Role memberRole, String enclosingType, String name, String signature, String returnType) {
        assertTrue(deps.stream().anyMatch(d->d.getMemberInfo().equals(new MemberInfo(memberRole, enclosingType.substring(1), name, signature, returnType))));
    }

    public static class Helper {
        Path             tempPath;

        TranspilerTester tester;

        private String  classPath;

        public static Helper create() {
            return create("");
        }

        public static Helper create(String classPath) {
            Helper helper = new Helper();
            helper.classPath = classPath;
            return helper;
        }

        private Helper() {
            try {
                tempPath = createPaths();
            } catch (IOException e) {
                throw new RuntimeException("Unable to create paths");
            }
        }

        public void newTester() {
            newTester(classPath);
        }

        public void newTester(String classPath) {
            tester = newTesterWithDefaults(tempPath.resolve("classes"), IncrementalTest.class, classPath)
                    .setTempPath(tempPath)
                    .setJavaPackage("test")
                    .setWriteTypeGraph(true);
            try {
                tester.createOutPath(tempPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public TranspilerTester getTranspilerTester() {
            return tester;
        }

        public Path getTempPath() {
            return tempPath;
        }

        Helper addJava(String type, String content) {
            if (tester==null) {
                newTester();
            }
            tester.addCompilationUnit(type, content);
            return  this;
        }

        Helper addNative(String type, String content) {
            if (tester==null) {
                newTester();
            }
            tester.addNativeFile(type, content);
            return  this;
        }

        Helper remove(String type) {
            if (tester==null) {
                newTester();
            }
            tester.removeCompilationUnit(type);
            return  this;
        }

        public TranspileResult assertTranspileSucceeds() {
            tester.compile();
            TranspileResult results =   tester.assertTranspileSucceeds();
            tester = null;
            return results;
        }

        public TranspileResult assertTranspileFails() {
            tester.compile();
            TranspileResult results =   tester.assertTranspileFails();
            tester = null;
            return results;
        }

        private Path createPaths() throws IOException {
            Path tempPath = Files.createTempDirectory("tester");
            Files.createDirectories(tempPath.resolve("classes"));

            System.out.println("tempPath\n " + tempPath);
            return tempPath;
        }
    }
}
