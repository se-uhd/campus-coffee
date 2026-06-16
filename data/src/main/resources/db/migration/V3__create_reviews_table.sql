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
