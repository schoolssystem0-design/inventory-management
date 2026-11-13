-- =====================================================================
-- V005: Phase 2 shop-type modules (docs/IMPLEMENTATION-STAGES.md, Stage 4b)
--   scale barcodes (SUP-03), offers and coupons (SUP-05, XSL-02), loyalty (CUS-06), repairs (MOB-07),
--   installments (MOB-08, ELE-03), prescriptions and the controlled-medicine register (MED-08, MED-09),
--   delivery and challans (ELE-04, HRD-04)
-- =====================================================================

-- ---------------------------------------------------------------- settings
ALTER TABLE shop_settings ADD COLUMN scale_value_kind VARCHAR(6) NOT NULL DEFAULT 'WEIGHT'
    CHECK (scale_value_kind IN ('WEIGHT', 'PRICE'));
ALTER TABLE shop_settings ADD COLUMN prescription_required BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE shop_settings ADD COLUMN loyalty_spend_per_point NUMERIC(14, 2) NOT NULL DEFAULT 100
    CHECK (loyalty_spend_per_point > 0);
ALTER TABLE shop_settings ADD COLUMN loyalty_point_value NUMERIC(14, 2) NOT NULL DEFAULT 1 CHECK (loyalty_point_value > 0);
ALTER TABLE shop_settings ADD COLUMN loyalty_min_redeem INT NOT NULL DEFAULT 100 CHECK (loyalty_min_redeem >= 0);
ALTER TABLE shop_settings ADD COLUMN loyalty_expiry_months INT NOT NULL DEFAULT 12
    CHECK (loyalty_expiry_months BETWEEN 0 AND 120);

-- ---------------------------------------------------------------- scale barcodes
-- The item code printed by the weighing scale (5 digits after the 2x prefix).
ALTER TABLE product ADD COLUMN scale_code VARCHAR(5) CHECK (scale_code ~ '^[0-9]{1,5}$');
CREATE UNIQUE INDEX product_scale_code_uq ON product (shop_id, scale_code) WHERE scale_code IS NOT NULL;

-- ---------------------------------------------------------------- offers and coupons
CREATE TABLE promotion (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    name          VARCHAR(120)   NOT NULL,
    kind          VARCHAR(12)    NOT NULL CHECK (kind IN ('PERCENT', 'AMOUNT', 'BUY_GET', 'MULTI_PRICE', 'BILL_PERCENT',
                                                          'BILL_AMOUNT')),
    value         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (value >= 0),
    buy_qty       NUMERIC(14, 3) CHECK (buy_qty > 0),
    get_qty       NUMERIC(14, 3) CHECK (get_qty > 0),
    min_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (min_amount >= 0),
    scope         VARCHAR(10)    NOT NULL DEFAULT 'ALL' CHECK (scope IN ('ALL', 'CATEGORY', 'PRODUCTS')),
    category_ids  BIGINT[]       NOT NULL DEFAULT '{}',
    product_ids   BIGINT[]       NOT NULL DEFAULT '{}',
    starts_on     DATE,
    ends_on       DATE,
    days          SMALLINT[]     NOT NULL DEFAULT '{}',
    from_time     TIME,
    to_time       TIME,
    coupon_code   VARCHAR(30),
    max_uses      INT            CHECK (max_uses > 0),
    uses          INT            NOT NULL DEFAULT 0 CHECK (uses >= 0),
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT promotion_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT promotion_dates_ck CHECK (starts_on IS NULL OR ends_on IS NULL OR ends_on >= starts_on)
);
CREATE UNIQUE INDEX promotion_coupon_uq ON promotion (shop_id, upper(coupon_code)) WHERE coupon_code IS NOT NULL;

-- Offer amounts are kept inside the line and bill discounts (so every total and report stays the same);
-- these columns say how much of each discount came from an offer.
ALTER TABLE sale_line ADD COLUMN promotion_id BIGINT;
ALTER TABLE sale_line ADD COLUMN promo_discount NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (promo_discount >= 0);
ALTER TABLE sale ADD COLUMN promo_discount NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (promo_discount >= 0);
ALTER TABLE sale ADD COLUMN bill_promotion_id BIGINT;
ALTER TABLE sale ADD COLUMN coupon_code VARCHAR(30);

CREATE TABLE promotion_use (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    promotion_id  BIGINT         NOT NULL,
    sale_id       BIGINT         NOT NULL,
    customer_id   BIGINT,
    amount        NUMERIC(14, 2) NOT NULL,
    business_date DATE           NOT NULL,
    at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT promotion_use_promotion_fk FOREIGN KEY (shop_id, promotion_id) REFERENCES promotion (shop_id, id),
    CONSTRAINT promotion_use_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id)
);
CREATE INDEX promotion_use_promotion_idx ON promotion_use (shop_id, promotion_id, business_date);

-- ---------------------------------------------------------------- loyalty
ALTER TABLE customer ADD COLUMN loyalty_points INT NOT NULL DEFAULT 0 CHECK (loyalty_points >= 0);
ALTER TABLE customer ADD COLUMN loyalty_last_at DATE;

CREATE TABLE loyalty_txn (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT        NOT NULL REFERENCES shop (id),
    customer_id    BIGINT        NOT NULL,
    kind           VARCHAR(12)   NOT NULL CHECK (kind IN ('EARN', 'REDEEM', 'TAKE_BACK', 'GIVE_BACK', 'EXPIRE', 'ADJUST')),
    points         INT           NOT NULL CHECK (points <> 0),
    balance_after  INT           NOT NULL CHECK (balance_after >= 0),
    sale_id        BIGINT,
    return_id      BIGINT,
    note           VARCHAR(200),
    actor_name     VARCHAR(200),
    business_date  DATE          NOT NULL,
    at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT loyalty_txn_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id)
);
CREATE INDEX loyalty_txn_customer_idx ON loyalty_txn (shop_id, customer_id, id);
CREATE INDEX loyalty_txn_sale_idx ON loyalty_txn (shop_id, sale_id) WHERE sale_id IS NOT NULL;

-- ---------------------------------------------------------------- repairs
CREATE TABLE repair_job (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id         BIGINT         NOT NULL REFERENCES shop (id),
    number          VARCHAR(40)    NOT NULL,
    branch_id       BIGINT         NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(200)   NOT NULL,
    customer_phone  VARCHAR(30),
    device          VARCHAR(200)   NOT NULL,
    serial          VARCHAR(60),
    problem         VARCHAR(1000)  NOT NULL,
    accessories     VARCHAR(300),
    condition_note  VARCHAR(300),
    diagnosis       VARCHAR(1000),
    estimate        NUMERIC(14, 2) CHECK (estimate >= 0),
    advance         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (advance >= 0),
    advance_method  VARCHAR(20),
    advance_session_id BIGINT,
    labour          NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (labour >= 0),
    technician_id   BIGINT,
    promised_on     DATE,
    status          VARCHAR(14)    NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'CHECKING', 'WAITING_PARTS',
                                   'REPAIRING', 'READY', 'DELIVERED', 'CANCELLED', 'NOT_REPAIRABLE')),
    sale_id         BIGINT,
    ready_sms_sent  BOOLEAN        NOT NULL DEFAULT FALSE,
    created_by      BIGINT,
    actor_name      VARCHAR(200),
    business_date   DATE           NOT NULL,
    at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    ready_at        TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT repair_job_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT repair_job_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT repair_job_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id),
    CONSTRAINT repair_job_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id)
);
CREATE UNIQUE INDEX repair_job_number_uq ON repair_job (shop_id, number);
CREATE INDEX repair_job_status_idx ON repair_job (shop_id, status, id);
CREATE INDEX repair_job_serial_idx ON repair_job (shop_id, upper(serial)) WHERE serial IS NOT NULL;

CREATE TABLE repair_part (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT         NOT NULL REFERENCES shop (id),
    job_id      BIGINT         NOT NULL,
    product_id  BIGINT         NOT NULL,
    qty         NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    price       NUMERIC(14, 2) NOT NULL CHECK (price >= 0),
    at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT repair_part_job_fk FOREIGN KEY (shop_id, job_id) REFERENCES repair_job (shop_id, id),
    CONSTRAINT repair_part_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE INDEX repair_part_job_idx ON repair_part (shop_id, job_id);

CREATE TABLE repair_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT        NOT NULL REFERENCES shop (id),
    job_id      BIGINT        NOT NULL,
    status      VARCHAR(14)   NOT NULL,
    note        VARCHAR(500),
    actor_name  VARCHAR(200),
    at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT repair_event_job_fk FOREIGN KEY (shop_id, job_id) REFERENCES repair_job (shop_id, id)
);
CREATE INDEX repair_event_job_idx ON repair_event (shop_id, job_id, id);

-- ---------------------------------------------------------------- installments
CREATE TABLE installment_plan (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id            BIGINT         NOT NULL REFERENCES shop (id),
    number             VARCHAR(40)    NOT NULL,
    sale_id            BIGINT         NOT NULL,
    customer_id        BIGINT         NOT NULL,
    principal          NUMERIC(14, 2) NOT NULL CHECK (principal > 0),
    markup_pct         NUMERIC(7, 4)  NOT NULL DEFAULT 0 CHECK (markup_pct >= 0 AND markup_pct <= 100),
    markup             NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (markup >= 0),
    total              NUMERIC(14, 2) NOT NULL CHECK (total > 0),
    months             INT            NOT NULL CHECK (months BETWEEN 1 AND 60),
    first_due          DATE           NOT NULL,
    grace_days         INT            NOT NULL DEFAULT 0 CHECK (grace_days BETWEEN 0 AND 60),
    late_fee_per_day   NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (late_fee_per_day >= 0),
    status             VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    guarantor_name     VARCHAR(200)   NOT NULL,
    guarantor_cnic     VARCHAR(20)    NOT NULL,
    guarantor_phone    VARCHAR(30)    NOT NULL,
    guarantor_address  VARCHAR(500),
    notes              VARCHAR(500),
    created_by         BIGINT,
    actor_name         VARCHAR(200),
    business_date      DATE           NOT NULL,
    at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT installment_plan_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT installment_plan_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id),
    CONSTRAINT installment_plan_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id)
);
CREATE UNIQUE INDEX installment_plan_number_uq ON installment_plan (shop_id, number);
CREATE UNIQUE INDEX installment_plan_sale_uq ON installment_plan (shop_id, sale_id) WHERE status <> 'CANCELLED';

CREATE TABLE installment_due (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id   BIGINT         NOT NULL REFERENCES shop (id),
    plan_id   BIGINT         NOT NULL,
    no        INT            NOT NULL CHECK (no >= 1),
    due_on    DATE           NOT NULL,
    amount    NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    paid      NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (paid >= 0),
    late_fee  NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (late_fee >= 0),
    paid_on   DATE,
    CONSTRAINT installment_due_plan_fk FOREIGN KEY (shop_id, plan_id) REFERENCES installment_plan (shop_id, id),
    CONSTRAINT installment_due_paid_le CHECK (paid <= amount + late_fee)
);
CREATE UNIQUE INDEX installment_due_no_uq ON installment_due (shop_id, plan_id, no);
CREATE INDEX installment_due_date_idx ON installment_due (shop_id, due_on) WHERE paid < amount + late_fee;

CREATE TABLE installment_receipt (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id              BIGINT         NOT NULL REFERENCES shop (id),
    plan_id              BIGINT         NOT NULL,
    customer_payment_id  BIGINT         NOT NULL,
    amount               NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    late_fee             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    at                   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT installment_receipt_plan_fk FOREIGN KEY (shop_id, plan_id) REFERENCES installment_plan (shop_id, id)
);
CREATE INDEX installment_receipt_plan_idx ON installment_receipt (shop_id, plan_id);

-- ---------------------------------------------------------------- prescriptions and controlled medicines
CREATE TABLE prescription (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT        NOT NULL REFERENCES shop (id),
    number         VARCHAR(40)   NOT NULL,
    sale_id        BIGINT,
    patient_name   VARCHAR(200)  NOT NULL,
    patient_age    VARCHAR(20),
    patient_phone  VARCHAR(30),
    doctor_name    VARCHAR(200)  NOT NULL,
    doctor_reg_no  VARCHAR(40),
    issued_on      DATE,
    file_id        BIGINT,
    notes          VARCHAR(500),
    created_by     BIGINT,
    actor_name     VARCHAR(200),
    business_date  DATE          NOT NULL,
    at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT prescription_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT prescription_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id),
    CONSTRAINT prescription_file_fk FOREIGN KEY (shop_id, file_id) REFERENCES stored_file (shop_id, id)
);
CREATE UNIQUE INDEX prescription_number_uq ON prescription (shop_id, number);
CREATE INDEX prescription_sale_idx ON prescription (shop_id, sale_id);
ALTER TABLE sale ADD COLUMN prescription_id BIGINT;

-- One row per controlled medicine sold (positive) or returned (negative). Never changed.
CREATE TABLE controlled_register (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    branch_id        BIGINT         NOT NULL,
    sale_id          BIGINT         NOT NULL,
    sale_number      VARCHAR(40)    NOT NULL,
    return_id        BIGINT,
    product_id       BIGINT         NOT NULL,
    product_name     VARCHAR(200)   NOT NULL,
    batch_no         VARCHAR(40),
    qty              NUMERIC(14, 3) NOT NULL CHECK (qty <> 0),
    unit_name        VARCHAR(30),
    patient_name     VARCHAR(200)   NOT NULL,
    doctor_name      VARCHAR(200)   NOT NULL,
    prescription_id  BIGINT,
    pharmacist_id    BIGINT,
    pharmacist_name  VARCHAR(200),
    business_date    DATE           NOT NULL,
    at               TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX controlled_register_date_idx ON controlled_register (shop_id, business_date, id);
CREATE INDEX controlled_register_product_idx ON controlled_register (shop_id, product_id, id);

-- ---------------------------------------------------------------- delivery and challans
CREATE TABLE challan (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id         BIGINT        NOT NULL REFERENCES shop (id),
    number          VARCHAR(40)   NOT NULL,
    branch_id       BIGINT        NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(200)  NOT NULL,
    customer_phone  VARCHAR(30),
    address         VARCHAR(500),
    status          VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'INVOICED', 'CANCELLED')),
    sale_id         BIGINT,
    note            VARCHAR(500),
    created_by      BIGINT,
    actor_name      VARCHAR(200),
    business_date   DATE          NOT NULL,
    at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT challan_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT challan_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT challan_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id),
    CONSTRAINT challan_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id)
);
CREATE UNIQUE INDEX challan_number_uq ON challan (shop_id, number);
CREATE INDEX challan_status_idx ON challan (shop_id, status, id);

CREATE TABLE challan_line (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    challan_id    BIGINT         NOT NULL,
    line_no       INT            NOT NULL,
    product_id    BIGINT         NOT NULL,
    product_name  VARCHAR(200)   NOT NULL,
    unit_id       BIGINT,
    unit_name     VARCHAR(30),
    unit_factor   NUMERIC(14, 3) NOT NULL DEFAULT 1,
    qty           NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    base_qty      NUMERIC(14, 3) NOT NULL,
    price         NUMERIC(14, 2) NOT NULL CHECK (price >= 0),
    CONSTRAINT challan_line_doc_fk FOREIGN KEY (shop_id, challan_id) REFERENCES challan (shop_id, id),
    CONSTRAINT challan_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE INDEX challan_line_doc_idx ON challan_line (shop_id, challan_id);

CREATE TABLE delivery (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id         BIGINT        NOT NULL REFERENCES shop (id),
    number          VARCHAR(40)   NOT NULL,
    branch_id       BIGINT        NOT NULL,
    sale_id         BIGINT,
    challan_id      BIGINT,
    customer_name   VARCHAR(200)  NOT NULL,
    customer_phone  VARCHAR(30),
    address         VARCHAR(500)  NOT NULL,
    scheduled_on    DATE,
    driver          VARCHAR(120),
    vehicle         VARCHAR(40),
    installation    BOOLEAN       NOT NULL DEFAULT FALSE,
    status          VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ON_THE_WAY', 'DELIVERED', 'INSTALLED',
                                  'FAILED', 'CANCELLED')),
    note            VARCHAR(500),
    received_by     VARCHAR(120),
    created_by      BIGINT,
    actor_name      VARCHAR(200),
    business_date   DATE          NOT NULL,
    at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    done_at         TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT delivery_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT delivery_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT delivery_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id),
    CONSTRAINT delivery_challan_fk FOREIGN KEY (shop_id, challan_id) REFERENCES challan (shop_id, id),
    CONSTRAINT delivery_doc_ck CHECK (sale_id IS NOT NULL OR challan_id IS NOT NULL)
);
CREATE UNIQUE INDEX delivery_number_uq ON delivery (shop_id, number);
CREATE INDEX delivery_status_idx ON delivery (shop_id, status, scheduled_on);

-- ---------------------------------------------------------------- stock movement types for challans
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_move_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_move_type_check CHECK (move_type IN ('OPENING', 'PURCHASE',
    'SALE', 'SALE_RETURN', 'PURCHASE_RETURN', 'ADJUST_IN', 'ADJUST_OUT', 'COUNT', 'TRANSFER_OUT', 'TRANSFER_IN', 'CANCEL',
    'BUYBACK', 'CHALLAN', 'CHALLAN_BACK'));

-- ---------------------------------------------------------------- numbering
INSERT INTO numbering (shop_id, doc_type, prefix, next_no, digits)
SELECT s.id, t.doc_type, t.prefix, 1, 6
FROM shop s CROSS JOIN (VALUES ('REPAIR', 'JOB'), ('INSTALLMENT_PLAN', 'INS'), ('PRESCRIPTION', 'RX'),
                               ('CHALLAN', 'DC'), ('DELIVERY', 'DLV')) AS t(doc_type, prefix)
WHERE NOT EXISTS (SELECT 1 FROM numbering n WHERE n.shop_id = s.id AND n.branch_id IS NULL AND n.doc_type = t.doc_type);

-- ---------------------------------------------------------------- new permissions for existing owner roles
UPDATE role SET permissions = permissions || ARRAY['promotions.manage', 'loyalty.manage', 'installments.manage',
    'delivery.manage']
WHERE system_key = 'OWNER' AND NOT ('promotions.manage' = ANY (permissions));

-- ---------------------------------------------------------------- RLS and grants
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['promotion', 'promotion_use', 'loyalty_txn', 'repair_job', 'repair_part', 'repair_event',
                             'installment_plan', 'installment_due', 'installment_receipt', 'prescription',
                             'controlled_register', 'challan', 'challan_line', 'delivery']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (shop_id = app_current_shop() OR app_all_shops())'
                       ' WITH CHECK (shop_id = app_current_shop() OR app_all_shops())', t);
    END LOOP;
END
$$;

-- Insert-only history.
GRANT SELECT, INSERT ON promotion_use, loyalty_txn, repair_event, installment_receipt, controlled_register,
    challan_line TO pos_app;
GRANT SELECT, INSERT, UPDATE ON promotion, repair_job, installment_plan, installment_due, prescription, challan,
    delivery TO pos_app;
GRANT SELECT, INSERT, DELETE ON repair_part TO pos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pos_app;
