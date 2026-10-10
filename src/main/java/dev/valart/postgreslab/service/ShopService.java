package dev.valart.postgreslab.service;

import dev.valart.postgreslab.model.shop.Shop;
import dev.valart.postgreslab.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShopService {
    private final ShopRepository repository;

    @Transactional
    public Shop create(String name) {
        return repository.create(name);
    }

    public Shop get(long id) {
        return repository.get(id);
    }
}
