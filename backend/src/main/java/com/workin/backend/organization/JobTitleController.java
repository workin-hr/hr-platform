package com.workin.backend.organization;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/job-titles")
public class JobTitleController {

	private final JobTitleService jobTitleService;

	public JobTitleController(JobTitleService jobTitleService) {
		this.jobTitleService = jobTitleService;
	}

	@RequiresPermission(PermissionKeys.JOB_TITLES_READ)
	@GetMapping
	public List<JobTitleView> list(HttpServletRequest request) {
		return jobTitleService.list(BranchController.contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.JOB_TITLES_READ)
	@GetMapping("/{jobTitleId}")
	public JobTitleView get(HttpServletRequest request, @PathVariable Long jobTitleId) {
		return jobTitleService.get(BranchController.contextFrom(request), jobTitleId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.JOB_TITLES_MANAGE)
	@PostMapping
	public ResponseEntity<JobTitleView> create(
			HttpServletRequest request, @Valid @RequestBody UpsertJobTitleRequest body) {
		return jobTitleService.create(BranchController.contextFrom(request), body)
				.map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.JOB_TITLES_MANAGE)
	@PutMapping("/{jobTitleId}")
	public JobTitleView update(
			HttpServletRequest request, @PathVariable Long jobTitleId, @Valid @RequestBody UpsertJobTitleRequest body) {
		return jobTitleService.update(BranchController.contextFrom(request), jobTitleId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.JOB_TITLES_MANAGE)
	@DeleteMapping("/{jobTitleId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long jobTitleId) {
		if (!jobTitleService.delete(BranchController.contextFrom(request), jobTitleId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

}
