-- =====================================================================
-- V006: Fast food / restaurant shop type (Stage 4c)
--   menu items made from recipes (module recipes), kitchen tickets and display (module kitchen),
--   order type, table and token on sales, notes on lines
-- =====================================================================

-- ---------------------------------------------------------------- menu items
-- RECIPE: a menu item made to order. Its ingredients are its bundle_item rows (qty per serving, in each
-- ingredient's base unit); selling it takes the ingredients out of stock. It never has stock itself.
ALTER TABLE product DROP CONSTRAINT product_kind_check;
ALTER TABLE product ADD CONSTRAINT product_kind_check
    CHECK (kind IN ('STANDARD', 'SERIAL', 'BATCH', 'VARIANT_PARENT', 'BUNDLE', 'SERVICE', 'RECIPE'));

-- ---------------------------------------------------------------- orders
ALTER TABLE sale ADD COLUMN order_type VARCHAR(10) CHECK (order_type IN ('DINE_IN', 'TAKEAWAY', 'DELIVERY'));
ALTER TABLE sale ADD COLUMN table_no VARCHAR(20);
-- Token called out to the customer: 1, 2, 3 … per branch per day.
ALTER TABLE sale ADD COLUMN token_no INT CHECK (token_no > 0);
ALTER TABLE sale_line ADD COLUMN note VARCHAR(200);

-- ---------------------------------------------------------------- kitchen
CREATE TABLE kitchen_ticket (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT        NOT NULL REFERENCES shop (id),
    branch_id      BIGINT        NOT NULL,
    sale_id        BIGINT        NOT NULL,
    token_no       INT           NOT NULL,
    order_type     VARCHAR(10)   NOT NULL,
    table_no       VARCHAR(20),
    status         VARCHAR(10)   NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'PREPARING', 'READY', 'SERVED',
                                 'CANCELLED')),
    business_date  DATE          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    ready_at       TIMESTAMPTZ,
    served_at      TIMESTAMPTZ,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT kitchen_ticket_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT kitchen_ticket_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT kitchen_ticket_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id)
);
CREATE UNIQUE INDEX kitchen_ticket_sale_uq ON kitchen_ticket (shop_id, sale_id);
CREATE INDEX kitchen_ticket_open_idx ON kitchen_ticket (shop_id, branch_id, status, id)
    WHERE status IN ('NEW', 'PREPARING', 'READY');

-- ---------------------------------------------------------------- shop type
INSERT INTO shop_type (type_key, name, description, icon, sort_order, default_modules, offered_modules, template) VALUES
('fast_food', 'Fast food / restaurant', 'Burger, pizza, shawarma and food points: menu from recipes, deals, kitchen screen, tokens, delivery', 'utensils', 11,
 '{recipes,kitchen,bundles,promotions,multi_till,delivery}',
 '{loyalty,sms,weighed,pack_units,batches,quotations}',
 '{"categories":["Burgers","Pizza","Shawarma & wraps","Fries & sides","Drinks","Deals","Desserts","Ingredients"],
   "units":[{"name":"piece","decimals":false},{"name":"plate","decimals":false},{"name":"kg","decimals":true},
            {"name":"gram","decimals":true},{"name":"litre","decimals":true},{"name":"ml","decimals":true},
            {"name":"pack","decimals":false}]}')
ON CONFLICT (type_key) DO NOTHING;

-- ---------------------------------------------------------------- permissions for existing owner roles
UPDATE role SET permissions = permissions || ARRAY['kitchen.view']
WHERE system_key = 'OWNER' AND NOT ('kitchen.view' = ANY (permissions));

-- ---------------------------------------------------------------- RLS and grants
ALTER TABLE kitchen_ticket ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kitchen_ticket USING (shop_id = app_current_shop() OR app_all_shops())
    WITH CHECK (shop_id = app_current_shop() OR app_all_shops());
GRANT SELECT, INSERT, UPDATE ON kitchen_ticket TO pos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pos_app;
