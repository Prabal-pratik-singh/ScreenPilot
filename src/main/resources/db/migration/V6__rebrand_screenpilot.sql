-- Rebrand: Apnamart demo identity -> ScreenPilot (personal project).
-- Updates data seeded by earlier builds so existing databases match the new
-- documented credentials and naming. Fresh databases seed with the new
-- branding directly and these statements simply match nothing.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Portal accounts: new emails + admin password (ScreenPilot@123)
UPDATE users
SET email         = 'admin@screenpilot.in',
    full_name     = 'ScreenPilot Admin',
    password_hash = crypt('ScreenPilot@123', gen_salt('bf', 10))
WHERE email = 'admin@apnamart.in';

UPDATE users SET email = 'content.ranchi@screenpilot.in' WHERE email = 'content.ranchi@apnamart.in';
UPDATE users SET email = 'viewer@screenpilot.in' WHERE email = 'viewer@apnamart.in';

-- Demo store names: drop the old brand prefix ("Apnamart Lalpur" -> "Lalpur")
UPDATE screens
SET store_name = SUBSTRING(store_name FROM 10)
WHERE store_name LIKE 'Apnamart %';

UPDATE screen_groups
SET description = REPLACE(description, 'Apnamart stores', 'Stores')
WHERE description LIKE '%Apnamart%';

-- Ticker presets inside layout zones
UPDATE layout_zones
SET config = REPLACE(config, 'Welcome to Apnamart', 'Welcome')
WHERE config LIKE '%Apnamart%';
