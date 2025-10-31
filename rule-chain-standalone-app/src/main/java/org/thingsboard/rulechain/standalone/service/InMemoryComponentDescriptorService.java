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
package org.thingsboard.rulechain.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.Assert;
import org.thingsboard.server.common.data.id.ComponentDescriptorId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.plugin.ComponentDescriptor;
import org.thingsboard.server.common.data.plugin.ComponentScope;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.dao.component.ComponentDescriptorService;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lightweight in-memory {@link ComponentDescriptorService} implementation tailored for
 * the standalone rule chain demo. It allows the rule chain component discovery
 * service to cache and reuse descriptors during the application lifecycle without
 * requiring a full ThingsBoard persistence stack.
 */
public class InMemoryComponentDescriptorService implements ComponentDescriptorService {

    private final Map<String, ComponentDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public ComponentDescriptor saveComponent(TenantId tenantId, ComponentDescriptor component) {
        Assert.notNull(component, "Component descriptor must not be null");
        descriptors.put(component.getClazz(), component);
        return component;
    }

    @Override
    public ComponentDescriptor findById(TenantId tenantId, ComponentDescriptorId componentId) {
        if (componentId == null) {
            return null;
        }
        return descriptors.values().stream()
                .filter(descriptor -> componentId.equals(descriptor.getId()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public ComponentDescriptor findByClazz(TenantId tenantId, String clazz) {
        return descriptors.get(clazz);
    }

    @Override
    public PageData<ComponentDescriptor> findByTypeAndPageLink(TenantId tenantId, ComponentType type, PageLink pageLink) {
        return PageData.emptyPageData();
    }

    @Override
    public PageData<ComponentDescriptor> findByScopeAndTypeAndPageLink(TenantId tenantId, ComponentScope scope, ComponentType type, PageLink pageLink) {
        return PageData.emptyPageData();
    }

    @Override
    public boolean validate(TenantId tenantId, ComponentDescriptor component, JsonNode configuration) {
        return Optional.ofNullable(component)
                .map(ComponentDescriptor::getClazz)
                .filter(clazz -> !clazz.isBlank())
                .isPresent();
    }

    @Override
    public void deleteByClazz(TenantId tenantId, String clazz) {
        descriptors.remove(clazz);
    }
}
