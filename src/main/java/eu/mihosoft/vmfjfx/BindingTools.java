/*
 * Copyright 2019-2020 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmfjfx;


import eu.mihosoft.vmf.runtime.core.ChangeListener;
import eu.mihosoft.vmf.runtime.core.VObject;
import javafx.beans.InvalidationListener;
import javafx.beans.property.Property;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public final class BindingTools {
    private BindingTools() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Selects a VMF property for binding to a JavaFX property.
     * @param vObj object to select the property from
     * @param propertyName name of the property
     * @return property binder
     */
    public static PropBinderUntyped selectPropOfObject(VObject vObj, String propertyName) {

        eu.mihosoft.vmf.runtime.core.Property prop =
                vObj.vmf().reflect().propertyByName(propertyName).orElseThrow();

        return new PropBinderUntyped(prop);
    }

    /**
     * Selects a VMF property for binding to a JavaFX property.
     * @param property property to bind
     * @return property binder
     */
    public static PropBinderUntyped selectProp(eu.mihosoft.vmf.runtime.core.Property property) {
        return new PropBinderUntyped(property);
    }

    /**
     * Untyped property binder.
     */
    public final static class PropBinderUntyped {

        private final eu.mihosoft.vmf.runtime.core.Property property;

        private PropBinderUntyped(eu.mihosoft.vmf.runtime.core.Property property) {
            this.property = property;
        }

        /**
         * returns a typed property binder with the specified converter.
         * @param converter converter to be used for property binding
         * @param <U> VMF property value type
         * @param <V> JavaFX property value type
         * @return
         */
        public <U,V> PropBinder<U,V> withConverter(BiFunction<eu.mihosoft.vmf.runtime.core.Property/*<U>*/, Property<V>, V> converter) {
            return new PropBinder<>(this, converter);
        }
    }

    /**
     * Property binder.
     * @param <T> VMF property value type
     * @param <R> JavaFX property value type
     */
    public final static class PropBinder<T,R> {

        private final PropBinderUntyped parent;
        private final BiFunction<eu.mihosoft.vmf.runtime.core.Property/*T*/, Property<R>, R> converter;
        private TriConsumer<eu.mihosoft.vmf.runtime.core.Property/*T*/,Property<R>, Exception> onError;
        private Property<R> prop;

        private PropBinder(PropBinderUntyped parent, BiFunction<eu.mihosoft.vmf.runtime.core.Property/*T*/, Property<R>, R> converter) {
            this.parent = parent;
            this.converter = converter;
        }

        /**
         * Includes the specified error handler to this binder.
         * @param onError consumer to execute on conversion error
         * @return this property binder
         */
        PropBinder<T,R> withErrorHandler(TriConsumer<eu.mihosoft.vmf.runtime.core.Property/*T*/,Property<R>, Exception> onError) {
            this.onError = onError;
            return this;
        }

        /**
         * Specifies the target property of the anticipated binding.
         * @param prop the target property
         * @return the FX property binder
         */
        FXBinder<T,R> withTargetProp(Property<R> prop) {
            this.prop = prop;
            return new FXBinder<>(this);
        }
    }

    /**
     * A binder describing the behavior of the binding and potential back synchronization
     * from the target property to the source property.
     * @param <T> VMF property value type
     * @param <R> JavaFX property value type
     */
    public final static class FXBinder<T,R> {

        private final PropBinder<T,R> propBinder;
        private TriConsumer<Property<R>,eu.mihosoft.vmf.runtime.core.Property/*T*/, Exception> onError;
        private Node backSyncNode;
        private BiFunction<Property<R>, eu.mihosoft.vmf.runtime.core.Property/*T*/, T> backSyncConverter;
        private Predicate<R> backSyncPred;

        private FXBinder(PropBinder<T,R> propBinder) {
            this.propBinder = propBinder;
        }

        /**
         * Includes the specified error handler to this binder.
         * @param onError consumer to execute on conversion error
         * @return this property binder
         */
        FXBinder<T,R> withErrorHandler(TriConsumer<Property<R>,eu.mihosoft.vmf.runtime.core.Property/*T*/, Exception> onError) {
            this.onError = onError;
            return this;
        }

        /**
         * Back-sync the target property to the VMF source property whenever the specified node receives an action event.
         * @param n node, e.g., a text input control
         * @param converter converter to use for binding
         * @return this binder
         */
        FXBinder<T,R> backSyncOnActionEventOf(Node n, BiFunction<Property<R>, eu.mihosoft.vmf.runtime.core.Property, T> converter) {
            this.backSyncNode = n;
            this.backSyncConverter = converter;
            this.backSyncPred = null;
            return this;
        }

        /**
         * Back-sync the target property to the VMF source property on change events if the specified predicate
         * is true.
         * @param predicate predicate that indicates when to back sync the target property
         * @param converter converter to use for binding
         * @return this binder
         */
        FXBinder<T,R> backSyncIf(Predicate<R> predicate, BiFunction<Property<R>, eu.mihosoft.vmf.runtime.core.Property, T> converter) {
            this.backSyncNode = null;
            this.backSyncConverter = converter;
            this.backSyncPred = predicate;
            return this;
        }

        /**
         * Back-sync the target property to the VMF source property on change events.
         * @param converter converter to use for binding
         * @return this binder
         */
        FXBinder<T,R> backSync(BiFunction<Property<R>, eu.mihosoft.vmf.runtime.core.Property, T> converter) {
            this.backSyncNode = null;
            this.backSyncConverter = converter;
            this.backSyncPred = null;
            return this;
        }

        /**
         * Terminal operation that performs the previously specified binding.
         * @return final binding
         */
        Binding<T,R> bind() {
            return new Binding(this);
        }
    }

    /**
     * The binding between a VMF property and a JavaFX property.
     * @param <T> VMF property value type
     * @param <R> JavaFX property value type
     */
    public static final class Binding<T,R> {

        private final FXBinder<T,R> fxBinder;
        private final List<Subscription> subscriptions = new ArrayList<>();

        private Binding(FXBinder<T, R> fxBinder) {
            this.fxBinder = fxBinder;

            subscriptions.add(setUpBindingVMFToJFX());
            subscriptions.add(setUpBindingJFXToVMF());
        }

        private Subscription setUpBindingVMFToJFX() {
            var propBinder = fxBinder.propBinder;
            var propBinderUntyped = propBinder.parent;
            var vmfToFX = propBinder.converter;
            var vmfProp = propBinderUntyped.property;
            var fxProp  = fxBinder.propBinder.prop;
            var onError = propBinder.onError;

            // vmf -> jfx
            ChangeListener l = (change) -> {
                try {
                    fxProp.setValue(vmfToFX.apply(vmfProp, fxProp));
                } catch (Exception ex) {
                    if(onError!=null) {
                        onError.accept(vmfProp, fxProp, ex);
                    } else {
                        throw new RuntimeException("binding broken",ex);
                    }
                }
            };

            Subscription s = vmfProp.addChangeListener(l);
            l.onChange(null);

            return s;
        }

        private Subscription setUpBindingJFXToVMF() {
            var propBinder = fxBinder.propBinder;
            var propBinderUntyped = propBinder.parent;
            var fxToVMF = fxBinder.backSyncConverter;
            var vmfProp = propBinderUntyped.property;
            var backSyncNode = fxBinder.backSyncNode;
            var backSyncPred = fxBinder.backSyncPred;
            var fxProp  = fxBinder.propBinder.prop;
            var onError = fxBinder.onError;

            if(fxToVMF==null) return ()->{};

            // jfx -> vmf
            InvalidationListener l = ov -> {
                try {
                    if(backSyncPred!=null && backSyncPred.test(fxProp.getValue())) {
                        vmfProp.set(fxToVMF.apply(fxProp, vmfProp));
                    } else if(backSyncPred==null) {
                        vmfProp.set(fxToVMF.apply(fxProp, vmfProp));
                    }
                } catch (Exception ex) {
                    if (onError != null) {
                        onError.accept(fxProp, vmfProp, ex);
                    } else {
                        throw new RuntimeException("binding broken", ex);
                    }
                }
            };

            Subscription s = null;

            if(backSyncNode==null) {
                if(backSyncPred!=null) {
                    fxProp.addListener(l);
                }
                s = () -> {
                    fxProp.removeListener(l);
                };
            } else {
                EventHandler<ActionEvent> aeHandler = (ae)->{
                    l.invalidated(fxProp);
                };
                backSyncNode.addEventHandler(ActionEvent.ACTION, aeHandler);
            }

            if(s!=null) {
                return s;
            } else {
                return () -> {
                };
            }
        }

        public Subscription getSubscription() {
            return null;
        }
    }

    /**
     * Double to string converter (vmf -> jfx).
     */
    public final static class DoubleToStringConverterVMF2JFX implements BiFunction<eu.mihosoft.vmf.runtime.core.Property, Property<String>, String> {
        private final int numDigits;

        /**
         * Constructor.
         * @param numDigits number of digits to show after the separator dot.
         */
        public DoubleToStringConverterVMF2JFX(int numDigits) {
            this.numDigits = numDigits;
        }

        @Override
        public String apply(eu.mihosoft.vmf.runtime.core.Property property, Property<String> stringProperty) {
            return String.format("%."+numDigits+"f", property.get());
        }
    }

    /**
     * String to double converter (jfx -> vmf).
     */
    public static class StringToDoubleConverterVMF2JFX implements BiFunction<javafx.beans.property.Property<String>, eu.mihosoft.vmf.runtime.core.Property, Object> {

        @Override
        public Double apply(javafx.beans.property.Property<String> propertyJFX, eu.mihosoft.vmf.runtime.core.Property propertyVMF) {
            return Double.parseDouble(propertyJFX.getValue());
        }
    }

    /**
     * Integer to String converter (vmf -> jfx).
     */
    public final static class IntegerToStringConverterVMF2JFX implements BiFunction<eu.mihosoft.vmf.runtime.core.Property/*<int>*/, Property<String>, String> {

        @Override
        public String apply(eu.mihosoft.vmf.runtime.core.Property property, Property<String> stringProperty) {
            return String.format("%d", property.get());
        }
    }

    /**
     * String to Integer converter (jfx -> vmf).
     */
    public static class StringToIntegerConverterJFX2VMF implements BiFunction<javafx.beans.property.Property<String>, eu.mihosoft.vmf.runtime.core.Property/*<int>*/, Object> {

        @Override
        public Integer apply(javafx.beans.property.Property<String> propertyJFX, eu.mihosoft.vmf.runtime.core.Property propertyVMF) {
            return Integer.parseInt(propertyJFX.getValue());
        }
    }

    /**
     * Represents an operation that accepts three input arguments and returns no
     * result. This is the tertiary specialization of {@link java.util.function.Consumer}.
     * Unlike most other functional interfaces, {@code TriConsumer} is expected
     * to operate via side-effects.
     * <p>
     * <p>This is a <a href="package-summary.html">functional interface</a>
     * whose functional method is {@link #accept(Object, Object, Object)}.
     *
     * @param <S> the type of the first argument to the operation
     * @param <T> the type of the second argument to the operation
     * @param <U> the type of the third argument to the operation
     * @see {@link java.util.function.Consumer}, {@link BiConsumer}
     */
    @FunctionalInterface
    public interface TriConsumer<S,T,U> {

        /**
         * Performs this operation on the given arguments.
         *
         * @param s the first input argument
         * @param t the second input argument
         * @param u the third input argument
         */
        void accept(S s,T t,U u);

        /**
         * Returns a composed {@code TriConsumer} that performs, in sequence, this
         * operation followed by the {@code after} operation. If performing either
         * operation throws an exception, it is relayed to the caller of the
         * composed operation.  If performing this operation throws an exception,
         * the {@code after} operation will not be performed.
         *
         * @param after the operation to perform after this operation
         * @return a composed {@code TriConsumer} that performs in sequence this
         * operation followed by the {@code after} operation
         * @throws NullPointerException if {@code after} is null
         */
        default TriConsumer<S, T, U> andThen(TriConsumer<? super S, ? super T, ? super U> after) {
            Objects.requireNonNull(after);

            return (l, r, s) -> {
                accept(l, r, s);
                after.accept(l, r, s);
            };
        }
    }
}





