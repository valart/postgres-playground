package dev.valart.postgreslab.api;

import dev.valart.postgreslab.model.offer.Offer;
import dev.valart.postgreslab.model.product.CreateProductRequest;
import dev.valart.postgreslab.model.product.Product;
import dev.valart.postgreslab.service.OfferService;
import dev.valart.postgreslab.service.ProductService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService products;
    private final OfferService offers;

    @PostMapping
    public Product create(@RequestBody CreateProductRequest request) {
        return products.create(request.name());
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable long id) {
        return products.get(id);
    }

    @GetMapping("/{id}/offers")
    public List<Offer> cheapestOffers(@PathVariable long id,
                                      @RequestParam(defaultValue = "20") int limit) {
        return offers.cheapest(id, limit);
    }
}
