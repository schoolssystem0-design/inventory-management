-- Rebrand platform settings seeded as SelectCode (existing deployments).
UPDATE platform_setting SET value = 'Tirvex Tech' WHERE setting_key = 'company_name' AND value = 'SelectCode';
UPDATE platform_setting SET value = 'Tirvex Tech POS' WHERE setting_key = 'product_name' AND value = 'SelectCode POS';