package com.workin.spike.referencedata;

import java.util.List;
import java.util.Optional;

/**
 * One interface, two implementations -- {@code RlsBranchService} and
 * {@code GuardBranchService} in the {@code isolation} sub-package,
 * switched via Spring profile ({@code isolation-rls} /
 * {@code isolation-guard}). BranchController depends only on this
 * interface, so both mechanisms are exercised through identical
 * controller/business logic -- the only thing that differs between the
 * two spike runs is which isolation mechanism is active underneath.
 */
public interface BranchService {

    List<BranchView> listForCurrentCompany();

    BranchView create(String name);

    Optional<BranchView> findById(Long id);
}
