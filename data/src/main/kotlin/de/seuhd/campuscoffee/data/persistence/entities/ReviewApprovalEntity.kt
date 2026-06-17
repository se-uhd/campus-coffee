package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.Table

/**
 * Database entity recording that a user approved a review. Carries its own surrogate id (driven by
 * review_approvals_seq), mirroring the reviews table, plus the review and user references. The named
 * unique constraint on (review_id, user_id) enforces "one approval per user per review".
 */
@jakarta.persistence.Entity
@Table(name = "review_approvals")
class ReviewApprovalEntity : Entity() {
    @field:Column(name = "review_id")
    var reviewId: Long? = null

    @field:Column(name = "user_id")
    var userId: Long? = null

    companion object {
        /** Name of the unique constraint on (review_id, user_id), declared in the Flyway migration. */
        const val REVIEW_USER_UNIQUE_CONSTRAINT = "uq_review_approvals_review_user"
    }
}
