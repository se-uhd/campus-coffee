SET TIME ZONE 'UTC';

CREATE SEQUENCE reviews_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE reviews (
    id bigint NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    pos_id bigint REFERENCES pos(id),
    author_id bigint REFERENCES users(id),
    review text NOT NULL CHECK (length(review) > 0),
    approval_count int NOT NULL CHECK (approval_count >= 0),
    approved boolean NOT NULL
);

CREATE SEQUENCE review_approvals_seq START WITH 1 INCREMENT BY 1;

-- Records who approved a review (its own surrogate id mirrors the other tables). The named unique
-- constraint on (review_id, user_id) is the authoritative "one approval per user per review" guard; the
-- application maps a violation to a 409.
CREATE TABLE review_approvals (
    id bigint NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    review_id bigint NOT NULL,
    user_id bigint NOT NULL,
    CONSTRAINT uq_review_approvals_review_user UNIQUE (review_id, user_id),
    CONSTRAINT fk_review_approvals_review FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_approvals_user FOREIGN KEY (user_id) REFERENCES users(id)
);
