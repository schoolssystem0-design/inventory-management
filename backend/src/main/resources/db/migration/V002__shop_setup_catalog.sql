-- =====================================================================
-- V002: shop settings, branches, tills, numbering, payment methods, files, catalogue
-- docs/DATA-MODEL.md §3, §5. Every table: shop_id first, RLS, indexes start with shop_id.
-- =====================================================================

CREATE TABLE shop_settings (
    shop_id               BIGINT         PRIMARY KEY REFERENCES shop (id),
    business_name         VARCHAR(200)   NOT NULL,
    urdu_name             VARCHAR(200),
    logo_file_id          BIGINT,
    address               VARCHAR(500),
    phones                VARCHAR(200),
    email                 VARCHAR(254),
    ntn                   VARCHAR(30),
    strn                  VARCHAR(30),
    tax_mode              VARCHAR(20)    NOT NULL DEFAULT 'NONE' CHECK (tax_mode IN ('NONE', 'INCLUSIVE', 'EXCLUSIVE')),
    tax_rate              NUMERIC(7, 4)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 100),
    allow_negative_stock  BOOLEAN        NOT NULL DEFAULT FALSE,
    price_rounding        VARCHAR(10)    NOT NULL DEFAULT 'NONE' CHECK (price_rounding IN ('NONE', 'ONE', 'FIVE', 'TEN')),
    receipt_size          VARCHAR(10)    NOT NULL DEFAULT 'MM80' CHECK (receipt_size IN ('A4', 'MM80', 'MM58')),
    receipt_language      VARCHAR(10)    NOT NULL DEFAULT 'EN' CHECK (receipt_language IN ('EN', 'UR', 'BOTH')),
    receipt_header        VARCHAR(500),
    receipt_footer        VARCHAR(500),
    receipt_show_barcode  BOOLEAN        NOT NULL DEFAULT TRUE,
    receipt_show_tax      BOOLEAN        NOT NULL DEFAULT TRUE,
    receipt_show_savings  BOOLEAN        NOT NULL DEFAULT TRUE,
    return_policy         VARCHAR(500),
    return_days           INT            NOT NULL DEFAULT 7 CHECK (return_days >= 0),
    closing_time          TIME           NOT NULL DEFAULT '22:00',
    discount_limit_pct    NUMERIC(7, 4)  NOT NULL DEFAULT 10 CHECK (discount_limit_pct >= 0 AND discount_limit_pct <= 100),
    writeoff_limit        NUMERIC(14, 2) NOT NULL DEFAULT 5000,
    session_idle_minutes  INT            NOT NULL DEFAULT 10 CHECK (session_idle_minutes BETWEEN 2 AND 480),
    owner_totp_required   BOOLEAN        NOT NULL DEFAULT FALSE,
    mask_customer_pii     BOOLEAN        NOT NULL DEFAULT TRUE,
    support_code_hash     VARCHAR(64),
    support_code_expires  TIMESTAMPTZ,
    terms_version         VARCHAR(20),
    terms_accepted_at     TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version               BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE settings_change (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT       NOT NULL REFERENCES shop (id),
    setting_key  VARCHAR(60)  NOT NULL,
    old_value    VARCHAR(500),
    new_value    VARCHAR(500),
    actor_type   VARCHAR(20)  NOT NULL,
    actor_id     BIGINT,
    actor_name   VARCHAR(200),
    at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX settings_change_shop_at_idx ON settings_change (shop_id, at DESC);

CREATE TABLE branch (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(10)  NOT NULL,
    kind        VARCHAR(10)  NOT NULL DEFAULT 'SHOP' CHECK (kind IN ('SHOP', 'GODOWN')),
    address     VARCHAR(500),
    phone       VARCHAR(30),
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT branch_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX branch_name_uq ON branch (shop_id, lower(name));
CREATE UNIQUE INDEX branch_code_uq ON branch (shop_id, upper(code));
CREATE UNIQUE INDEX branch_one_default_uq ON branch (shop_id) WHERE is_default;

CREATE TABLE user_branch (
    shop_id    BIGINT NOT NULL REFERENCES shop (id),
    user_id    BIGINT NOT NULL,
    branch_id  BIGINT NOT NULL,
    PRIMARY KEY (shop_id, user_id, branch_id),
    CONSTRAINT user_branch_user_fk FOREIGN KEY (shop_id, user_id) REFERENCES app_user (shop_id, id),
    CONSTRAINT user_branch_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);

CREATE TABLE till (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    branch_id   BIGINT       NOT NULL,
    name        VARCHAR(60)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT till_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX till_name_uq ON till (shop_id, branch_id, lower(name));

CREATE TABLE numbering (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT      NOT NULL REFERENCES shop (id),
    branch_id   BIGINT,
    doc_type    VARCHAR(30) NOT NULL,
    prefix      VARCHAR(12) NOT NULL,
    next_no     BIGINT      NOT NULL DEFAULT 1 CHECK (next_no >= 1),
    digits      INT         NOT NULL DEFAULT 6 CHECK (digits BETWEEN 3 AND 10),
    CONSTRAINT numbering_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX numbering_uq ON numbering (shop_id, coalesce(branch_id, 0), doc_type);

CREATE TABLE payment_method (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT      NOT NULL REFERENCES shop (id),
    method_key   VARCHAR(20) NOT NULL CHECK (method_key IN
                 ('CASH', 'CARD', 'BANK', 'JAZZCASH', 'EASYPAISA', 'CHEQUE', 'ACCOUNT', 'VOUCHER')),
    label        VARCHAR(60) NOT NULL,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order   INT         NOT NULL DEFAULT 0,
    version      BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX payment_method_key_uq ON payment_method (shop_id, method_key);

CREATE TABLE stored_file (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT       NOT NULL REFERENCES shop (id),
    storage_key      VARCHAR(300) NOT NULL,
    purpose          VARCHAR(40)  NOT NULL,
    original_name    VARCHAR(255) NOT NULL,
    content_type     VARCHAR(100) NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    sha256           VARCHAR(64)  NOT NULL,
    created_by_type  VARCHAR(20)  NOT NULL,
    created_by_id    BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT stored_file_shop_id_uq UNIQUE (shop_id, id)
);

-- =====================================================================
-- Catalogue
-- =====================================================================
CREATE TABLE category (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    parent_id   BIGINT,
    name        VARCHAR(100) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT category_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT category_parent_fk FOREIGN KEY (shop_id, parent_id) REFERENCES category (shop_id, id)
);
CREATE UNIQUE INDEX category_name_uq ON category (shop_id, coalesce(parent_id, 0), lower(name));

CREATE TABLE brand (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    name        VARCHAR(100) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT brand_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX brand_name_uq ON brand (shop_id, lower(name));

CREATE TABLE unit (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id           BIGINT      NOT NULL REFERENCES shop (id),
    name              VARCHAR(30) NOT NULL,
    decimals_allowed  BOOLEAN     NOT NULL DEFAULT FALSE,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT unit_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX unit_name_uq ON unit (shop_id, lower(name));

CREATE TABLE product (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    sku              VARCHAR(40)    NOT NULL,
    name             VARCHAR(200)   NOT NULL,
    urdu_name        VARCHAR(200),
    category_id      BIGINT,
    brand_id         BIGINT,
    unit_id          BIGINT         NOT NULL,
    kind             VARCHAR(20)    NOT NULL DEFAULT 'STANDARD'
                     CHECK (kind IN ('STANDARD', 'SERIAL', 'BATCH', 'VARIANT_PARENT', 'BUNDLE', 'SERVICE')),
    cost_avg         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (cost_avg >= 0),
    price            NUMERIC(14, 2) NOT NULL CHECK (price >= 0),
    wholesale_price  NUMERIC(14, 2) CHECK (wholesale_price >= 0),
    min_price        NUMERIC(14, 2) CHECK (min_price >= 0),
    tax_rate         NUMERIC(7, 4)  CHECK (tax_rate >= 0 AND tax_rate <= 100),
    reorder_level    NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    reorder_qty      NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK (reorder_qty >= 0),
    image_file_id    BIGINT,
    weighed          BOOLEAN        NOT NULL DEFAULT FALSE,
    description      VARCHAR(1000),
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT product_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT product_category_fk FOREIGN KEY (shop_id, category_id) REFERENCES category (shop_id, id),
    CONSTRAINT product_brand_fk FOREIGN KEY (shop_id, brand_id) REFERENCES brand (shop_id, id),
    CONSTRAINT product_unit_fk FOREIGN KEY (shop_id, unit_id) REFERENCES unit (shop_id, id),
    CONSTRAINT product_image_fk FOREIGN KEY (shop_id, image_file_id) REFERENCES stored_file (shop_id, id)
);
CREATE UNIQUE INDEX product_sku_uq ON product (shop_id, lower(sku));
CREATE INDEX product_name_idx ON product (shop_id, lower(name));
CREATE INDEX product_category_idx ON product (shop_id, category_id);

CREATE TABLE product_barcode (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT      NOT NULL REFERENCES shop (id),
    product_id  BIGINT      NOT NULL,
    barcode     VARCHAR(64) NOT NULL,
    CONSTRAINT product_barcode_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX product_barcode_uq ON product_barcode (shop_id, barcode);
CREATE INDEX product_barcode_product_idx ON product_barcode (shop_id, product_id);

-- Pack units (module pack_units): buy in cartons, sell in pieces, each with its own price and barcode
CREATE TABLE product_unit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT         NOT NULL REFERENCES shop (id),
    product_id  BIGINT         NOT NULL,
    unit_id     BIGINT         NOT NULL,
    factor      NUMERIC(14, 3) NOT NULL CHECK (factor > 0),
    price       NUMERIC(14, 2) CHECK (price >= 0),
    barcode     VARCHAR(64),
    CONSTRAINT product_unit_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id) ON DELETE CASCADE,
    CONSTRAINT product_unit_unit_fk FOREIGN KEY (shop_id, unit_id) REFERENCES unit (shop_id, id)
);
CREATE UNIQUE INDEX product_unit_uq ON product_unit (shop_id, product_id, unit_id);

-- Medicine fields (module formula)
CREATE TABLE product_pharma (
    shop_id          BIGINT       NOT NULL REFERENCES shop (id),
    product_id       BIGINT       NOT NULL,
    formula          VARCHAR(200),
    strength         VARCHAR(60),
    dosage_form      VARCHAR(40),
    company          VARCHAR(120),
    pack_size        VARCHAR(40),
    schedule         VARCHAR(12)  NOT NULL DEFAULT 'OTC' CHECK (schedule IN ('OTC', 'RX', 'CONTROLLED')),
    rack             VARCHAR(30),
    registration_no  VARCHAR(40),
    PRIMARY KEY (shop_id, product_id),
    CONSTRAINT product_pharma_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id) ON DELETE CASCADE
);
CREATE INDEX product_pharma_formula_idx ON product_pharma (shop_id, lower(formula));

-- Auto part numbers (module part_numbers)
CREATE TABLE product_part (
    shop_id          BIGINT       NOT NULL REFERENCES shop (id),
    product_id       BIGINT       NOT NULL,
    oem_number       VARCHAR(60),
    alternate_numbers TEXT[]      NOT NULL DEFAULT '{}',
    PRIMARY KEY (shop_id, product_id),
    CONSTRAINT product_part_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id) ON DELETE CASCADE
);
CREATE INDEX product_part_oem_idx ON product_part (shop_id, lower(oem_number));

-- Book fields (module bundles, bookshop type)
CREATE TABLE product_book (
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    product_id  BIGINT       NOT NULL,
    isbn        VARCHAR(20),
    author      VARCHAR(200),
    publisher   VARCHAR(200),
    edition     VARCHAR(40),
    class_name  VARCHAR(40),
    PRIMARY KEY (shop_id, product_id),
    CONSTRAINT product_book_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id) ON DELETE CASCADE
);

CREATE TABLE price_change (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT         NOT NULL REFERENCES shop (id),
    product_id  BIGINT         NOT NULL,
    old_price   NUMERIC(14, 2),
    new_price   NUMERIC(14, 2),
    old_cost    NUMERIC(14, 2),
    new_cost    NUMERIC(14, 2),
    reason      VARCHAR(200),
    actor_type  VARCHAR(20)    NOT NULL,
    actor_id    BIGINT,
    actor_name  VARCHAR(200),
    at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT price_change_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE INDEX price_change_product_idx ON price_change (shop_id, product_id, at DESC);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['shop_settings', 'settings_change', 'branch', 'user_branch', 'till', 'numbering',
                             'payment_method', 'stored_file', 'category', 'brand', 'unit', 'product',
                             'product_barcode', 'product_unit', 'product_pharma', 'product_part', 'product_book',
                             'price_change']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (shop_id = app_current_shop() OR app_all_shops())'
                       ' WITH CHECK (shop_id = app_current_shop() OR app_all_shops())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON shop_settings, branch, till, numbering, payment_method, category, brand, unit, product,
    product_unit, product_pharma, product_part, product_book TO pos_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_branch, product_barcode, product_unit TO pos_app;
GRANT SELECT, INSERT ON settings_change, stored_file, price_change TO pos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pos_app;
