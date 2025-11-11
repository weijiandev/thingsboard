# Batch CUSUM Filter Node

The batch CUSUM node analyses batched telemetry payloads (for example, 5- or 10-minute
windows of temperature samples) and emits a single classification per batch. It is
optimised for slow drifts such as gradual temperature rise/decline, zero-drift or offset
anomalies and complements traditional threshold and EWMA detectors.

## Input expectations

* The inbound message `data` must contain an array (default path: `messages`) of objects.
* Each element must contain telemetry under the `data` field (stringified JSON is
  supported) and metadata under the `metadata` field. The timestamp is read from
  `metadata.ts` and falls back to the item level `ts` field when missing.
* Temperature values are extracted from `data.temperature` by default. Configure
  `valueJsonKey` if the payload uses a different attribute.
* The node sorts all points in a batch by timestamp before processing. Missing or invalid
  points are skipped.

## Configuration overview

```jsonc
{
  "dataArrayPath": "messages",
  "dataFieldInItem": "data",
  "metadataFieldInItem": "metadata",
  "valueJsonKey": "temperature",
  "tsFieldInMetadata": "ts",
  "zDomain": true,
  "baseline": {
    "mode": "EWM",          // EWM | ROLLING | FIXED
    "betaMean": 0.1,
    "betaVar": 0.1,
    "rollingN": 12,
    "mu0": 30.0,
    "sigma0": 1.0
  },
  "cusum": {
    "mode": "TWO_SIDED",    // TWO_SIDED | POS_ONLY | NEG_ONLY
    "kSigma": 0.5,
    "hWarnSigma": 3.0,
    "hAlarmSigma": 5.0,
    "resetPolicy": "ON_ALARM" // ON_ALARM | ON_BATCH_END | NEVER
  },
  "hysteresis": {
    "hClearSigma": 2.0,
    "minBatchesBelowK": 1
  },
  "gates": {
    "startBelow": 55.0,
    "deltaTamb": 5.0,
    "modes": ["charge", "cc_cv"]
  },
  "resetIfGapSec": 0,
  "warmupBatches": 1,
  "emitKeys": {
    "zLast": "_CUS_z",
    "sPos": "_CUS_pos",
    "sNeg": "_CUS_neg",
    "drift": "_CUS_drift",
    "decision": "_CUS_decision",
    "reason": "_CUS_reason",
    "batchPoints": "_CUS_batchPoints",
    "mu": "_CUS_mu",
    "sigma": "_CUS_sigma"
  },
  "asFilter": true
}
```

### Baseline modes

* **EWM (default):** Exponentially weighted updates of the mean and variance with
  configurable smoothing factors.
* **ROLLING:** Maintains a rolling window of the last `rollingN` points to compute the
  baseline and dispersion.
* **FIXED:** Keeps the baseline at the configured `mu0` and `sigma0`.

### CUSUM and decision logic

For each batch item, the node optionally converts values to the z-domain using the current
baseline mean and sigma. It then updates the positive/negative CUSUM statistics according
to the configured mode. Batch end decisions:

* `ALARM` when `max(S⁺, S⁻) ≥ hAlarmSigma`
* `WARN` when `max(S⁺, S⁻) ≥ hWarnSigma`
* otherwise `OK`

Warm-up batches (default: the first batch) only update the baseline and emit the
`INSUFFICIENT` relation.

### Hysteresis and reset

If the previous batch produced an `ALARM`, the node will hold that state until the current
batch satisfies both conditions:

1. `max(S⁺, S⁻) < hClearSigma`
2. At least `minBatchesBelowK` consecutive batches stayed below the reference `kSigma`.

The `resetPolicy` controls when to clear the cumulative sums: on every alarm, after each
batch, or never (fully persistent).

### Gating

Optional gates reduce false positives by suppressing decisions until operating conditions
are met:

* `startBelow` – only evaluate when the current baseline mean is below this value.
* `deltaTamb` – require the baseline mean to exceed the ambient temperature (`Tamb`) by a
  given delta (metadata key `Tamb` or `tamb`).
* `modes` – whitelist of metadata `mode` values; if the current mode is absent or not
  listed, the node outputs `INSUFFICIENT`.

### Outputs

Only one message is emitted per batch. Metadata keys (configurable via `emitKeys`) include:

```json
{
  "_CUS_mu": 33.02,
  "_CUS_sigma": 0.42,
  "_CUS_z": 1.9,
  "_CUS_pos": 5.4,
  "_CUS_neg": 0.0,
  "_CUS_drift": "POS",
  "_CUS_batchPoints": 10,
  "_CUS_decision": "ALARM",
  "_CUS_reason": "POS>=hAlarm"
}
```

When `asFilter` is enabled, the node routes the enriched message through the `ALARM`,
`WARN`, `OK` or `INSUFFICIENT` relation, otherwise it simply emits a success path.
