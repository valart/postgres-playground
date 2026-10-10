package dev.valart.postgreslab.service;

import dev.valart.postgreslab.model.product.Product;
import dev.valart.postgreslab.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository repository;

    @Transactional
    public Product create(String name) {
        return repository.create(name);
    }

    public Product get(long id) {
        return repository.get(id);
    }
}
