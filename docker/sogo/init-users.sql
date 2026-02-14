-- SOGo SQL authentication users table
CREATE TABLE IF NOT EXISTS sogo_users (
    c_uid VARCHAR(128) PRIMARY KEY,
    c_cn VARCHAR(128),
    c_password VARCHAR(256),
    mail VARCHAR(256)
);

-- Test users for CalDAV integration testing
INSERT INTO sogo_users (c_uid, c_cn, c_password, mail) VALUES
    ('testuser1', 'Test User 1', 'testpass1', 'testuser1@test.local'),
    ('testuser2', 'Test User 2', 'testpass2', 'testuser2@test.local')
ON CONFLICT (c_uid) DO NOTHING;