/*
 * Copyright © 2016-2025 The Thingsboard Authors
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
package org.thingsboard.rulechain.standalone.model;

import org.thingsboard.server.service.component.RuleNodeClassInfo;

/**
 * Simple DTO exposing the most relevant details about a ThingsBoard rule node
 * so that REST responses remain decoupled from the internal descriptor class.
 */
public record RuleNodeDescriptor(
        String className,
        String simpleName,
        String displayName,
        String description,
        int currentVersion
) {

    public static RuleNodeDescriptor from(RuleNodeClassInfo info) {
        var annotation = info.getAnnotation();
        return new RuleNodeDescriptor(
                info.getClassName(),
                info.getSimpleName(),
                annotation.name(),
                annotation.nodeDescription(),
                info.getCurrentVersion()
        );
    }
}
