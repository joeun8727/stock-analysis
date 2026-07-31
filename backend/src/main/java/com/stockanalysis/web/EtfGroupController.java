package com.stockanalysis.web;

import com.stockanalysis.data.EtfGroupService;
import com.stockanalysis.data.EtfGroupService.EtfGroupView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/etf-groups")
public class EtfGroupController {

    private final EtfGroupService service;

    public EtfGroupController(EtfGroupService service) {
        this.service = service;
    }

    public record CreateRequest(String name) {
    }

    /** All groups with their leverage/inverse/futures slots and a readiness flag. */
    @GetMapping
    public List<EtfGroupView> list() {
        return service.listAll();
    }

    @PostMapping
    public EtfGroupView create(@RequestBody CreateRequest req) {
        return service.view(service.createGroup(req.name()).getId());
    }

    @PatchMapping("/{id}")
    public EtfGroupView rename(@PathVariable long id, @RequestBody CreateRequest req) {
        return service.view(service.rename(id, req.name()).getId());
    }

    /** Removes the group only; its datasets stay and become ungrouped. */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
