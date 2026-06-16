package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.configuration.ApprovalConfiguration
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.objects.Review
import de.seuhd.campuscoffee.domain.model.objects.persistedId
import de.seuhd.campuscoffee.domain.ports.api.ReviewService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import de.seuhd.campuscoffee.domain.ports.data.PosDataService
import de.seuhd.campuscoffee.domain.ports.data.ReviewDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Implementation of the Review service that handles business logic related to review entities.
 */
@Service
class ReviewServiceImpl(
    private val reviewDataService: ReviewDataService,
    private val userDataService: UserDataService,
    private val posDataService: PosDataService,
    private val approvalConfiguration: ApprovalConfiguration
) : CrudServiceImpl<Review, Long>(Review::class.java),
    ReviewService {
    override fun dataService(): CrudDataService<Review, Long> = reviewDataService

    @Transactional
    override fun upsert(domainObject: Review): Review {
        // validate that the POS exists before creating or updating the review
        val pos = posDataService.getById(domainObject.pos.persistedId)

        val reviewToUpsert =
            domainObject.id?.let { id ->
                // loading the existing review first makes an update of a missing review a 404
                val existingReview = reviewDataService.getById(id)

                // a review's POS and author are fixed at creation: re-pointing it would bypass the
                // one-review-per-author-per-POS rule and carry approvals to a POS nobody approved
                // for, and changing the author could mark a review as approved by its own author
                if (existingReview.pos.id != pos.id || existingReview.author.id != domainObject.author.id) {
                    throw ValidationException(
                        "The POS and author of review with ID '$id' cannot be changed."
                    )
                }

                // approvals are managed by the approval workflow (see approve); an update keeps the
                // existing approval state instead of accepting it from the caller
                domainObject.copy(
                    approvalCount = existingReview.approvalCount,
                    approved = existingReview.approved
                )
            } ?: domainObject

        // an author may review a POS only once (updates cannot change the pair, so only creation can
        // violate the rule); the uq_reviews_pos_author database constraint is the authoritative guard
        // that also closes the concurrent-create race, reported with the same 409 as this check
        if (domainObject.id == null && reviewDataService.filter(pos, domainObject.author).isNotEmpty()) {
            throw DuplicationException(
                Review::class.java,
                "pos_id/author_id",
                "POS ${pos.id}, author ${domainObject.author.id}"
            )
        }

        return super.upsert(reviewToUpsert)
    }

    @Transactional(readOnly = true)
    override fun filter(
        posId: Long,
        approved: Boolean
    ): List<Review> = reviewDataService.filter(posDataService.getById(posId), approved)

    // TODO (Exercise 5): record who approved in the provided review_approvals table (unique
    //  (review_id, user_id) via ReviewApprovalDataService) so a user can approve a review at most once,
    //  and derive approvalCount/approved from it -- an anonymous count cannot enforce that. The approver
    //  must come from the authenticated principal (Exercise 2), not the client-asserted userId below.
    @Transactional
    override fun approve(
        reviewId: Long,
        userId: Long
    ): Review {
        log.info(
            "Processing approval request for review with ID '{}' by user with ID '{}'...",
            reviewId,
            userId
        )

        // validate that the user exists
        val user = userDataService.getById(userId)
        val approverId = user.persistedId

        // validate that the review exists
        val reviewToApprove = reviewDataService.getById(reviewId)
        val authorId = reviewToApprove.author.persistedId

        // a user cannot approve their own review
        if (authorId == approverId) {
            log.warn(
                "User with ID '{}' attempted to approve their own review with ID '{}'.",
                approverId,
                reviewId
            )
            throw ValidationException(
                "User with ID '$approverId' cannot approve their own review with ID '$reviewId'."
            )
        }

        // increment approval count on the freshly fetched review
        val approvedReview = reviewToApprove.copy(approvalCount = reviewToApprove.approvalCount + 1)

        // update approval status to determine if the review now reaches the approval quorum
        val finalReview = updateApprovalStatus(approvedReview)
        if (finalReview.approved) {
            log.info(
                "Review with ID '{}' has now reached the approval quorum ({}/{})",
                finalReview.id,
                finalReview.approvalCount,
                approvalConfiguration.minCount
            )
        } else {
            log.info(
                "Review with ID '{}' has not reached the approval quorum ({}/{})",
                finalReview.id,
                finalReview.approvalCount,
                approvalConfiguration.minCount
            )
        }

        return reviewDataService.upsert(finalReview)
    }

    /**
     * Calculates and updates the approval status of a review based on its approval count.
     * Business rule: a review is approved when it reaches the configured minimum approval count.
     */
    fun updateApprovalStatus(review: Review): Review {
        log.debug("Updating approval status of review with ID '{}'...", review.id)
        return review.copy(approved = isApproved(review))
    }

    /**
     * Determines whether a review meets the minimum approval threshold.
     */
    private fun isApproved(review: Review): Boolean = review.approvalCount >= approvalConfiguration.minCount

    private companion object {
        private val log = LoggerFactory.getLogger(ReviewServiceImpl::class.java)
    }
}
