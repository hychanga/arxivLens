package com.arxivlens.controller;

import com.arxivlens.dto.TrendDtos.TrendsResponse;
import com.arxivlens.service.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trends")
public class TrendController {

    private final TrendService service;

    public TrendController(TrendService service) {
        this.service = service;
    }

    @GetMapping
    public TrendsResponse get(@RequestParam(name = "source", defaultValue = "arxiv") String source) {
        return service.compute(source);
    }
}
