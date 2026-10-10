package dev.valart.postgreslab.api;

import dev.valart.postgreslab.model.offer.ImportResult;
import dev.valart.postgreslab.model.offer.Offer;
import dev.valart.postgreslab.model.offer.OfferBatch;
import dev.valart.postgreslab.model.offer.OfferPatch;
import dev.valart.postgreslab.service.OfferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
public class OfferController {

    private final OfferService offers;

    @PostMapping("/batch")
    public ImportResult register(@RequestBody OfferBatch request,
                                 @RequestParam(defaultValue = "ordered") String mode,
                                 @RequestParam(defaultValue = "false") boolean retry) {
        return offers.register(request.offers(), mode, retry);
    }

    @PatchMapping("/{id}")
    public Offer update(@PathVariable long id, @RequestBody OfferPatch request) {
        return offers.update(id, request);
    }
}
