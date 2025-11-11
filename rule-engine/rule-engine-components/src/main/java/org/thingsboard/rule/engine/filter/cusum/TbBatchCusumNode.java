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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.common.msg.queue.PartitionChangeMsg;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.rule.engine.filter.cusum.TbBatchCusumNodeConfiguration.CusumMode.NEG_ONLY;
import static org.thingsboard.rule.engine.filter.cusum.TbBatchCusumNodeConfiguration.CusumMode.POS_ONLY;
import static org.thingsboard.rule.engine.filter.cusum.TbBatchCusumNodeConfiguration.ResetPolicy.ON_ALARM;
import static org.thingsboard.rule.engine.filter.cusum.TbBatchCusumNodeConfiguration.ResetPolicy.ON_BATCH_END;
import static org.thingsboard.server.common.data.AttributeScope.SERVER_SCOPE;

@Slf4j
@RuleNode(
        type = ComponentType.FILTER,
        name = "batch cusum",
        configClazz = TbBatchCusumNodeConfiguration.class,
        relationTypes = {"ALARM", "WARN", "OK", "INSUFFICIENT"},
        nodeDescription = "Detects slow drifts in batch telemetry using z-domain CUSUM.",
        nodeDetails = "The node consumes batch telemetry arrays, maintains baseline mean/variance and accumulates " +
                "CUSUM statistics across batches. It emits a single decision per batch via metadata or routing.",
        configDirective = "tbFilterNodeBatchCusumConfig",
        icon = "multiline_chart"
)
public class TbBatchCusumNode implements TbNode {

    private static final double EPSILON = 1e-9;
    private static final String MODE_FIELD = "mode";
    private static final String TAMB_FIELD = "Tamb";
    private static final String TAMB_FIELD_LOWER = "tamb";

    private TbBatchCusumNodeConfiguration config;
    private final ConcurrentMap<String, CusumState> states = new ConcurrentHashMap<>();

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbBatchCusumNodeConfiguration.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            CusumState state = resolveState(ctx, msg);
            BatchProcessingResult result;
            synchronized (state) {
                result = processBatch(msg, state);
            }
            if (config.getPersistState().isEnabled()) {
                persistState(ctx, msg, state);
            }
            TbMsgMetaData md = msg.getMetaData().copy();
            applyMetadata(result, md);
            TbMsg outMsg = ctx.transformMsg(msg, msg.getType(), msg.getOriginator(), md, msg.getData());
            if (config.isAsFilter()) {
                ctx.tellNext(outMsg, result.relation());
            } else {
                ctx.tellSuccess(outMsg);
            }
        } catch (Exception e) {
            ctx.tellFailure(msg, e);
        }
    }

    private CusumState resolveState(TbContext ctx, TbMsg msg) throws Exception {
        String key = msg.getOriginator().getId().toString();
        CusumState state = states.get(key);
        if (state != null) {
            return state;
        }
        CusumState loaded = config.getPersistState().isEnabled()
                ? loadState(ctx, msg)
                : new CusumState();
        states.putIfAbsent(key, loaded);
        return states.get(key);
    }

    private CusumState loadState(TbContext ctx, TbMsg msg) {
        try {
            List<AttributeKvEntry> entries = ctx.getAttributesService()
                    .find(ctx.getTenantId(), msg.getOriginator(), SERVER_SCOPE,
                            Collections.singleton(config.getPersistState().getScopeKey()))
                    .get(5, TimeUnit.SECONDS);
            if (entries != null && !entries.isEmpty()) {
                AttributeKvEntry entry = entries.get(0);
                if (entry != null && !Strings.isNullOrEmpty(entry.getValueAsString())) {
                    return parseState(entry.getValueAsString());
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to load CUSUM state", msg.getOriginator(), e);
        }
        return new CusumState();
    }

    private CusumState parseState(String value) {
        try {
            JsonNode node = JacksonUtil.toJsonNode(value);
            CusumState state = new CusumState();
            if (node.hasNonNull("mu")) {
                state.mu = node.get("mu").asDouble();
                state.baselineInitialized = true;
            }
            if (node.hasNonNull("variance")) {
                state.variance = node.get("variance").asDouble();
            }
            if (node.hasNonNull("sPos")) {
                state.sPos = node.get("sPos").asDouble();
            }
            if (node.hasNonNull("sNeg")) {
                state.sNeg = node.get("sNeg").asDouble();
            }
            if (node.hasNonNull("lastTs")) {
                state.lastTs = node.get("lastTs").asLong();
            }
            if (node.hasNonNull("batches")) {
                state.batches = node.get("batches").asInt();
            }
            if (node.hasNonNull("belowK")) {
                state.belowK = node.get("belowK").asInt();
            }
            if (node.hasNonNull("lastDecision")) {
                String valueStr = node.get("lastDecision").asText();
                state.lastDecision = Decision.valueOf(valueStr);
            }
            if (node.hasNonNull("rolling")) {
                ArrayNode arr = (ArrayNode) node.get("rolling");
                for (JsonNode val : arr) {
                    state.addRolling(val.asDouble());
                }
            }
            if (node.hasNonNull("lastZ")) {
                state.lastZ = node.get("lastZ").asDouble();
            }
            if (node.hasNonNull("lastDrift")) {
                state.lastDrift = Drift.valueOf(node.get("lastDrift").asText());
            }
            return state;
        } catch (Exception e) {
            log.warn("Failed to parse persisted CUSUM state", e);
            return new CusumState();
        }
    }

    private void persistState(TbContext ctx, TbMsg msg, CusumState state) {
        try {
            ObjectNode node = JacksonUtil.newObjectNode();
            node.put("mu", state.mu);
            node.put("variance", state.variance);
            node.put("sPos", state.sPos);
            node.put("sNeg", state.sNeg);
            node.put("lastTs", state.lastTs);
            node.put("batches", state.batches);
            node.put("belowK", state.belowK);
            node.put("lastDecision", state.lastDecision.name());
            node.put("lastZ", state.lastZ);
            node.put("lastDrift", state.lastDrift.name());
            ArrayNode rolling = node.putArray("rolling");
            state.rolling.forEach(rolling::add);

            AttributesSaveRequest request = AttributesSaveRequest.builder()
                    .scope(AttributeScope.SERVER_SCOPE)
                    .kv(new StringDataEntry(config.getPersistState().getScopeKey(), node.toString()))
                    .build();
            ctx.getTelemetryService().saveAttributes(ctx.getTenantId(), msg.getOriginator(), request);
        } catch (Exception e) {
            log.warn("[{}] Failed to persist CUSUM state", msg.getOriginator(), e);
        }
    }

    private BatchProcessingResult processBatch(TbMsg msg, CusumState state) {
        Batch batch = parseBatch(msg);
        if (batch.samples.isEmpty()) {
            return BatchProcessingResult.insufficient(state, "NO_POINTS", Decision.INSUFFICIENT);
        }

        if (shouldResetForGap(state, batch)) {
            state.reset();
        }

        double lastZ = state.lastZ;
        Drift drift = Drift.NONE;
        for (Sample sample : batch.samples) {
            BaselineSnapshot baseline = updateBaseline(sample.value, state);
            double z = config.isZDomain() ? (sample.value - baseline.mean) / Math.max(baseline.sigma, EPSILON)
                    : sample.value - baseline.mean;
            lastZ = z;
            updateCusum(z, state);
            state.lastTs = sample.ts;
            state.lastTamb = sample.tamb == null ? state.lastTamb : sample.tamb;
            state.lastMode = sample.mode == null ? state.lastMode : sample.mode;
            drift = resolveDrift(state);
        }

        state.lastZ = lastZ;
        state.lastDrift = drift;
        state.batches++;

        double maxS = Math.max(state.sPos, state.sNeg);
        Decision rawDecision = evaluateThreshold(maxS);
        state.belowK = maxS < config.getCusum().getKSigma() ? state.belowK + 1 : 0;

        Decision finalDecision;
        String reason;

        if (state.batches <= config.getWarmupBatches()) {
            finalDecision = Decision.INSUFFICIENT;
            reason = "WARMUP";
        } else if (!passesGates(state)) {
            finalDecision = Decision.INSUFFICIENT;
            reason = "GATE_BLOCK";
        } else {
            Decision hysteresisDecision = applyHysteresis(rawDecision, maxS, state);
            finalDecision = hysteresisDecision;
            reason = buildReason(rawDecision, hysteresisDecision, maxS, state);
        }

        applyResetPolicy(finalDecision, state);
        state.lastDecision = finalDecision;

        return new BatchProcessingResult(batch.samples.size(), state.mu, Math.sqrt(Math.max(state.variance, 0)),
                lastZ, state.sPos, state.sNeg, drift, finalDecision, reason);
    }

    private boolean shouldResetForGap(CusumState state, Batch batch) {
        if (config.getResetIfGapSec() <= 0 || state.lastTs <= 0) {
            return false;
        }
        long gapMillis = batch.samples.get(0).ts - state.lastTs;
        return gapMillis > Duration.ofSeconds(config.getResetIfGapSec()).toMillis();
    }

    private boolean passesGates(CusumState state) {
        TbBatchCusumNodeConfiguration.Gates gates = config.getGates();
        if (gates.getStartBelow() != null && state.mu >= gates.getStartBelow()) {
            return false;
        }
        if (gates.getDeltaTamb() != null && state.lastTamb != null && state.mu - state.lastTamb < gates.getDeltaTamb()) {
            return false;
        }
        if (gates.getModes() != null && !gates.getModes().isEmpty()) {
            if (state.lastMode == null) {
                return false;
            }
            String mode = state.lastMode.toLowerCase(Locale.ENGLISH);
            boolean match = gates.getModes().stream().anyMatch(m -> mode.equalsIgnoreCase(m));
            if (!match) {
                return false;
            }
        }
        return true;
    }

    private Decision applyHysteresis(Decision rawDecision, double maxS, CusumState state) {
        if (state.lastDecision != Decision.ALARM) {
            return rawDecision;
        }
        if (rawDecision == Decision.ALARM) {
            return Decision.ALARM;
        }
        TbBatchCusumNodeConfiguration.Hysteresis hysteresis = config.getHysteresis();
        if (maxS < hysteresis.getHClearSigma() && state.belowK >= hysteresis.getMinBatchesBelowK()) {
            return rawDecision;
        }
        return Decision.ALARM;
    }

    private String buildReason(Decision rawDecision, Decision finalDecision, double maxS, CusumState state) {
        if (finalDecision == Decision.INSUFFICIENT) {
            return "INSUFFICIENT";
        }
        if (finalDecision == Decision.ALARM && rawDecision != Decision.ALARM) {
            return "HYSTERESIS";
        }
        if (finalDecision == Decision.ALARM) {
            return state.sPos >= state.sNeg ? "POS>=hAlarm" : "NEG>=hAlarm";
        }
        if (finalDecision == Decision.WARN) {
            return state.sPos >= state.sNeg ? "POS>=hWarn" : "NEG>=hWarn";
        }
        return maxS >= config.getCusum().getKSigma() ? "DRIFT_LOW" : "STABLE";
    }

    private Decision evaluateThreshold(double maxS) {
        TbBatchCusumNodeConfiguration.Cusum cusumCfg = config.getCusum();
        if (maxS >= cusumCfg.getHAlarmSigma()) {
            return Decision.ALARM;
        }
        if (maxS >= cusumCfg.getHWarnSigma()) {
            return Decision.WARN;
        }
        return Decision.OK;
    }

    private void applyResetPolicy(Decision decision, CusumState state) {
        TbBatchCusumNodeConfiguration.ResetPolicy policy = config.getCusum().getResetPolicy();
        if (policy == ON_BATCH_END || (policy == ON_ALARM && decision == Decision.ALARM)) {
            state.sPos = 0;
            state.sNeg = 0;
        }
        if (decision == Decision.ALARM && policy == ON_ALARM) {
            state.belowK = 0;
        }
    }

    private Drift resolveDrift(CusumState state) {
        if (config.getCusum().getMode() == NEG_ONLY) {
            return state.sNeg > 0 ? Drift.NEG : Drift.NONE;
        }
        if (config.getCusum().getMode() == POS_ONLY) {
            return state.sPos > 0 ? Drift.POS : Drift.NONE;
        }
        if (state.sPos == 0 && state.sNeg == 0) {
            return Drift.NONE;
        }
        return state.sPos >= state.sNeg ? Drift.POS : Drift.NEG;
    }

    private void updateCusum(double z, CusumState state) {
        TbBatchCusumNodeConfiguration.Cusum cusumCfg = config.getCusum();
        double k = cusumCfg.getKSigma();
        if (cusumCfg.getMode() != NEG_ONLY) {
            state.sPos = Math.max(0, state.sPos + (z - k));
        } else {
            state.sPos = 0;
        }
        if (cusumCfg.getMode() != POS_ONLY) {
            state.sNeg = Math.max(0, state.sNeg + (-z - k));
        } else {
            state.sNeg = 0;
        }
    }

    private BaselineSnapshot updateBaseline(double value, CusumState state) {
        TbBatchCusumNodeConfiguration.Baseline baselineCfg = config.getBaseline();
        if (!state.baselineInitialized) {
            initializeBaseline(value, state);
        }
        switch (baselineCfg.getMode()) {
            case EWM:
                state.mu = (1 - baselineCfg.getBetaMean()) * state.mu + baselineCfg.getBetaMean() * value;
                double diff = value - state.mu;
                state.variance = (1 - baselineCfg.getBetaVar()) * state.variance + baselineCfg.getBetaVar() * diff * diff;
                break;
            case ROLLING:
                updateRollingBaseline(value, state, baselineCfg.getRollingN());
                break;
            case FIXED:
                state.mu = baselineCfg.getMu0();
                state.variance = baselineCfg.getSigma0() * baselineCfg.getSigma0();
                break;
            default:
                throw new IllegalStateException("Unsupported baseline mode " + baselineCfg.getMode());
        }
        return new BaselineSnapshot(state.mu, Math.sqrt(Math.max(state.variance, 0)));
    }

    private void initializeBaseline(double value, CusumState state) {
        TbBatchCusumNodeConfiguration.Baseline baselineCfg = config.getBaseline();
        state.baselineInitialized = true;
        switch (baselineCfg.getMode()) {
            case EWM:
                state.mu = value;
                state.variance = baselineCfg.getSigma0() * baselineCfg.getSigma0();
                break;
            case ROLLING:
                state.addRolling(value);
                state.mu = value;
                state.variance = 0;
                break;
            case FIXED:
                state.mu = baselineCfg.getMu0();
                state.variance = baselineCfg.getSigma0() * baselineCfg.getSigma0();
                break;
            default:
                throw new IllegalStateException("Unsupported baseline mode " + baselineCfg.getMode());
        }
    }

    private void updateRollingBaseline(double value, CusumState state, int windowSize) {
        state.addRolling(value);
        while (state.rolling.size() > windowSize) {
            state.removeOldest();
        }
        double sum = 0;
        double sumSquares = 0;
        for (double v : state.rolling) {
            sum += v;
            sumSquares += v * v;
        }
        int n = state.rolling.size();
        if (n > 0) {
            double mean = sum / n;
            double variance = Math.max(0, (sumSquares / n) - mean * mean);
            state.mu = mean;
            state.variance = variance;
        }
    }

    private Batch parseBatch(TbMsg msg) {
        JsonNode root = JacksonUtil.toJsonNode(msg.getData());
        JsonNode arrayNode = resolvePath(root, config.getDataArrayPath());
        if (arrayNode == null || !arrayNode.isArray()) {
            return Batch.empty();
        }
        List<Sample> samples = new ArrayList<>();
        for (JsonNode raw : arrayNode) {
            resolveSample(raw).ifPresent(samples::add);
        }
        samples.sort(Comparator.comparingLong(sample -> sample.ts));
        return new Batch(samples);
    }

    private Optional<Sample> resolveSample(JsonNode raw) {
        JsonNode dataNode = resolvePath(raw, config.getDataFieldInItem());
        if (dataNode == null) {
            dataNode = raw;
        }
        JsonNode metadataNode = resolvePath(raw, config.getMetadataFieldInItem());
        if (metadataNode == null) {
            metadataNode = raw;
        }
        if (dataNode != null && dataNode.isTextual()) {
            dataNode = JacksonUtil.toJsonNode(dataNode.asText());
        }
        if (metadataNode != null && metadataNode.isTextual()) {
            metadataNode = JacksonUtil.toJsonNode(metadataNode.asText());
        }
        if (dataNode == null || metadataNode == null) {
            return Optional.empty();
        }
        JsonNode valueNode = dataNode.get(config.getValueJsonKey());
        if (valueNode == null) {
            return Optional.empty();
        }
        Double value = asDouble(valueNode);
        if (value == null) {
            return Optional.empty();
        }
        long ts = resolveTimestamp(raw, metadataNode);
        if (ts <= 0) {
            return Optional.empty();
        }
        Double tamb = extractTamb(metadataNode);
        String mode = metadataNode.hasNonNull(MODE_FIELD) ? metadataNode.get(MODE_FIELD).asText() : null;
        return Optional.of(new Sample(ts, value, tamb, mode));
    }

    private long resolveTimestamp(JsonNode raw, JsonNode metadataNode) {
        JsonNode tsNode = metadataNode.get(config.getTsFieldInMetadata());
        if (tsNode == null && raw.hasNonNull("ts")) {
            tsNode = raw.get("ts");
        }
        if (tsNode == null) {
            return 0;
        }
        if (tsNode.isNumber()) {
            return tsNode.asLong();
        }
        if (tsNode.isTextual()) {
            try {
                return Long.parseLong(tsNode.asText());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private Double extractTamb(JsonNode metadataNode) {
        JsonNode tambNode = metadataNode.get(TAMB_FIELD);
        if (tambNode == null) {
            tambNode = metadataNode.get(TAMB_FIELD_LOWER);
        }
        return tambNode != null ? asDouble(tambNode) : null;
    }

    private Double asDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private JsonNode resolvePath(JsonNode node, String path) {
        if (Strings.isNullOrEmpty(path) || node == null) {
            return node;
        }
        String[] tokens = path.split("\\.");
        JsonNode current = node;
        for (String token : tokens) {
            if (Strings.isNullOrEmpty(token)) {
                continue;
            }
            current = current.get(token);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private void applyMetadata(BatchProcessingResult result, TbMsgMetaData md) {
        TbBatchCusumNodeConfiguration.EmitKeys keys = config.getEmitKeys();
        put(md, keys.getMu(), result.mu());
        put(md, keys.getSigma(), result.sigma());
        put(md, keys.getZLast(), result.lastZ());
        put(md, keys.getSPos(), result.sPos());
        put(md, keys.getSNeg(), result.sNeg());
        if (keys.getDrift() != null) {
            md.putValue(keys.getDrift(), result.drift().name());
        }
        if (keys.getDecision() != null) {
            md.putValue(keys.getDecision(), result.decision().name());
        }
        if (keys.getReason() != null) {
            md.putValue(keys.getReason(), result.reason());
        }
        if (keys.getBatchPoints() != null) {
            md.putValue(keys.getBatchPoints(), Integer.toString(result.batchPoints()));
        }
    }

    private void put(TbMsgMetaData md, String key, double value) {
        if (key != null) {
            md.putValue(key, Double.isFinite(value) ? Double.toString(value) : "NaN");
        }
    }

    @Override
    public void destroy() {
        states.clear();
    }

    @Override
    public void onPartitionChangeMsg(TbContext ctx, PartitionChangeMsg msg) {
        states.clear();
    }

    private enum Decision {
        ALARM,
        WARN,
        OK,
        INSUFFICIENT
    }

    private enum Drift {
        POS,
        NEG,
        NONE
    }

    private record BatchProcessingResult(int batchPoints, double mu, double sigma, double lastZ,
                                         double sPos, double sNeg, Drift drift,
                                         Decision decision, String reason) {
        String relation() {
            return decision.name();
        }

        static BatchProcessingResult insufficient(CusumState state, String reason, Decision decision) {
            return new BatchProcessingResult(0, state.mu, Math.sqrt(Math.max(state.variance, 0)),
                    state.lastZ, state.sPos, state.sNeg, state.lastDrift, decision, reason);
        }
    }

    private static final class Batch {
        private final List<Sample> samples;

        private Batch(List<Sample> samples) {
            this.samples = samples;
        }

        static Batch empty() {
            return new Batch(Collections.emptyList());
        }
    }

    private static final class Sample {
        private final long ts;
        private final double value;
        private final Double tamb;
        private final String mode;

        private Sample(long ts, double value, Double tamb, String mode) {
            this.ts = ts;
            this.value = value;
            this.tamb = tamb;
            this.mode = mode;
        }
    }

    private static final class BaselineSnapshot {
        private final double mean;
        private final double sigma;

        private BaselineSnapshot(double mean, double sigma) {
            this.mean = mean;
            this.sigma = sigma;
        }
    }

    private static final class CusumState {
        private double mu;
        private double variance;
        private double sPos;
        private double sNeg;
        private long lastTs;
        private int batches;
        private int belowK;
        private Decision lastDecision = Decision.INSUFFICIENT;
        private double lastZ;
        private Drift lastDrift = Drift.NONE;
        private Double lastTamb;
        private String lastMode;
        private boolean baselineInitialized;
        private final Deque<Double> rolling = new ArrayDeque<>();

        private void reset() {
            mu = 0;
            variance = 0;
            sPos = 0;
            sNeg = 0;
            lastTs = 0;
            batches = 0;
            belowK = 0;
            lastDecision = Decision.INSUFFICIENT;
            lastZ = 0;
            lastDrift = Drift.NONE;
            lastTamb = null;
            lastMode = null;
            baselineInitialized = false;
            rolling.clear();
        }

        private void addRolling(double value) {
            rolling.addLast(value);
        }

        private void removeOldest() {
            if (!rolling.isEmpty()) {
                rolling.removeFirst();
            }
        }
    }
}
