package com.workin.backend.requests;

import jakarta.validation.constraints.NotBlank;

/** Legacy reject.php requires a rejection reason; stored in reply. */
public record RejectRequestRequest(@NotBlank String reply) {
}
