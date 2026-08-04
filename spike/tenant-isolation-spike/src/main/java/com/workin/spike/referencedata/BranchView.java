package com.workin.spike.referencedata;

public record BranchView(Long id, Long companyId, String name) {

    public static BranchView of(Branch branch) {
        return new BranchView(branch.getId(), branch.getCompanyId(), branch.getName());
    }
}
