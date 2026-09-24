package com.mail.rule;

import com.mail.common.ApiResponse;
import com.mail.rule.domain.Rule;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @GetMapping
    public ApiResponse<List<Rule>> list() {
        return ApiResponse.ok(ruleService.list());
    }

    @PostMapping
    public ApiResponse<Rule> create(@RequestBody Rule rule) {
        return ApiResponse.ok(ruleService.create(rule));
    }

    @PutMapping("/{id}")
    public ApiResponse<Rule> update(@PathVariable Long id, @RequestBody Rule rule) {
        return ApiResponse.ok(ruleService.update(id, rule));
    }

    @PutMapping("/{id}/toggle")
    public ApiResponse<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        ruleService.toggle(id, enabled);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/reorder")
    public ApiResponse<Void> reorder(@RequestBody Map<String, List<Long>> body) {
        ruleService.reorder(body.get("ids"));
        return ApiResponse.ok();
    }
}