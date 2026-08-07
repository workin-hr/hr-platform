package com.workin.backend.requests;

/** Reply is optional on approval (legacy: empty reply stored as null). */
public record ApproveRequestRequest(String reply) {
}
