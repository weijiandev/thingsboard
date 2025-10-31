package com.example.rulechain;

import com.example.rulechain.core.RuleContext;
import com.example.rulechain.service.RuleChainExecutionService;
import com.example.rulechain.web.dto.RuleChainNodeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RuleChainExecutionServiceTests {

    @Autowired
    private RuleChainExecutionService ruleChainExecutionService;

    @Test
    void executesRuleChainAndEnrichesMetadata() {
        RuleChainNodeRequest threshold = new RuleChainNodeRequest();
        threshold.setType("threshold");
        threshold.setConfiguration(Map.of(
                "payloadKey", "temperature",
                "threshold", 20,
                "operator", "GREATER_THAN"
        ));

        RuleChainNodeRequest enrich = new RuleChainNodeRequest();
        enrich.setType("enrich");
        enrich.setConfiguration(Map.of(
                "payloadKey", "temperature",
                "metadataKey", "lastTemperature"
        ));

        RuleChainNodeRequest log = new RuleChainNodeRequest();
        log.setType("log");
        log.setConfiguration(Map.of("message", "Threshold satisfied"));

        RuleContext context = ruleChainExecutionService.execute(
                List.of(threshold, enrich, log),
                Map.of("temperature", 25)
        );

        assertThat(context.getUnmodifiableMetadata())
                .containsEntry("lastTemperature", 25);
    }

    @Test
    void stopsExecutionWhenThresholdFails() {
        RuleChainNodeRequest threshold = new RuleChainNodeRequest();
        threshold.setType("threshold");
        threshold.setConfiguration(Map.of(
                "payloadKey", "temperature",
                "threshold", 50,
                "operator", "GREATER_THAN"
        ));

        RuleChainNodeRequest enrich = new RuleChainNodeRequest();
        enrich.setType("enrich");
        enrich.setConfiguration(Map.of(
                "payloadKey", "temperature",
                "metadataKey", "lastTemperature"
        ));

        RuleContext context = ruleChainExecutionService.execute(
                List.of(threshold, enrich),
                Map.of("temperature", 20)
        );

        assertThat(context.getUnmodifiableMetadata())
                .doesNotContainKey("lastTemperature");
    }
}
