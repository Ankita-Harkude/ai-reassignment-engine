package com.ziprun.reassignment.domain;

/**
 * ASSIGNED -> REASSIGNMENT_PENDING -> REASSIGNED -> DELIVERED
 * (REASSIGNED / ASSIGNED can re-enter REASSIGNMENT_PENDING if their agent drops out again.)
 */
public enum OrderStatus {
    ASSIGNED, REASSIGNMENT_PENDING, REASSIGNED, DELIVERED
}
