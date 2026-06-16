SET TIME ZONE 'UTC';

CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE users (
    id bigint NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    login_name varchar(255) NOT NULL CHECK (login_name <> ''),
    email_address varchar(254) NOT NULL CHECK (length(email_address) > 2), -- https://stackoverflow.com/a/574698/1974143, https://stackoverflow.com/a/1423203/1974143
    first_name varchar(255) NOT NULL CHECK (first_name <> ''),
    last_name varchar(255) NOT NULL CHECK (last_name <> ''),
    -- BCrypt via the delegating encoder stores a prefixed hash ("{bcrypt}$2a$10$...", ~68 chars), so the
    -- column is text. It is nullable because a User can be constructed before a hash is set; the API
    -- requires a password through the DTO instead.
    password_hash text,
    -- explicitly named so the application can map a violation to the offending user field
    CONSTRAINT uq_users_login_name UNIQUE (login_name),
    CONSTRAINT uq_users_email_address UNIQUE (email_address)
);

-- The set of roles a user holds, backing the @ElementCollection of the Role enum on UserEntity. The
-- CHECK keeps the stored strings in step with the enum; roles are cumulative by convention
-- (USER < MODERATOR < ADMIN).
CREATE TABLE user_roles (
    user_id bigint NOT NULL,
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'MODERATOR', 'ADMIN')),
    PRIMARY KEY (user_id, role),
    -- named so deleting a user cascades to their roles
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
