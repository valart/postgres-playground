package dev.valart.postgreslab.api;

import dev.valart.postgreslab.model.lab.DemoResult;
import dev.valart.postgreslab.service.DeadlockDemoService;
import dev.valart.postgreslab.service.LabService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/lab")
@RequiredArgsConstructor
public class LabController {

    private final LabService lab;
    private final DeadlockDemoService demo;

    @PostMapping(value = "/reset-and-seed", produces = "text/plain")
    public String seed(@RequestParam(defaultValue = "small") String size) {
        return lab.seed(size);
    }

    @PostMapping(value = "/index/{variant}", produces = "text/plain")
    public String index(@PathVariable String variant) {
        return lab.index(variant);
    }

    @GetMapping(value = "/explain-offers", produces = "text/plain")
    public String explain(@RequestParam(defaultValue = "777") long productId) {
        return lab.explain(productId);
    }

    @PostMapping(value = "/reset-minima", produces = "text/plain")
    public String resetMinima() {
        return lab.resetMinima();
    }

    @PostMapping(value = "/reset-imports", produces = "text/plain")
    public String resetImports() {
        return lab.resetImports();
    }

    @PostMapping("/import-shop")
    public long importShop() {
        return lab.importShop();
    }

    @PostMapping("/import-products")
    public List<Long> importProducts(@RequestParam int count) {
        return lab.importProducts(count);
    }

    @GetMapping(value = "/update-data", produces = "text/plain")
    public String updateData(@RequestParam(defaultValue = "8000") int limit) {
        return lab.updateData(limit);
    }

    @PostMapping(value = "/vacuum", produces = "text/plain")
    public String vacuum() {
        return lab.vacuum();
    }

    @PostMapping("/demo-deadlock")
    public DemoResult deadlock(@RequestParam(defaultValue = "unordered") String mode,
                               @RequestParam(defaultValue = "false") boolean retry) {
        return demo.demonstrate(mode, retry);
    }

    @PostMapping(value = "/hold-snapshot", produces = "text/plain")
    public String snapshot(@RequestParam(defaultValue = "120") int seconds) {
        return lab.holdSnapshot(seconds);
    }
}
