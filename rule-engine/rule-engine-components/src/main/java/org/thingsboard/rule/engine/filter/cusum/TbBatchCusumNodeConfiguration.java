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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

import java.util.Arrays;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TbBatchCusumNodeConfiguration implements NodeConfiguration<TbBatchCusumNodeConfiguration> {

    private String dataArrayPath = "messages";
    private String dataFieldInItem = "data";
    private String metadataFieldInItem = "metadata";
    private String valueJsonKey = "temperature";
    private String tsFieldInMetadata = "ts";

    private boolean zDomain = true;
    private Baseline baseline = new Baseline();
    private Cusum cusum = new Cusum();
    private Hysteresis hysteresis = new Hysteresis();
    private Gates gates = new Gates();

    private long resetIfGapSec = 0;
    private int warmupBatches = 1;

    private EmitKeys emitKeys = new EmitKeys();
    private boolean asFilter = true;
    private PersistState persistState = new PersistState();

    @Override
    public TbBatchCusumNodeConfiguration defaultConfiguration() {
        return new TbBatchCusumNodeConfiguration();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Baseline {
        private BaselineMode mode = BaselineMode.EWM;
        private double betaMean = 0.1;
        private double betaVar = 0.1;
        private int rollingN = 12;
        private double mu0 = 30.0;
        private double sigma0 = 1.0;
    }

    public enum BaselineMode {
        EWM,
        ROLLING,
        FIXED
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cusum {
        private CusumMode mode = CusumMode.TWO_SIDED;
        private double kSigma = 0.5;
        private double hWarnSigma = 3.0;
        private double hAlarmSigma = 5.0;
        private ResetPolicy resetPolicy = ResetPolicy.ON_ALARM;
    }

    public enum CusumMode {
        TWO_SIDED,
        POS_ONLY,
        NEG_ONLY
    }

    public enum ResetPolicy {
        ON_ALARM,
        ON_BATCH_END,
        NEVER
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hysteresis {
        private double hClearSigma = 2.0;
        private int minBatchesBelowK = 1;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Gates {
        private Double startBelow = 55.0;
        private Double deltaTamb = 5.0;
        private List<String> modes = Arrays.asList("charge", "cc_cv");
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmitKeys {
        private String zLast = "_CUS_z";
        private String sPos = "_CUS_pos";
        private String sNeg = "_CUS_neg";
        private String drift = "_CUS_drift";
        private String decision = "_CUS_decision";
        private String reason = "_CUS_reason";
        private String batchPoints = "_CUS_batchPoints";
        private String mu = "_CUS_mu";
        private String sigma = "_CUS_sigma";
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersistState {
        private boolean enabled = true;
        private String scopeKey = "_STATE_CUSUM_BATCH";
    }
}
