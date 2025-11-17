# EWMA & CUSUM Detector Quickstart

The EWMA, CUSUM, Isolation Forest, and fusion rule nodes that were added for the
battery temperature alarm strategy behave like regular ThingsBoard filter nodes:
they take the latest telemetry payload, read the configured metric field, and
append a `detectors.<outputKey>` object with the computed statistics.  The
`TRUE/FALSE` relations correspond to `isAnomaly=true/false` so you can connect
follow-up nodes exactly like any other filter in a rule chain.

This guide shows how to feed the sample telemetry shared in the review into the
EWMA and CUSUM detectors and interpret the results.

## 1. Sample telemetry

Paste the following payloads (timestamps follow the original example) through
`Device Telemetry` → `Rule Chain` to reproduce the scenario:

| # | ts (ms) | temperature | terminal_voltage | terminal_current |
|---|---------|-------------|------------------|------------------|
| 1 | 1735730820000 | 32.84155417 | 3.534080602 | -2.015123369 |
| 2 | 1735730880000 | 32.87942219 | 3.531461277 | -2.012369681 |
| 3 | 1735730940000 | 32.92381148 | 3.529274079 | -2.012791881 |
| 4 | 1735731000000 | 32.97573970 | 3.526904572 | -2.011469332 |
| 5 | 1735731060000 | 33.01788126 | 3.524647034 | -2.012191054 |
| 6 | 1735731120000 | 33.05835416 | 3.522311706 | -2.010756825 |
| 7 | 1735731180000 | 33.09964421 | 3.520236396 | -2.011050023 |
| 8 | 1735731240000 | 33.13425403 | 3.517935782 | -2.008007948 |
| 9 | 1735731300000 | 33.19574150 | 3.515832489 | -2.013654984 |
|10 | 1735731360000 | 33.24199061 | 3.513606524 | -2.008617392 |

## 2. EWMA detector configuration

1. Drag the **EWMA detector** node into your rule chain.
2. Use the following configuration to match the strategy in the design doc, but
   reduce the warm-up window so the sample data becomes actionable immediately:

```json
{
  "field": "metrics.temperature",
  "alpha": 0.2,
  "sigmaThreshold": 3.0,
  "initPoints": 5,
  "minHistory": 5,
  "missingPolicy": "SKIP",
  "direction": "UP",
  "outputKey": "ewma",
  "persistState": true
}
```

3. Connect the `True` relation to the nodes that should fire when a high
   temperature is detected and the `False` relation to the normal-flow branch.

With this config, the EWMA node will emit the following detector objects in the
message body while the sample telemetry is replayed:

| # | temperature | ewma | z-score | isAnomaly |
|---|-------------|------|---------|-----------|
| 5 | 33.0179 | 32.9277 | 1.27 | false |
| 6 | 33.0584 | 32.9538 | 1.26 | false |
| 7 | 33.0996 | 32.9830 | 1.23 | false |
| 8 | 33.1343 | 33.0132 | 1.15 | false |
| 9 | 33.1957 | 33.0497 | 1.22 | false |
|10 | 33.2420 | 33.0882 | 1.15 | false |

Once the live stream drifts farther than `3σ` above the EWMA baseline, the node
will automatically start routing the message through the `True` relation and you
will find an `isAnomaly: true` flag inside `detectors.ewma`.

## 3. CUSUM detector configuration

1. Add the **CUSUM detector** node and apply the following configuration:

```json
{
  "field": "metrics.temperature",
  "aggType": "mean",
  "direction": "UP",
  "targetMeanMode": "AUTO",
  "minHistory": 5,
  "kMode": "AUTO_SIGMA",
  "kSigmaFactor": 0.5,
  "h": 5.0,
  "resetPolicy": "ON_ALARM",
  "outputKey": "cusum"
}
```

2. Link the node’s `True/False` relations exactly like the EWMA node.

While the ten telemetry points above are still in the “small drift” range, the
CUSUM node records steadily increasing `Cplus` scores but never exceeds the
`h = 5` decision interval:

| # | temperature | Cplus | score | isAnomaly |
|---|-------------|-------|-------|-----------|
| 5 | 33.0179 | 0.07 | 0.07 | false |
| 6 | 33.0584 | 0.16 | 0.16 | false |
| 7 | 33.0996 | 0.30 | 0.30 | false |
| 8 | 33.1343 | 0.47 | 0.47 | false |
| 9 | 33.1957 | 0.70 | 0.70 | false |
|10 | 33.2420 | 0.98 | 0.98 | false |

If the drift keeps growing, `Cplus` crosses the `h` threshold, the message is
sent via the `True` relation, and `detectors.cusum.isAnomaly` flips to `true`.

## 4. Using the detector outputs

Once both nodes are in the chain, every telemetry message carries a `detectors`
section:

```json
"detectors": {
  "ewma": {
    "isAnomaly": false,
    "z": 1.23,
    "ewma": 32.98,
    "alpha": 0.2,
    "sigmaThreshold": 3.0
  },
  "cusum": {
    "isAnomaly": false,
    "score": 0.30,
    "Cplus": 0.30,
    "Cminus": 0.0,
    "k": 0.0355,
    "h": 5.0
  }
}
```

These outputs can be consumed directly by a **fusion** node (to implement the
“三算子两票通过” rule) or forwarded to alarms, notifications, or dashboards.
