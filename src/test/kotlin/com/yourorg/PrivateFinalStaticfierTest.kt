package com.yourorg

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.openrewrite.java.Assertions.java
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

class PrivateFinalStaticfierTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(PrivateFinalStaticfier())
    }

    @Test
    fun changesPrivateFinalWithNoInstanceReferences() = rewriteRun(
        java(
            """
               package foo;
               class A {
                    private final <T> T echo(T value) {
                        return value;
                    }
               }
            """,
            """
               package foo;
               class A {
                    private static <T> T echo(T value) {
                        return value;
                    }
               }
            """
        )
    )

    @Test
    fun preservesModifierOrder() = rewriteRun(
        java(
            """
               package foo;
               class A {
                    final private <T> T echo(T value) {
                        return value;
                    }
               }
            """,
            """
               package foo;
               class A {
                    static private <T> T echo(T value) {
                        return value;
                    }
               }
            """
        )
    )

    @Test
    fun changesPrivateWithNoInstanceReferences() = rewriteRun(
        java(
            """
               package foo;
               class A {
                    private <T> T echo(T value) {
                        return value;
                    }
               }
            """,
            """
               package foo;
               class A {
                    private static <T> T echo(T value) {
                        return value;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangePrivateFinalWithInstanceRefs() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    int x;
                    private int add(int value) {
                        return x + value;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangePrivateFinalWithExplicitCallsToInstanceRefs() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    int x;
                    private int add(int value) {
                        return this.x + value;
                    }
               }
            """
        )
    )

    @Test
    fun changesPrivateFinalWithStaticRefs() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private static final int TEN = 10;
                    private int addTen(int value) {
                        int result =  TEN + value;
                        System.out.println(TEN + " " + value + " = " + result);
                        return result;
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static final int TEN = 10;
                    private static int addTen(int value) {
                        int result =  TEN + value;
                        System.out.println(TEN + " " + value + " = " + result);
                        return result;
                    }
               }
            """
        )
    )

    @Test
    fun changesPrivateFinalWithStaticMethodCalls() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private static final int TEN = 10;

                    public static int getTen() { return TEN; }

                    private int addTen(int value) {
                        int result = getTen() + value;
                        System.out.println(getTen() + " " + value + " = " + result);
                        return result;
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static final int TEN = 10;

                    public static int getTen() { return TEN; }

                    private static int addTen(int value) {
                        int result = getTen() + value;
                        System.out.println(getTen() + " " + value + " = " + result);
                        return result;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangeInterfaceMethod() = rewriteRun(
        java(
            """
               package foo;

               interface IFoo {
                    void foo();
               }
            """
        )
    )

    @Test
    fun doesNotChangeAbstractMethod() = rewriteRun(
        java(
            """
               package foo;

               abstract class AbstractFoo {
                    abstract void foo();
                    void bar() { foo(); }
               }
            """
        )
    )

    @Test
    fun doesNotChangePrivateFinalWithInstanceMethodCall() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    int x;

                    private int getX() { return x; }

                    private final int add(int value) {
                        return getX() + value;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangePrivateFinalWithChainedInstanceMethodCalls() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    final Object o = new Object();

                    private final int add(int value) {
                        return o.hashCode() + value;
                    }
               }
            """
        )
    )

    @Test
    fun changesDeclarationWithInstanceMethodCallOnStaticField() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private static final Object o = new Object();

                    private int add(int value) {
                        return o.hashCode() + value;
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static final Object o = new Object();

                    private static int add(int value) {
                        return o.hashCode() + value;
                    }
               }
            """
        )
    )

    @Test
    fun changesEmptyMethod() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private final void empty() { }
               }
            """,
            """
               package foo;

               class A {
                    private static void empty() { }
               }
            """
        )
    )

    @Test
    @Disabled
    // NOTE: This is a known failing case.  Tracking recursive call graphs is complicated...
    fun changesSelfReferentialInstanceMethods() = rewriteRun(
        java(
            """
               package foo;

               class PingPong {
                    private int ping(int x) {
                        if (x <= 1) { return x; }
                        return pong(x - 1);
                    }

                    private int pong(int x) {
                        if (x <= 1) { return x; }
                        return ping(x * 3 - 1);
                    }
               }
            """,
            """
               package foo;

               class PingPong {
                    private static int ping(int x) {
                        if (x <= 1) { return x; }
                        return pong(x - 1);
                    }

                    private static int pong(int x) {
                        if (x <= 1) { return x; }
                        return ping(x * 3 - 1);
                    }
               }
            """
        )
    )

    @Test
    fun changesRecursiveInstanceMethods() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private int fib(int n) {
                        if (n <= 1) { return 1; }
                        return fib(n - 2) + fib(n - 1);
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static int fib(int n) {
                        if (n <= 1) { return 1; }
                        return fib(n - 2) + fib(n - 1);
                    }
               }
            """
        )
    )

    @Test
    fun changesRecursiveInstanceMethodsWithExplicitThis() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private int fib(int n) {
                        if (n <= 1) { return 1; }
                        return this.fib(n - 2) + this.fib(n - 1);
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static int fib(int n) {
                        if (n <= 1) { return 1; }
                        return A.fib(n - 2) + A.fib(n - 1);
                    }
               }
            """
        )
    )

    @Test
    fun changesMethodReferencingStaticFieldWithSameName() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    public static final int foo = 1234;
                    private int foo(int n) {
                        return foo + n;
                    }
               }
            """,
            """
               package foo;

               class A {
                    public static final int foo = 1234;
                    private static int foo(int n) {
                        return foo + n;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangeMethodReferencingInstanceFieldWithSameName() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    public final int foo = 1234;
                    private int foo(int n) {
                        return foo + n;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangeMethodReferencingOverloadedInstanceMethod() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    public final int INSTANCE_CONST = 1234;

                    private int foo() {
                        return INSTANCE_CONST;
                    }

                    private int foo(int n) {
                        return foo() + n;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangeMethodReferencingSuperclassInstanceMethod() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private int foo(int n) {
                        return hashCode() * n;
                    }
               }
            """
        )
    )

    @Test
    fun changesReferenceToArrayLength() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private int foo(int n) {
                        int[] arr = new int[n];
                        return new int[n].length + arr.length;
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static int foo(int n) {
                        int[] arr = new int[n];
                        return new int[n].length + arr.length;
                    }
               }
            """
        )
    )

    @Test
    fun changesWithReferenceToFieldOnNewObject() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    int i;
                    private int foo(int n) {
                        return new A().i + n;
                    }
               }
            """,
            """
               package foo;

               class A {
                    int i;
                    private static int foo(int n) {
                        return new A().i + n;
                    }
               }
            """
        )
    )

    @Test
    fun doesNotChangeChainedMethodCallsFromInstanceObject() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    Object o = new Object();
                    private int foo() {
                        return o.toString().length();
                    }
               }
            """
        )
    )

    @Test
    fun changesChainedMethodCallsFromStaticObject() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    static Object o = new Object();
                    private int foo() {
                        return o.toString().length();
                    }
               }
            """,
            """
               package foo;

               class A {
                    static Object o = new Object();
                    private static int foo() {
                        return o.toString().length();
                    }
               }
            """
        )
    )

    @Test
    fun changesReferenceToArrayClone() = rewriteRun(
        java(
            """
               package foo;

               class A {
                    private int[] foo(int n) {
                        return new int[n].clone();
                    }
               }
            """,
            """
               package foo;

               class A {
                    private static int[] foo(int n) {
                        return new int[n].clone();
                    }
               }
            """
        )
    )

    // this example taken from https://rules.sonarsource.com/java/RSPEC-2325
    @Test
    fun updatesRSPEC2325Example() = rewriteRun(
        java(
            """
                class Utilities {
                    private static String magicWord = "magic";

                    private String getMagicWord() {
                        return magicWord;
                    }

                    private void setMagicWord(String value) {
                        magicWord = value;
                    }
                }
            """,
            """
                class Utilities {
                    private static String magicWord = "magic";

                    private static String getMagicWord() {
                        return magicWord;
                    }

                    private static void setMagicWord(String value) {
                        magicWord = value;
                    }
                }
            """
        )
    )
}