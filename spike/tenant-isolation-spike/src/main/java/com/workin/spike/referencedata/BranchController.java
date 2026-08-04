package com.workin.spike.referencedata;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Depends only on {@link BranchService} -- identical controller code
 * runs under both the "isolation-rls" and "isolation-guard" profiles;
 * only the injected implementation differs.
 */
@RestController
@RequestMapping("/api/branches")
public class BranchController {

    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    @GetMapping
    public List<BranchView> list() {
        return branchService.listForCurrentCompany();
    }

    @PostMapping
    public BranchView create(@Valid @RequestBody CreateBranchRequest request) {
        return branchService.create(request.name());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BranchView> get(@PathVariable Long id) {
        return branchService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
