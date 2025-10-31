package com.example.rulechain.web;

import com.example.rulechain.core.RuleContext;
import com.example.rulechain.registry.RuleNodeDefinition;
import com.example.rulechain.registry.RuleNodeRegistry;
import com.example.rulechain.service.RuleChainExecutionService;
import com.example.rulechain.web.dto.ExecuteRuleChainRequest;
import com.example.rulechain.web.dto.RuleChainExecutionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/rule-chain")
@Validated
public class RuleChainController {

    private final RuleChainExecutionService ruleChainExecutionService;

    public RuleChainController(RuleChainExecutionService ruleChainExecutionService) {
        this.ruleChainExecutionService = ruleChainExecutionService;
    }

    @GetMapping("/nodes")
    public List<RuleNodeDefinition> getAvailableNodes() {
        RuleNodeRegistry registry = ruleChainExecutionService.getRegistry();
        return registry.getDefinitions();
    }

    @PostMapping("/execute")
    public ResponseEntity<RuleChainExecutionResponse> execute(@Valid @RequestBody ExecuteRuleChainRequest request) {
        RuleContext context = ruleChainExecutionService.execute(request.getNodes(), request.getPayload());
        RuleChainExecutionResponse response = new RuleChainExecutionResponse(
                context.getUnmodifiablePayload(),
                context.getUnmodifiableMetadata()
        );
        return ResponseEntity.ok(response);
    }
}
