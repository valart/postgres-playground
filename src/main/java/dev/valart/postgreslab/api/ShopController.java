package dev.valart.postgreslab.api;

import dev.valart.postgreslab.model.shop.CreateShopRequest;
import dev.valart.postgreslab.model.shop.Shop;
import dev.valart.postgreslab.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shops;

    @PostMapping
    public Shop create(@RequestBody CreateShopRequest request) {
        return shops.create(request.name());
    }

    @GetMapping("/{id}")
    public Shop get(@PathVariable long id) {
        return shops.get(id);
    }
}
