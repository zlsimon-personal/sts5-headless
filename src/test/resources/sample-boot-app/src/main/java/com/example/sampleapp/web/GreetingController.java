package com.example.sampleapp.web;

import com.example.sampleapp.service.GreetingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two request mappings with resolved paths (a path variable and a body)
 * so {@code getRequestMappings} returns concrete, non-empty data.
 */
@RestController
@RequestMapping("/api/greetings")
public class GreetingController {

    private final GreetingService service;

    public GreetingController(GreetingService service) {
        this.service = service;
    }

    @GetMapping("/{name}")
    public String greet(@PathVariable("name") String name) {
        return service.greet(name);
    }

    @PostMapping
    public String greetBody(@RequestBody String name) {
        return service.greet(name);
    }
}
