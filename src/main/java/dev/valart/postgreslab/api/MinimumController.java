package dev.valart.postgreslab.api;

import dev.valart.postgreslab.model.minimum.Minimum;
import dev.valart.postgreslab.service.MinimumService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
public class MinimumController {

    private final MinimumService minima;

    @GetMapping("/{id}/minimum")
    public Minimum get(@PathVariable long id) {
        return minima.minimum(id);
    }
}
