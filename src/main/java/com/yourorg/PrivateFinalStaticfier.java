package com.yourorg;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.java.tree.J.Modifier;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.java.tree.J.Modifier.Type.*;

/**
 * Finds private instance methods that make no references to instance fields or methods, and makes them static.
 *
 * Takes a class like:
 *
 * <pre>
 * class A {
 *   private void foo() {
 *     System.out.println("No instance data used!");
 *   }
 *
 *   private void bar() {
 *     System.out.println("Here's my hashcode! " + this.hashCode()));
 *   }
 * }
 * </pre>
 *
 * and converts it to:
 *
 * <pre>
 * class A {
 *   private static void foo() {
 *     // Becomes static!
 *     System.out.println("No instance data used!");
 *   }
 *
 *   private void bar() {
 *     // Still an instance method!
 *     System.out.println("Here's my hashcode! " + this.hashCode()));
 *   }
 * }
 * </pre>
 *
 * @see <a href="https://rules.sonarsource.com/java/RSPEC-2325">https://rules.sonarsource.com/java/RSPEC-2325</a>
 */
public class PrivateFinalStaticfier extends Recipe {
    @Override
    public String getDisplayName() {
        return "Private Final Staticfier";
    }

    @Override
    public String getDescription() {
        return "Makes any private final methods that do not use instance data into static methods.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new PrivateFinalVisitor();
    }

    /**
     * Visits a method declaration and adds the `static` modifier if it's private and does not reference any
     * instance fields or methods.
     */
    public static class PrivateFinalVisitor extends JavaIsoVisitor<ExecutionContext> {
        /**
         * Template for replacing occurrences of the "this" keyword with the name of the class containing the method
         * declaration.
         */
        private final JavaTemplate replaceThisTemplate = JavaTemplate.builder(this::getCursor, "#{any(java.lang.Class)}").build();

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
            J.MethodDeclaration m = method;

            // First check: method signature (private final, and not already static)
            List<Modifier> modifiers = m.getModifiers();
            if (!Modifier.hasModifier(modifiers, Private) || Modifier.hasModifier(modifiers, Static)) {
                // not `private`, or is already `private static`, so not a method we're interested in
                // This also filters out any interface or abstract methods, since abstract methods can't be private
                return m;
            }

            // Second check: no instance variable or instance method references
            // Note: doesn't work if there's a recursive call graph with more than one method (the current one) in it
            if (hasInstanceReference(m)) {
                return m;
            }

            List<Modifier> newModifiers = new ArrayList<>(modifiers);

            Optional<Modifier> maybeFinalModifier = modifiers.stream().filter(mod -> mod.getType() == Final).findFirst();
            if (maybeFinalModifier.isPresent()) {
                // Replace the `final` modifier with the `static` one
                // `static` and `final` are mutually incompatible, so we'd generate invalid Java if we didn't remove
                // the `final` modifier
                Modifier finalModifier = maybeFinalModifier.get();
                Modifier staticModifier = new Modifier(randomId(), finalModifier.getPrefix(), Markers.EMPTY, Static, emptyList());
                newModifiers.add(modifiers.indexOf(finalModifier), staticModifier);
                newModifiers.remove(finalModifier);
            } else {
                // No `final` modifier - just tack the `static` modifier onto the end of the modifiers list
                Modifier staticModifier = new Modifier(randomId(), Space.build(" ", emptyList()), Markers.EMPTY, Static, emptyList());
                newModifiers.add(staticModifier);
            }

            // Replace the existing set of modifiers with the new one (adding `static`, removing `final` if present)
            m = method.withModifiers(newModifiers);

            executionContext.putMessage("CURRENT_CLASS_NAME", method.getMethodType().getDeclaringType().getClassName());

            // run super at the end so we only do AST manipulation inside the method body after determining to modify
            // the method
            return super.visitMethodDeclaration(m, executionContext);
        }

        @Override
        public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext executionContext) {
            if (identifier.getSimpleName().equals("this")) {
                // Is there a way to get this from the identifier?  Like walking up the tree?
                String currentClassName = executionContext.getMessage("CURRENT_CLASS_NAME");
                // replace `this` with the declaring class, eg `this.someVar` -> `MyClass.someVar`
                identifier = identifier.withTemplate(
                        replaceThisTemplate,
                        identifier.getCoordinates().replace(),
                        identifier.withSimpleName(currentClassName)
                );
            }
            return super.visitIdentifier(identifier, executionContext);
        }

        private boolean hasInstanceReference(J.MethodDeclaration method) {
            // Used as an "out" parameter, i.e. just a box containing a boolean.
            AtomicBoolean hasInstanceReference = new AtomicBoolean(false);
            new InstanceReferenceVisitor(method).visit(method, hasInstanceReference, getCursor());
            return hasInstanceReference.get();
        }
    }

    /**
     * Helper visitor used to track references to methods and variables within a single method declaration.
     */
    private static class InstanceReferenceVisitor extends JavaVisitor<AtomicBoolean> {
        private final J.MethodDeclaration method;
        private final JavaType.Method methodType;

        public InstanceReferenceVisitor(J.MethodDeclaration method) {
            this.method = method;
            assert method.getMethodType() != null;
            this.methodType = method.getMethodType();
        }

        @Override
        public J visitIdentifier(J.Identifier identifier, AtomicBoolean hasNonStaticReference) {
            if (!identifier.getSimpleName().equals("this") && identifier.getFieldType() != null && identifier.getFieldType().getOwner().equals(this.methodType.getDeclaringType()) && !identifier.getFieldType().hasFlags(Flag.Static)) {
                // reading an instance field from the declaring class
                hasNonStaticReference.set(true);
            }
            return super.visitIdentifier(identifier, hasNonStaticReference);
        }

        @Override
        public J visitFieldAccess(J.FieldAccess fieldAccess, AtomicBoolean hasNonStaticReference) {
            if (!fieldAccess.getName().getFieldType().hasFlags(Flag.Static)) {
                Expression target = fieldAccess.getTarget();
                if (target instanceof J.NewArray) {
                    // reading a field from a new array
                } else if (target instanceof J.Identifier && ((J.Identifier) target).getFieldType().getOwner() instanceof JavaType.Method) {
                    // reading from a local variable, which is always safe
                } else if (target instanceof J.NewClass) {
                    // reading directly from a new object, nothing to do
                } else {
                    hasNonStaticReference.set(true);
                }
            }
            return fieldAccess;
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method, AtomicBoolean hasNonStaticReference) {
            if (!method.getMethodType().hasFlags(Flag.Static) && !Objects.equals(this.methodType, method.getMethodType())) {
                // calling an instance method

                if (method.getSelect() == null) {
                    // Calling an instance method on `this` (rather than from off of another method, or from an object)
                    // NOTE: This will need to be changed to support multi-method recursion!
                    hasNonStaticReference.set(true);
                }
            }
            return super.visitMethodInvocation(method, hasNonStaticReference);
        }
    }
}
