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
package org.thingsboard.rulechain.standalone.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.rulechain.standalone.model.RuleNodeDescriptor;
import org.thingsboard.server.service.component.ComponentDiscoveryService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller exposing a minimal API around the ThingsBoard rule node
 * library. It allows consumers to browse the available rule node types without
 * depending on the full ThingsBoard platform.
 */
@RestController
@RequestMapping("/api/rule-nodes")
@RequiredArgsConstructor
public class RuleNodeLibraryController {

    private final ComponentDiscoveryService componentDiscoveryService;

    @GetMapping
    public List<RuleNodeDescriptor> listRuleNodes() {
        return componentDiscoveryService.getVersionedNodes().stream()
                .map(RuleNodeDescriptor::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/{className}")
    public ResponseEntity<RuleNodeDescriptor> getRuleNode(@PathVariable String className) {
        return componentDiscoveryService.getRuleNodeInfo(className)
                .map(RuleNodeDescriptor::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
