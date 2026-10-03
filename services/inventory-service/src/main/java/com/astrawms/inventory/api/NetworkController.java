package com.astrawms.inventory.api;

import com.astrawms.inventory.service.Network;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Site network view, inventory half (ADR-0024). Reads only; the user's site and owner scope applies. */
@RestController
public class NetworkController {

    private final Network network;

    public NetworkController(Network network) {
        this.network = network;
    }

    /** Per site of the user's scope: on hand, allocated, held, aged dock stock, frozen by a physical inventory. */
    @GetMapping("/api/v1/network/inventory")
    public List<Map<String, Object>> sites() {
        return network.sites();
    }

    /** Per aisle of one site, for the digital twin. */
    @GetMapping("/api/v1/sites/{siteId}/inventory/aisles")
    public List<Map<String, Object>> aisles(@PathVariable String siteId) {
        return network.aisles(siteId);
    }
}
