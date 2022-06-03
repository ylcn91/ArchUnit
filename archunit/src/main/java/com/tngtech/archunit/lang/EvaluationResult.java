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

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
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
 * To react to failures during evaluation of the rule, one can use {@link #handleViolations(ViolationHandler)}:
 * <br><br>
 * <pre><code>
 * result.handleViolations(new ViolationHandler&lt;JavaAccess&lt;?&gt;&gt;() {
 *     {@literal @}Override
 *     public void handle(Collection&lt;JavaAccess&lt;?&gt;&gt; violatingObjects, String message) {
 *         // do some reporting or react in any way to violation
 *     }
 * });
 * </code></pre>
 */
public final class EvaluationResult {
    private final HasDescription rule;
    private final ConditionEvents events;
    private final Priority priority;

    @PublicAPI(usage = ACCESS)
    public EvaluationResult(HasDescription rule, Priority priority) {
        this(rule, new ConditionEvents(), priority);
    }

    @PublicAPI(usage = ACCESS)
    public EvaluationResult(HasDescription rule, ConditionEvents events, Priority priority) {
        this.rule = rule;
        this.events = events;
        this.priority = priority;
    }

    @PublicAPI(usage = ACCESS)
    public FailureReport getFailureReport() {
        ImmutableList<String> result = events.getViolating().stream()
                .flatMap(event -> event.getDescriptionLines().stream())
                .sorted(natural())
                .collect(toImmutableList());
        FailureMessages failureMessages = new FailureMessages(result, events.getInformationAboutNumberOfViolations());
        return new FailureReport(rule, priority, failureMessages);
    }

    @PublicAPI(usage = ACCESS)
    public void add(EvaluationResult part) {
        for (ConditionEvent event : part.events) {
            events.add(event);
        }
    }

    /**
     * Passes violations to the supplied {@link ViolationHandler}. The passed violations will automatically
     * be filtered by the reified type of the given {@link ViolationHandler}. That is, if a
     * <code>ViolationHandler&lt;SomeClass&gt;</code> is passed, only violations by objects assignable to
     * <code>SomeClass</code> will be reported. The term 'reified' means that the type parameter
     * was not erased, i.e. ArchUnit can still determine the actual type parameter of the passed violation handler,
     * otherwise the upper bound, in extreme cases {@link Object}, will be used (i.e. all violations will be passed).
     *
     * @param violationHandler The violation handler that is supposed to handle all violations matching the
     *                         respective type parameter
     */
    @PublicAPI(usage = ACCESS, state = EXPERIMENTAL)
    public void handleViolations(ViolationHandler<?> violationHandler) {
        ConditionEvent.Handler eventHandler = convertToEventHandler(violationHandler);
        for (final ConditionEvent event : events.getViolating()) {
            event.handleWith(eventHandler);
        }
    }

    private <T> ConditionEvent.Handler convertToEventHandler(final ViolationHandler<T> handler) {
        final Class<?> supportedElementType = TypeToken.of(handler.getClass())
                .resolveType(ViolationHandler.class.getTypeParameters()[0]).getRawType();

        return (correspondingObjects, message) -> {
            if (allElementTypesMatch(correspondingObjects, supportedElementType)) {
                // If all elements are assignable to T (= supportedElementType), covariance of Collection allows this cast
                @SuppressWarnings("unchecked")
                Collection<T> collection = (Collection<T>) correspondingObjects;
                handler.handle(collection, message);
            }
        };
    }

    private boolean allElementTypesMatch(Collection<?> violatingObjects, Class<?> supportedElementType) {
        return violatingObjects.stream().allMatch(supportedElementType::isInstance);
    }

    @PublicAPI(usage = ACCESS)
    public boolean hasViolation() {
        return events.containViolation();
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
        ConditionEvents filtered = new ConditionEvents();
        for (ConditionEvent event : events) {
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
