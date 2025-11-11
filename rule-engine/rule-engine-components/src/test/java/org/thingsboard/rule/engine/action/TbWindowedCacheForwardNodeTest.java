/**
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
package org.thingsboard.rule.engine.action;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class TbWindowedCacheForwardNodeTest {

    private static final String QUEUE_NAME = "Main";

    private final RuleNodeId ruleNodeId = new RuleNodeId(UUID.fromString("9f0455e2-6d68-4e35-a31e-d9c06669f091"));
    private final DeviceId deviceId = new DeviceId(UUID.fromString("a3d7a8f4-5d3a-4f90-b302-90e3f53d0ddf"));
    private final CustomerId customerId = new CustomerId(UUID.fromString("c1fef5df-2a58-4a40-91a9-3bf84fd4f4e6"));
    private final RuleChainId targetRuleChainId = new RuleChainId(UUID.fromString("1be4f2a4-3c4a-4dbb-b3f0-c7a27a908755"));

    @Mock
    private TbContext ctx;

    private TbWindowedCacheForwardNode node;
    private TbWindowedCacheForwardNodeConfiguration config;

    @BeforeEach
    void setUp() {
        node = new TbWindowedCacheForwardNode();
        config = new TbWindowedCacheForwardNodeConfiguration();
        config.setWindowSizeInSeconds(60);
        config.setTargetRuleChainId(targetRuleChainId.getId().toString());
        config.setOutputMsgType(TbMsgType.POST_TELEMETRY_REQUEST.name());
        config.setMaxMessagesPerWindow(0);
    }

    @AfterEach
    void tearDown() {
        node.destroy();
    }

    @Test
    void givenMessagesInWindow_whenTickArrives_thenAggregatedMsgForwardedToTargetChain() throws TbNodeException {
        // GIVEN
        var tickHolder = new Object() {
            TbMsg value;
        };
        long windowMs = TimeUnit.SECONDS.toMillis(config.getWindowSizeInSeconds());

        given(ctx.getQueueName()).willReturn(QUEUE_NAME);
        given(ctx.getSelfId()).willReturn(ruleNodeId);
        willAnswer(invocation -> {
            tickHolder.value = invocation.getArgument(0);
            return null;
        }).given(ctx).tellSelf(any(TbMsg.class), anyLong());

        node.init(ctx, new TbNodeConfiguration(JacksonUtil.valueToTree(config)));

        TbMsg scheduledTick = tickHolder.value;
        assertThat(scheduledTick).isNotNull();

        long now = System.currentTimeMillis();
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("key", "value");

        TbMsg outdatedMsg = TbMsg.newMsg()
                .queueName(QUEUE_NAME)
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(deviceId)
                .customerId(customerId)
                .metaData(metaData.copy())
                .data("{\"temperature\":21}")
                .ts(now - TimeUnit.MINUTES.toMillis(5))
                .build();

        TbMsg recentMsg = TbMsg.newMsg()
                .queueName(QUEUE_NAME)
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(deviceId)
                .customerId(customerId)
                .metaData(metaData.copy())
                .data("{\"temperature\":25}")
                .ts(now - TimeUnit.SECONDS.toMillis(10))
                .build();

        // WHEN
        node.onMsg(ctx, outdatedMsg);
        node.onMsg(ctx, recentMsg);
        node.onMsg(ctx, scheduledTick);

        // THEN
        then(ctx).should().checkTenantEntity(targetRuleChainId);
        then(ctx).should(times(2)).tellSelf(any(TbMsg.class), eq(windowMs));

        ArgumentCaptor<TbMsg> ackCaptor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctx).should(times(2)).ack(ackCaptor.capture());
        assertThat(ackCaptor.getAllValues()).containsExactly(outdatedMsg, recentMsg);

        ArgumentCaptor<TbMsg> aggregatedCaptor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctx).should().input(aggregatedCaptor.capture(), eq(targetRuleChainId));

        TbMsg aggregatedMsg = aggregatedCaptor.getValue();
        assertThat(aggregatedMsg.getQueueName()).isEqualTo(QUEUE_NAME);
        assertThat(aggregatedMsg.getType()).isEqualTo(config.getOutputMsgType());
        assertThat(aggregatedMsg.getOriginator()).isEqualTo(deviceId);
        assertThat(aggregatedMsg.getCustomerId()).isEqualTo(customerId);

        TbMsgMetaData aggregatedMeta = aggregatedMsg.getMetaData();
        assertThat(aggregatedMeta.getValue("messageCount")).isEqualTo("1");
        long windowStartTs = Long.parseLong(aggregatedMeta.getValue("windowStartTs"));
        long windowEndTs = Long.parseLong(aggregatedMeta.getValue("windowEndTs"));
        assertThat(windowEndTs).isGreaterThanOrEqualTo(windowStartTs);

        JsonNode payload = JacksonUtil.fromString(aggregatedMsg.getData(), JsonNode.class);
        assertThat(payload.get("messageCount").asInt()).isEqualTo(1);
        JsonNode messages = payload.get("messages");
        assertThat(messages.isArray()).isTrue();
        assertThat(messages.size()).isEqualTo(1);

        JsonNode firstMsg = messages.get(0);
        assertThat(firstMsg.get("ts").asLong()).isEqualTo(recentMsg.getTs());
        assertThat(firstMsg.get("type").asText()).isEqualTo(TbMsgType.POST_TELEMETRY_REQUEST.name());
        assertThat(firstMsg.get("data").asText()).isEqualTo("{\"temperature\":25}");

        JsonNode metadataNode = firstMsg.get("metadata");
        assertThat(metadataNode.isObject()).isTrue();
        assertThat(metadataNode.get("key").asText()).isEqualTo("value");
    }

    @Test
    void givenMaxMessagesReached_beforeWindowElapses_thenFlushImmediately() throws TbNodeException {
        // GIVEN
        config.setMaxMessagesPerWindow(2);

        given(ctx.getQueueName()).willReturn(QUEUE_NAME);
        given(ctx.getSelfId()).willReturn(ruleNodeId);

        node.init(ctx, new TbNodeConfiguration(JacksonUtil.valueToTree(config)));

        TbMsg firstMsg = TbMsg.newMsg()
                .queueName(QUEUE_NAME)
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(deviceId)
                .customerId(customerId)
                .metaData(TbMsgMetaData.EMPTY)
                .data("{\"temperature\":21}")
                .build();

        TbMsg secondMsg = TbMsg.newMsg()
                .queueName(QUEUE_NAME)
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(deviceId)
                .customerId(customerId)
                .metaData(TbMsgMetaData.EMPTY)
                .data("{\"temperature\":22}")
                .build();

        // WHEN
        node.onMsg(ctx, firstMsg);
        node.onMsg(ctx, secondMsg);

        // THEN
        ArgumentCaptor<TbMsg> aggregatedCaptor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctx).should().input(aggregatedCaptor.capture(), eq(targetRuleChainId));

        TbMsg aggregatedMsg = aggregatedCaptor.getValue();
        JsonNode payload = JacksonUtil.fromString(aggregatedMsg.getData(), JsonNode.class);
        assertThat(payload.get("messageCount").asInt()).isEqualTo(2);
        assertThat(payload.get("messages").size()).isEqualTo(2);

        then(ctx).should(times(2)).ack(any(TbMsg.class));
    }
}

