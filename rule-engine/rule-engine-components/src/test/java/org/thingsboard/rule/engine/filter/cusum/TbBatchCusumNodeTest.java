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
package org.thingsboard.rule.engine.filter.cusum;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TbBatchCusumNodeTest {

    @Mock
    private TbContext ctx;

    private final RuleChainId ruleChainId = new RuleChainId(Uuids.timeBased());
    private final RuleNodeId ruleNodeId = new RuleNodeId(Uuids.timeBased());
    private final DeviceId deviceId = new DeviceId(Uuids.timeBased());

    @BeforeEach
    void setUp() {
        when(ctx.transformMsg(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            TbMsg original = invocation.getArgument(0);
            String type = invocation.getArgument(1);
            TbMsgMetaData md = invocation.getArgument(3);
            String data = invocation.getArgument(4);
            return TbMsg.newMsg()
                    .type(type)
                    .originator(original.getOriginator())
                    .copyMetaData(md)
                    .dataType(original.getDataType())
                    .data(data)
                    .ruleChainId(original.getRuleChainId())
                    .ruleNodeId(original.getRuleNodeId())
                    .build();
        });
    }

    @Test
    void warmupBatchOnlyEmitsInsufficient() throws TbNodeException {
        TbBatchCusumNode node = initNode(config -> config.setWarmupBatches(1));

        TbMsg msg = buildBatchMsg(batchValues(29.8, 29.9, 30.0));

        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> msgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        ArgumentCaptor<String> relationCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).tellNext(msgCaptor.capture(), relationCaptor.capture());

        assertEquals("INSUFFICIENT", relationCaptor.getValue());
        TbMsgMetaData md = msgCaptor.getValue().getMetaData();
        assertEquals("INSUFFICIENT", md.getValue("_CUS_decision"));
        assertEquals("WARMUP", md.getValue("_CUS_reason"));
        assertEquals("3", md.getValue("_CUS_batchPoints"));
    }

    @Test
    void sustainedDriftTriggersAlarmAfterWarmup() throws TbNodeException {
        TbBatchCusumNode node = initNode(config -> {
            config.setWarmupBatches(1);
            config.getCusum().setKSigma(0.1);
            config.getCusum().setHWarnSigma(0.5);
            config.getCusum().setHAlarmSigma(1.0);
            config.getBaseline().setMode(TbBatchCusumNodeConfiguration.BaselineMode.FIXED);
            config.getBaseline().setMu0(0.0);
            config.getBaseline().setSigma0(1.0);
            config.setZDomain(false);
        });

        TbMsg warmup = buildBatchMsg(batchValues(0.0, 0.1, 0.2));
        node.onMsg(ctx, warmup);
        clearInvocations(ctx);

        TbMsg drift = buildBatchMsg(batchValues(1.0, 1.0, 1.0));
        node.onMsg(ctx, drift);

        ArgumentCaptor<TbMsg> msgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        ArgumentCaptor<String> relationCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).tellNext(msgCaptor.capture(), relationCaptor.capture());

        assertEquals("ALARM", relationCaptor.getValue());
        TbMsgMetaData md = msgCaptor.getValue().getMetaData();
        assertEquals("ALARM", md.getValue("_CUS_decision"));
        assertEquals("POS>=hAlarm", md.getValue("_CUS_reason"));
        assertEquals("3", md.getValue("_CUS_batchPoints"));
        assertEquals("POS", md.getValue("_CUS_drift"));
    }

    @Test
    void gateSuppressionYieldsInsufficient() throws TbNodeException {
        TbBatchCusumNode node = initNode(config -> {
            config.setWarmupBatches(0);
            config.getCusum().setKSigma(0.0);
            config.getCusum().setHWarnSigma(0.5);
            config.getCusum().setHAlarmSigma(1.0);
            config.getBaseline().setMode(TbBatchCusumNodeConfiguration.BaselineMode.FIXED);
            config.getBaseline().setMu0(0.0);
            config.getBaseline().setSigma0(1.0);
            config.setZDomain(false);
            config.getGates().setModes(Arrays.asList("charge"));
        });

        TbMsg msg = buildBatchMsg(batchValuesWithMode("idle", 1.0, 1.0, 1.0));
        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> msgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        ArgumentCaptor<String> relationCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).tellNext(msgCaptor.capture(), relationCaptor.capture());

        assertEquals("INSUFFICIENT", relationCaptor.getValue());
        TbMsgMetaData md = msgCaptor.getValue().getMetaData();
        assertEquals("INSUFFICIENT", md.getValue("_CUS_decision"));
        assertEquals("GATE_BLOCK", md.getValue("_CUS_reason"));
    }

    private TbBatchCusumNode initNode(java.util.function.Consumer<TbBatchCusumNodeConfiguration> customizer)
            throws TbNodeException {
        TbBatchCusumNodeConfiguration config = new TbBatchCusumNodeConfiguration().defaultConfiguration();
        config.getPersistState().setEnabled(false);
        config.getGates().setStartBelow(null);
        config.getGates().setDeltaTamb(null);
        config.getGates().setModes(Collections.singletonList("charge"));
        customizer.accept(config);
        TbNodeConfiguration nodeConfig = new TbNodeConfiguration(JacksonUtil.valueToTree(config));
        TbBatchCusumNode node = new TbBatchCusumNode();
        node.init(ctx, nodeConfig);
        return node;
    }

    private TbMsg buildBatchMsg(ObjectNode batch) {
        return TbMsg.newMsg()
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(deviceId)
                .copyMetaData(new TbMsgMetaData())
                .dataType(TbMsgDataType.JSON)
                .data(batch.toString())
                .ruleChainId(ruleChainId)
                .ruleNodeId(ruleNodeId)
                .build();
    }

    private ObjectNode batchValues(double... values) {
        return batchValuesWithMode("charge", values);
    }

    private ObjectNode batchValuesWithMode(String mode, double... values) {
        ObjectNode root = JacksonUtil.newObjectNode();
        var array = root.putArray("messages");
        long baseTs = 1_000_000L;
        for (int i = 0; i < values.length; i++) {
            ObjectNode item = JacksonUtil.newObjectNode();
            ObjectNode data = JacksonUtil.newObjectNode();
            data.put("temperature", values[i]);
            ObjectNode metadata = JacksonUtil.newObjectNode();
            metadata.put("ts", baseTs + i * 1000);
            metadata.put("mode", mode);
            metadata.put("Tamb", 20.0);
            item.set("data", data);
            item.set("metadata", metadata);
            array.add(item);
        }
        return root;
    }
}
