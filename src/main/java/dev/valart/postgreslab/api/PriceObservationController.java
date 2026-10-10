package dev.valart.postgreslab.api;

import dev.valart.postgreslab.model.observation.ObservationBatch;
import dev.valart.postgreslab.model.observation.ObservationResult;
import dev.valart.postgreslab.service.MinimumService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/price-observations")
@RequiredArgsConstructor
public class PriceObservationController {

    private final MinimumService minima;

    @PostMapping("/batch")
    public ObservationResult observe(@RequestBody ObservationBatch request,
                                     @RequestParam(defaultValue = "conditional") String mode) {
        return minima.observe(request.observations(), mode);
    }
}
