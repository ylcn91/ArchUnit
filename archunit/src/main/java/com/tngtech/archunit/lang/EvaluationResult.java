/*
 * Copyright 2014-2022 TNG Technology Consulting GmbH
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
 */
package com.tngtech.archunit.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.tngtech.archunit.PublicAPI;
import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaClasses;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Ordering.natural;
import static com.tngtech.archunit.PublicAPI.State.EXPERIMENTAL;
import static com.tngtech.archunit.PublicAPI.Usage.ACCESS;
import static java.util.stream.Collectors.toList;

/**
 * Represents the result of evaluating an {@link ArchRule} against some {@link JavaClasses}.
 * To react to failures during evaluation of the rule, one can use {@link #handleViolations(ViolationHandler, Object[])}:
 * <br><br>
 * <pre><code>
 * result.handleViolations((Collection&lt;JavaAccess&lt;?&gt;&gt; violatingObjects, String message) -> {
 *     // do some reporting or react in any way to violation
 * });
 * </code></pre>
 */
public final class EvaluationResult {
    private final HasDescription rule;
    private final List<ConditionEvent> violations;
    private final Optional<String> informationAboutNumberOfViolations;
    private final Priority priority;

    @PublicAPI(usage = ACCESS)
    public EvaluationResult(HasDescription rule, Priority priority) {
        this(rule, ConditionEvents.Factory.create(), priority);
    }

    @PublicAPI(usage = ACCESS)
    public EvaluationResult(HasDescription rule, ConditionEvents events, Priority priority) {
        this.rule = rule;
        this.violations = new ArrayList<>(events.getViolating());
        this.informationAboutNumberOfViolations = events.getInformationAboutNumberOfViolations();
        this.priority = priority;
    }

    @PublicAPI(usage = ACCESS)
    public FailureReport getFailureReport() {
        ImmutableList<String> result = violations.stream()
                .flatMap(event -> event.getDescriptionLines().stream())
                .sorted(natural())
                .collect(toImmutableList());
        FailureMessages failureMessages = new FailureMessages(result, informationAboutNumberOfViolations);
        return new FailureReport(rule, priority, failureMessages);
    }

    @PublicAPI(usage = ACCESS)
    public void add(EvaluationResult part) {
        violations.addAll(part.violations);
    }

    /**
     * Passes violations to the supplied {@link ViolationHandler}. The passed violations will automatically
     * be filtered by the type of the given {@link ViolationHandler}. That is, if a
     * <code>ViolationHandler&lt;SomeClass&gt;</code> is passed, only violations by objects assignable to
     * <code>SomeClass</code> will be reported. Note that this will be unsafe for generics, i.e. ArchUnit
     * cannot filter to match the full generic type signature. E.g.
     * <pre><code>
     * handleViolations((Collection&lt;Optional&lt;String&gt;&gt; objects, String message) ->
     *     assertType(objects.iterator().next().get(), String.class)
     * )
     * </code></pre>
     * might throw an exception if there are also {@code Optional<Integer>} violations. Thus, when using
     * this method generic type parameters should always be substituted by wildcard types,
     * except if it is clear that the type parameter will always be set to a well known type.
     *
     * @param <T> Type of the relevant objects causing violations. E.g. {@code JavaAccess<?>}
     * @param violationHandler The violation handler that is supposed to handle all violations matching the
     *                         respective type parameter
     * @param __ignore_this_parameter_to_reify_type__ This parameter will be ignored; its only use is to make the
     *                                                generic type reified, so we can retrieve it at runtime.
     *                                                Otherwise, type erasure would make this impossible.
     */
    @SafeVarargs
    @SuppressWarnings("unused")
    @PublicAPI(usage = ACCESS, state = EXPERIMENTAL)
    public final <T> void handleViolations(ViolationHandler<T> violationHandler, T... __ignore_this_parameter_to_reify_type__) {
        Class<T> correspondingObjectType = componentTypeOf(__ignore_this_parameter_to_reify_type__);
        ConditionEvent.Handler eventHandler = convertToEventHandler(correspondingObjectType, violationHandler);
        for (final ConditionEvent event : violations) {
            event.handleWith(eventHandler);
        }
    }

    @SuppressWarnings("unchecked") // The cast is safe, since the component type of T[] will be type T
    private <T> Class<T> componentTypeOf(T[] array) {
        return (Class<T>) array.getClass().getComponentType();
    }

    private <ITEM> ConditionEvent.Handler convertToEventHandler(Class<? extends ITEM> correspondingObjectType, ViolationHandler<ITEM> violationHandler) {
        return (correspondingObjects, message) -> {
            if (allElementTypesMatch(correspondingObjects, correspondingObjectType)) {
                // If all elements are assignable to ITEM, covariance of ImmutableList allows this cast
                @SuppressWarnings("unchecked")
                Collection<ITEM> collection = ImmutableList.copyOf((Collection<ITEM>) correspondingObjects);
                violationHandler.handle(collection, message);
            }
        };
    }

    private boolean allElementTypesMatch(Collection<?> violatingObjects, Class<?> supportedElementType) {
        return violatingObjects.stream().allMatch(supportedElementType::isInstance);
    }

    @PublicAPI(usage = ACCESS)
    public boolean hasViolation() {
        return !violations.isEmpty();
    }

    @PublicAPI(usage = ACCESS)
    public Priority getPriority() {
        return priority;
    }

    /**
     * Filters all recorded {@link ConditionEvent ConditionEvents} by their textual description.
     * I.e. the lines of the description of an event are passed to the supplied predicate to
     * decide if the event is relevant.
     * @param linePredicate A predicate to determine which lines of events match. Predicate.test(..) == true will imply the violation will be preserved.
     * @return A new {@link EvaluationResult} containing only matching events
     */
    @PublicAPI(usage = ACCESS)
    public EvaluationResult filterDescriptionsMatching(Predicate<String> linePredicate) {
        ConditionEvents filtered = ConditionEvents.Factory.create();
        for (ConditionEvent event : violations) {
            filtered.add(new FilteredEvent(event, linePredicate));
        }
        return new EvaluationResult(rule, filtered, priority);
    }

    private static class FilteredEvent implements ConditionEvent {
        private final ConditionEvent delegate;
        private final Predicate<String> linePredicate;

        private FilteredEvent(ConditionEvent delegate, Predicate<String> linePredicate) {
            this.delegate = delegate;
            this.linePredicate = linePredicate;
        }

        @Override
        public boolean isViolation() {
            return delegate.isViolation() && !getDescriptionLines().isEmpty();
        }

        @Override
        public void addInvertedTo(ConditionEvents events) {
            delegate.addInvertedTo(events);
        }

        @Override
        public List<String> getDescriptionLines() {
            return delegate.getDescriptionLines().stream().filter(linePredicate).collect(toList());
        }

        @Override
        public void handleWith(Handler handler) {
            delegate.handleWith(new FilteredHandler(handler, linePredicate));
        }
    }

    private static class FilteredHandler implements ConditionEvent.Handler {
        private final ConditionEvent.Handler delegate;
        private final Predicate<String> linePredicate;

        private FilteredHandler(ConditionEvent.Handler delegate, Predicate<String> linePredicate) {
            this.delegate = delegate;
            this.linePredicate = linePredicate;
        }

        @Override
        public void handle(Collection<?> correspondingObjects, String message) {
            if (linePredicate.test(message)) {
                delegate.handle(correspondingObjects, message);
            }
        }
    }
}
