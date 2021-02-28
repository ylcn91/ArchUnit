/*
 * Copyright 2014-2021 TNG Technology Consulting GmbH
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
package com.tngtech.archunit.library.metrics.rendering;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.io.ByteStreams.toByteArray;
import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;

public class PlantUmlDiagram {
    private static final String componentDiagramTemplate = readResource("component-diagram.puml.template");

    private final Map<String, Component> components;
    private final Set<Dependency> dependencies;

    private PlantUmlDiagram(Map<String, Component> components, Set<Dependency> dependencies) {
        this.components = components;
        this.dependencies = dependencies;
    }

    public String render() {
        List<String> lines = new ArrayList<>();
        for (Component component : components.values()) {
            lines.add(component.render());
        }
        lines.add(lineSeparator());
        for (Dependency dependency : dependencies) {
            lines.add(dependency.render());
        }
        return componentDiagramTemplate.replace("${body}", Joiner.on(lineSeparator()).join(lines));
    }

    private static String readResource(String path) {
        try {
            byte[] bytes = toByteArray(PlantUmlDiagram.class.getResourceAsStream(path));
            return new String(bytes, UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static class Component {
        private static final String validIdentifierCharacters = "A-Za-z0-9";
        private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[" + validIdentifierCharacters + "]+");

        private final String identifier;
        private final List<String> description;

        private Component(String identifier, String description) {
            checkArgument(IDENTIFIER_PATTERN.matcher(identifier).matches(), "PlantUml component identifier must match %s", IDENTIFIER_PATTERN);

            this.identifier = identifier;
            this.description = Splitter.onPattern("\r?\n").splitToList(description);
        }

        String render() {
            List<String> result = new ArrayList<>();
            result.add("component " + identifier + " [");
            result.addAll(description);
            result.add("]");
            return Joiner.on(lineSeparator()).join(result);
        }
    }

    private static class Dependency {
        private final Component origin;
        private final Component target;

        private Dependency(Component origin, Component target) {
            this.origin = checkNotNull(origin);
            this.target = checkNotNull(target);
        }

        public String render() {
            return origin.identifier + " --> " + target.identifier;
        }
    }

    public static class Builder {
        private static final Pattern INVALID_IDENTIFIER_CHAR_PATTERN = Pattern.compile("[^" + Component.validIdentifierCharacters + "]");
        private final ImmutableMap.Builder<String, Component> componentBuilders = ImmutableMap.builder();
        private final SetMultimap<String, String> dependenciesFromSelf = HashMultimap.create();

        private Builder() {
        }

        public void addComponent(String identifier, String text) {
            String componentIdentifier = sanitize(identifier);
            componentBuilders.put(componentIdentifier, new Component(componentIdentifier, text));
        }

        public void addDependency(String originIdentifier, String targetIdentifier) {
            dependenciesFromSelf.put(sanitize(originIdentifier), sanitize(targetIdentifier));
        }

        private String sanitize(String identifier) {
            return INVALID_IDENTIFIER_CHAR_PATTERN.matcher(identifier).replaceAll("");
        }

        public PlantUmlDiagram build() {
            ImmutableMap<String, Component> components = componentBuilders.build();
            ImmutableSet.Builder<Dependency> dependencies = ImmutableSet.builder();
            for (Map.Entry<String, String> dependencyEntry : dependenciesFromSelf.entries()) {
                dependencies.add(new Dependency(components.get(dependencyEntry.getKey()), components.get(dependencyEntry.getValue())));
            }
            return new PlantUmlDiagram(components, dependencies.build());
        }
    }
}