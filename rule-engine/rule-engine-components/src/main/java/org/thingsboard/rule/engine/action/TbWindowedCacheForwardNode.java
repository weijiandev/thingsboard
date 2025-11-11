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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "window cache forward",
        configClazz = TbWindowedCacheForwardNodeConfiguration.class,
        version = 1,
        nodeDescription = "Collects incoming messages during the configured window and forwards aggregated payload to another rule chain.",
        nodeDetails = "Messages are cached for the specified time window (5 minutes by default). When the window elapses,"
                + " the node builds a JSON payload that contains all cached messages and pushes it to the target rule chain input.",
        icon = "query_stats"
)
public class TbWindowedCacheForwardNode implements TbNode {

    private static final Gson GSON = new Gson();
    private static final String SELF_MSG_TYPE = "WINDOW_CACHE_FORWARD_SELF_MSG";

    private final Map<EntityId, Deque<CachedMsg>> buffers = new ConcurrentHashMap<>();

    private TbWindowedCacheForwardNodeConfiguration config;
    private RuleChainId targetRuleChainId;
    private TbMsgType outputMsgType;
    private long windowSizeMs;
    private UUID nextTickId;
    private String queueName;
    private int maxMessagesPerWindow;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbWindowedCacheForwardNodeConfiguration.class);
        if (config.getWindowSizeInSeconds() <= 0) {
            throw new TbNodeException("Window size should be greater than 0 seconds", true);
        }
        this.windowSizeMs = TimeUnit.SECONDS.toMillis(config.getWindowSizeInSeconds());
        if (StringUtils.isEmpty(config.getTargetRuleChainId())) {
            throw new TbNodeException("Target rule chain id must be specified", true);
        }
        try {
            this.targetRuleChainId = new RuleChainId(UUID.fromString(config.getTargetRuleChainId()));
        } catch (IllegalArgumentException e) {
            throw new TbNodeException("Failed to parse rule chain id: " + config.getTargetRuleChainId(), true);
        }
        ctx.checkTenantEntity(targetRuleChainId);
        this.outputMsgType = resolveOutputMsgType();
        this.queueName = ctx.getQueueName();
        if (config.getMaxMessagesPerWindow() < 0) {
            throw new TbNodeException("Max messages per window must be greater than or equal to 0", true);
        }
        this.maxMessagesPerWindow = config.getMaxMessagesPerWindow();
        scheduleTickMsg(ctx, null);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        if (isScheduledTick(msg)) {
            flushWindow(ctx);
            scheduleTickMsg(ctx, msg.getCustomerId());
        } else {
            addToBuffer(ctx, msg);
            ctx.ack(msg);
        }
    }

    @Override
    public void destroy() {
        buffers.clear();
        nextTickId = null;
    }

    private void addToBuffer(TbContext ctx, TbMsg msg) {
        EntityId originator = msg.getOriginator();
        CachedMsg cachedMsg = new CachedMsg(msg);
        long threshold = System.currentTimeMillis() - windowSizeMs;
        Deque<CachedMsg> queue = buffers.compute(originator, (key, existingQueue) -> {
            Deque<CachedMsg> messages = existingQueue != null ? existingQueue : new ArrayDeque<>();
            prune(messages, threshold);
            messages.addLast(cachedMsg);
            return messages;
        });
        if (maxMessagesPerWindow > 0 && queue.size() >= maxMessagesPerWindow) {
            flushQueue(ctx, originator, queue, System.currentTimeMillis());
            buffers.remove(originator, queue);
        }
    }

    private void flushWindow(TbContext ctx) {
        long now = System.currentTimeMillis();
        long windowStartTs = now - windowSizeMs;
        Iterator<Map.Entry<EntityId, Deque<CachedMsg>>> iterator = buffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityId, Deque<CachedMsg>> entry = iterator.next();
            Deque<CachedMsg> queue = entry.getValue();
            prune(queue, windowStartTs);
            if (queue.isEmpty()) {
                iterator.remove();
                continue;
            }
            flushQueue(ctx, entry.getKey(), queue, now);
            iterator.remove();
        }
    }

    private void flushQueue(TbContext ctx, EntityId originator, Deque<CachedMsg> queue, long now) {
        if (queue.isEmpty()) {
            return;
        }
        long windowStartTs = Math.max(now - windowSizeMs, queue.peekFirst().ts);
        sendAggregatedMsg(ctx, originator, queue, windowStartTs, now);
        queue.clear();
    }

    private void sendAggregatedMsg(TbContext ctx, EntityId originator, Collection<CachedMsg> cachedMessages,
                                   long windowStartTs, long windowEndTs) {
        JsonObject payload = new JsonObject();
        int messageCount = cachedMessages.size();
        payload.addProperty("windowStartTs", windowStartTs);
        payload.addProperty("windowEndTs", windowEndTs);
        payload.addProperty("messageCount", messageCount);

        JsonArray messagesArray = new JsonArray();
        CustomerId customerId = null;
        for (CachedMsg cachedMsg : cachedMessages) {
            JsonObject jsonMsg = new JsonObject();
            jsonMsg.addProperty("ts", cachedMsg.ts);
            jsonMsg.addProperty("type", cachedMsg.type);
            jsonMsg.addProperty("data", cachedMsg.data);
            if (!cachedMsg.metaData.isEmpty()) {
                jsonMsg.add("metadata", GSON.toJsonTree(cachedMsg.metaData.values()));
            }
            messagesArray.add(jsonMsg);
            if (customerId == null && cachedMsg.customerId != null) {
                customerId = cachedMsg.customerId;
            }
        }
        payload.add("messages", messagesArray);

        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("windowStartTs", String.valueOf(windowStartTs));
        metaData.putValue("windowEndTs", String.valueOf(windowEndTs));
        metaData.putValue("messageCount", String.valueOf(messageCount));

        TbMsg aggregatedMsg = TbMsg.newMsg()
                .queueName(queueName)
                .type(outputMsgType)
                .originator(originator)
                .customerId(customerId)
                .metaData(metaData)
                .data(GSON.toJson(payload))
                .build();

        ctx.input(aggregatedMsg, targetRuleChainId);
    }

    private void prune(Deque<CachedMsg> queue, long thresholdTs) {
        while (!queue.isEmpty() && queue.peekFirst().ts < thresholdTs) {
            queue.pollFirst();
        }
    }

    private boolean isScheduledTick(TbMsg msg) {
        return SELF_MSG_TYPE.equals(msg.getType()) && msg.getId().equals(nextTickId);
    }

    private void scheduleTickMsg(TbContext ctx, CustomerId customerId) {
        TbMsg tickMsg = TbMsg.newMsg()
                .queueName(queueName)
                .type(SELF_MSG_TYPE)
                .originator(ctx.getSelfId())
                .customerId(customerId)
                .metaData(TbMsgMetaData.EMPTY)
                .data(TbMsg.EMPTY_STRING)
                .build();
        nextTickId = tickMsg.getId();
        ctx.tellSelf(tickMsg, windowSizeMs);
    }

    private TbMsgType resolveOutputMsgType() throws TbNodeException {
        if (StringUtils.isEmpty(config.getOutputMsgType())) {
            return TbMsgType.POST_TELEMETRY_REQUEST;
        }
        try {
            return TbMsgType.valueOf(config.getOutputMsgType());
        } catch (Exception e) {
            throw new TbNodeException("Unsupported output message type: " + config.getOutputMsgType(), true);
        }
    }

    private static class CachedMsg {
        private final long ts;
        private final String type;
        private final String data;
        private final TbMsgMetaData metaData;
        private final CustomerId customerId;

        private CachedMsg(TbMsg msg) {
            this.ts = msg.getTs();
            this.type = msg.getType();
            this.data = msg.getData();
            this.metaData = msg.getMetaData() != null ? msg.getMetaData().copy() : new TbMsgMetaData();
            this.customerId = msg.getCustomerId();
        }
    }
}

