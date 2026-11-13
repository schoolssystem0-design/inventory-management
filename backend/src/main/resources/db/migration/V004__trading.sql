-- =====================================================================
-- V004: daily trading (Stage 3). Stock, customers, purchasing, sales, cash, notifications, exports, billing.
-- docs/DATA-MODEL.md §6–§10 and §12. Every shop table: shop_id first, RLS, indexes start with shop_id.
-- Money numeric(14,2), quantities numeric(14,3). Posted documents are never deleted; corrections are new documents.
-- =====================================================================

-- ---------------------------------------------------------------- catalogue additions
ALTER TABLE till ADD CONSTRAINT till_shop_id_uq UNIQUE (shop_id, id);
ALTER TABLE product ADD COLUMN warranty_days INT NOT NULL DEFAULT 0 CHECK (warranty_days >= 0);
ALTER TABLE product ADD COLUMN supplier_id BIGINT;

-- Variants (module variants): a design (kind VARIANT_PARENT) and its sellable children, one per size/colour/shade.
CREATE TABLE product_variant (
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    product_id  BIGINT       NOT NULL,
    parent_id   BIGINT       NOT NULL,
    size        VARCHAR(30),
    colour      VARCHAR(40),
    shade       VARCHAR(40),
    PRIMARY KEY (shop_id, product_id),
    CONSTRAINT product_variant_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT product_variant_parent_fk FOREIGN KEY (shop_id, parent_id) REFERENCES product (shop_id, id)
);
CREATE INDEX product_variant_parent_idx ON product_variant (shop_id, parent_id);

-- Reusable size lists (GAR-02): S-XXL, 6-11, 28-40
CREATE TABLE size_set (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    name        VARCHAR(60)  NOT NULL,
    sizes       TEXT[]       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX size_set_name_uq ON size_set (shop_id, lower(name));

-- Bundles and sets (module bundles): selling the bundle takes stock from each item.
CREATE TABLE bundle_item (
    shop_id            BIGINT         NOT NULL REFERENCES shop (id),
    bundle_product_id  BIGINT         NOT NULL,
    item_product_id    BIGINT         NOT NULL,
    qty                NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    PRIMARY KEY (shop_id, bundle_product_id, item_product_id),
    CONSTRAINT bundle_item_bundle_fk FOREIGN KEY (shop_id, bundle_product_id) REFERENCES product (shop_id, id),
    CONSTRAINT bundle_item_item_fk FOREIGN KEY (shop_id, item_product_id) REFERENCES product (shop_id, id),
    CONSTRAINT bundle_item_not_self CHECK (bundle_product_id <> item_product_id)
);

-- ---------------------------------------------------------------- stock
CREATE TABLE batch (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    product_id    BIGINT         NOT NULL,
    batch_no      VARCHAR(40)    NOT NULL,
    mfg_date      DATE,
    expiry_date   DATE,
    cost          NUMERIC(14, 2) CHECK (cost >= 0),
    trade_price   NUMERIC(14, 2) CHECK (trade_price >= 0),
    retail_price  NUMERIC(14, 2) CHECK (retail_price >= 0),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT batch_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT batch_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE UNIQUE INDEX batch_no_uq ON batch (shop_id, product_id, upper(batch_no));
CREATE INDEX batch_expiry_idx ON batch (shop_id, expiry_date);

CREATE TABLE product_stock (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT         NOT NULL REFERENCES shop (id),
    product_id  BIGINT         NOT NULL,
    branch_id   BIGINT         NOT NULL,
    batch_id    BIGINT,
    qty         NUMERIC(14, 3) NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT product_stock_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT product_stock_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT product_stock_batch_fk FOREIGN KEY (shop_id, batch_id) REFERENCES batch (shop_id, id)
);
CREATE UNIQUE INDEX product_stock_uq ON product_stock (shop_id, product_id, branch_id, coalesce(batch_id, 0));
CREATE INDEX product_stock_branch_idx ON product_stock (shop_id, branch_id);

-- One row per physical unit (IMEI / serial). D3: a serial is active (not VOID) once per shop.
CREATE TABLE serial_unit (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id         BIGINT         NOT NULL REFERENCES shop (id),
    product_id      BIGINT         NOT NULL,
    serial          VARCHAR(40)    NOT NULL,
    serial2         VARCHAR(40),
    colour          VARCHAR(40),
    storage         VARCHAR(40),
    condition       VARCHAR(12)    NOT NULL DEFAULT 'NEW' CHECK (condition IN ('NEW', 'USED', 'BOX_OPEN')),
    pta_status      VARCHAR(12)    CHECK (pta_status IN ('APPROVED', 'NON_PTA', 'PATCHED')),
    branch_id       BIGINT         NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'IN_STOCK'
                    CHECK (status IN ('IN_STOCK', 'SOLD', 'IN_TRANSIT', 'DEFECTIVE', 'RETURNED_TO_SUPPLIER', 'WRITTEN_OFF', 'VOID')),
    cost            NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (cost >= 0),
    source          VARCHAR(20)    NOT NULL CHECK (source IN ('PURCHASE', 'OPENING', 'BUYBACK', 'ADJUSTMENT')),
    supplier_id     BIGINT,
    grn_id          BIGINT,
    buyback_id      BIGINT,
    sale_id         BIGINT,
    customer_id     BIGINT,
    sold_at         TIMESTAMPTZ,
    warranty_until  DATE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT serial_unit_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT serial_unit_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT serial_unit_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX serial_unit_serial_uq ON serial_unit (shop_id, upper(serial)) WHERE status <> 'VOID';
CREATE UNIQUE INDEX serial_unit_serial2_uq ON serial_unit (shop_id, upper(serial2)) WHERE serial2 IS NOT NULL AND status <> 'VOID';
CREATE INDEX serial_unit_product_idx ON serial_unit (shop_id, product_id, status);

CREATE TABLE stock_movement (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT         NOT NULL REFERENCES shop (id),
    product_id     BIGINT         NOT NULL,
    branch_id      BIGINT         NOT NULL,
    batch_id       BIGINT,
    serial_id      BIGINT,
    move_type      VARCHAR(20)    NOT NULL CHECK (move_type IN ('OPENING', 'PURCHASE', 'SALE', 'SALE_RETURN',
                   'PURCHASE_RETURN', 'ADJUST_IN', 'ADJUST_OUT', 'COUNT', 'TRANSFER_OUT', 'TRANSFER_IN', 'CANCEL', 'BUYBACK')),
    qty            NUMERIC(14, 3) NOT NULL CHECK (qty <> 0),
    balance_after  NUMERIC(14, 3) NOT NULL,
    unit_cost      NUMERIC(14, 2),
    doc_type       VARCHAR(30)    NOT NULL,
    doc_id         BIGINT         NOT NULL,
    doc_number     VARCHAR(40),
    user_id        BIGINT,
    actor_name     VARCHAR(200),
    note           VARCHAR(300),
    at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT stock_movement_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT stock_movement_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE INDEX stock_movement_product_idx ON stock_movement (shop_id, product_id, at DESC);
CREATE INDEX stock_movement_at_idx ON stock_movement (shop_id, at DESC);
CREATE INDEX stock_movement_doc_idx ON stock_movement (shop_id, doc_type, doc_id);

CREATE TABLE adjustment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT         NOT NULL REFERENCES shop (id),
    number       VARCHAR(40)    NOT NULL,
    branch_id    BIGINT         NOT NULL,
    reason       VARCHAR(20)    NOT NULL CHECK (reason IN ('DAMAGED', 'EXPIRED', 'LOST', 'FOUND', 'OWN_USE', 'OPENING', 'OTHER')),
    note         VARCHAR(500),
    value_in     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    value_out    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    approved_by  BIGINT,
    created_by   BIGINT,
    actor_name   VARCHAR(200),
    business_date DATE          NOT NULL,
    at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT adjustment_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT adjustment_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX adjustment_number_uq ON adjustment (shop_id, number);

CREATE TABLE adjustment_line (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT         NOT NULL REFERENCES shop (id),
    adjustment_id  BIGINT         NOT NULL,
    product_id     BIGINT         NOT NULL,
    batch_id       BIGINT,
    serial_id      BIGINT,
    qty            NUMERIC(14, 3) NOT NULL CHECK (qty <> 0),
    unit_cost      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    value          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    CONSTRAINT adjustment_line_doc_fk FOREIGN KEY (shop_id, adjustment_id) REFERENCES adjustment (shop_id, id),
    CONSTRAINT adjustment_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE INDEX adjustment_line_doc_idx ON adjustment_line (shop_id, adjustment_id);

CREATE TABLE stock_count (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT        NOT NULL REFERENCES shop (id),
    number       VARCHAR(40)   NOT NULL,
    branch_id    BIGINT        NOT NULL,
    scope        VARCHAR(200)  NOT NULL DEFAULT 'All products',
    category_id  BIGINT,
    is_opening   BOOLEAN       NOT NULL DEFAULT FALSE,
    status       VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'POSTED', 'CANCELLED')),
    note         VARCHAR(500),
    started_by   BIGINT,
    started_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    posted_by    BIGINT,
    approved_by  BIGINT,
    posted_at    TIMESTAMPTZ,
    adjustment_id BIGINT,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT stock_count_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT stock_count_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX stock_count_number_uq ON stock_count (shop_id, number);

-- system_qty is the snapshot when the count started; counted_at lets movements during the count be accounted for.
CREATE TABLE stock_count_line (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT         NOT NULL REFERENCES shop (id),
    count_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    batch_id     BIGINT,
    system_qty   NUMERIC(14, 3) NOT NULL,
    counted_qty  NUMERIC(14, 3) CHECK (counted_qty >= 0),
    counted_at   TIMESTAMPTZ,
    unit_cost    NUMERIC(14, 2),
    new_batch_no VARCHAR(40),
    new_expiry   DATE,
    CONSTRAINT stock_count_line_doc_fk FOREIGN KEY (shop_id, count_id) REFERENCES stock_count (shop_id, id),
    CONSTRAINT stock_count_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE UNIQUE INDEX stock_count_line_uq ON stock_count_line (shop_id, count_id, product_id, coalesce(batch_id, 0));

CREATE TABLE transfer (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id         BIGINT        NOT NULL REFERENCES shop (id),
    number          VARCHAR(40)   NOT NULL,
    from_branch_id  BIGINT        NOT NULL,
    to_branch_id    BIGINT        NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'DISPATCHED' CHECK (status IN ('DISPATCHED', 'RECEIVED', 'CANCELLED')),
    note            VARCHAR(500),
    dispatched_by   BIGINT,
    dispatched_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    received_by     BIGINT,
    received_at     TIMESTAMPTZ,
    business_date   DATE          NOT NULL,
    CONSTRAINT transfer_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT transfer_from_fk FOREIGN KEY (shop_id, from_branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT transfer_to_fk FOREIGN KEY (shop_id, to_branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT transfer_two_branches CHECK (from_branch_id <> to_branch_id)
);
CREATE UNIQUE INDEX transfer_number_uq ON transfer (shop_id, number);

CREATE TABLE transfer_line (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    transfer_id   BIGINT         NOT NULL,
    product_id    BIGINT         NOT NULL,
    batch_id      BIGINT,
    serial_id     BIGINT,
    qty_sent      NUMERIC(14, 3) NOT NULL CHECK (qty_sent > 0),
    qty_received  NUMERIC(14, 3) CHECK (qty_received >= 0 AND qty_received <= qty_sent),
    unit_cost     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    CONSTRAINT transfer_line_doc_fk FOREIGN KEY (shop_id, transfer_id) REFERENCES transfer (shop_id, id),
    CONSTRAINT transfer_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE INDEX transfer_line_doc_idx ON transfer_line (shop_id, transfer_id);

CREATE TABLE stock_alert (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT        NOT NULL REFERENCES shop (id),
    kind         VARCHAR(12)   NOT NULL CHECK (kind IN ('LOW', 'OUT', 'NEAR_EXPIRY', 'EXPIRED', 'DEAD')),
    product_id   BIGINT        NOT NULL,
    branch_id    BIGINT,
    batch_id     BIGINT,
    message      VARCHAR(300)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    seen_at      TIMESTAMPTZ,
    resolved_at  TIMESTAMPTZ,
    CONSTRAINT stock_alert_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE UNIQUE INDEX stock_alert_open_uq ON stock_alert (shop_id, kind, product_id, coalesce(branch_id, 0), coalesce(batch_id, 0))
    WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------- customers
CREATE TABLE customer (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    code             VARCHAR(20)    NOT NULL,
    name             VARCHAR(200)   NOT NULL,
    phone            VARCHAR(30),
    cnic             VARCHAR(20),
    email            VARCHAR(254),
    address          VARCHAR(500),
    kind             VARCHAR(12)    NOT NULL DEFAULT 'RETAIL' CHECK (kind IN ('RETAIL', 'WHOLESALE', 'CONTRACTOR', 'WORKSHOP')),
    credit_limit     NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (credit_limit >= 0),
    opening_balance  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    balance          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    notes            VARCHAR(1000),
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT customer_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX customer_code_uq ON customer (shop_id, upper(code));
CREATE UNIQUE INDEX customer_phone_uq ON customer (shop_id, phone) WHERE phone IS NOT NULL;
CREATE INDEX customer_name_idx ON customer (shop_id, lower(name));

-- D5: customer.balance = sum(debit) − sum(credit); written together by CustomerLedger.
CREATE TABLE customer_ledger (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT         NOT NULL REFERENCES shop (id),
    customer_id    BIGINT         NOT NULL,
    doc_type       VARCHAR(30)    NOT NULL,
    doc_id         BIGINT,
    doc_number     VARCHAR(40),
    description    VARCHAR(300)   NOT NULL,
    debit          NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    balance_after  NUMERIC(14, 2) NOT NULL,
    business_date  DATE           NOT NULL,
    at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT customer_ledger_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id)
);
CREATE INDEX customer_ledger_customer_idx ON customer_ledger (shop_id, customer_id, id);

-- ---------------------------------------------------------------- purchasing
CREATE TABLE supplier (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    code             VARCHAR(20)    NOT NULL,
    name             VARCHAR(200)   NOT NULL,
    company          VARCHAR(200),
    phone            VARCHAR(30),
    email            VARCHAR(254),
    ntn              VARCHAR(30),
    address          VARCHAR(500),
    terms_days       INT            NOT NULL DEFAULT 0 CHECK (terms_days >= 0),
    opening_balance  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    balance          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    notes            VARCHAR(1000),
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT supplier_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX supplier_code_uq ON supplier (shop_id, upper(code));
CREATE INDEX supplier_name_idx ON supplier (shop_id, lower(name));

ALTER TABLE product ADD CONSTRAINT product_supplier_fk FOREIGN KEY (shop_id, supplier_id) REFERENCES supplier (shop_id, id);

-- supplier.balance = what the shop owes = sum(credit) − sum(debit)
CREATE TABLE supplier_ledger (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT         NOT NULL REFERENCES shop (id),
    supplier_id    BIGINT         NOT NULL,
    doc_type       VARCHAR(30)    NOT NULL,
    doc_id         BIGINT,
    doc_number     VARCHAR(40),
    description    VARCHAR(300)   NOT NULL,
    debit          NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    balance_after  NUMERIC(14, 2) NOT NULL,
    business_date  DATE           NOT NULL,
    at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT supplier_ledger_supplier_fk FOREIGN KEY (shop_id, supplier_id) REFERENCES supplier (shop_id, id)
);
CREATE INDEX supplier_ledger_supplier_idx ON supplier_ledger (shop_id, supplier_id, id);

CREATE TABLE purchase_order (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT         NOT NULL REFERENCES shop (id),
    number       VARCHAR(40)    NOT NULL,
    supplier_id  BIGINT         NOT NULL,
    branch_id    BIGINT         NOT NULL,
    status       VARCHAR(12)    NOT NULL DEFAULT 'ORDERED'
                 CHECK (status IN ('ORDERED', 'PARTIAL', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    expected_on  DATE,
    note         VARCHAR(500),
    total        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_by   BIGINT,
    actor_name   VARCHAR(200),
    business_date DATE          NOT NULL,
    at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    closed_reason VARCHAR(300),
    CONSTRAINT purchase_order_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT purchase_order_supplier_fk FOREIGN KEY (shop_id, supplier_id) REFERENCES supplier (shop_id, id),
    CONSTRAINT purchase_order_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX purchase_order_number_uq ON purchase_order (shop_id, number);

-- Quantities in the line's unit (unit_factor base units each).
CREATE TABLE po_line (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    po_id         BIGINT         NOT NULL,
    product_id    BIGINT         NOT NULL,
    unit_id       BIGINT         NOT NULL,
    unit_factor   NUMERIC(14, 3) NOT NULL DEFAULT 1 CHECK (unit_factor > 0),
    qty_ordered   NUMERIC(14, 3) NOT NULL CHECK (qty_ordered > 0),
    qty_received  NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK (qty_received >= 0),
    unit_cost     NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    CONSTRAINT po_line_doc_fk FOREIGN KEY (shop_id, po_id) REFERENCES purchase_order (shop_id, id),
    CONSTRAINT po_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT po_line_shop_id_uq UNIQUE (shop_id, id)
);
CREATE INDEX po_line_doc_idx ON po_line (shop_id, po_id);

CREATE TABLE grn (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id           BIGINT         NOT NULL REFERENCES shop (id),
    number            VARCHAR(40)    NOT NULL,
    supplier_id       BIGINT         NOT NULL,
    po_id             BIGINT,
    branch_id         BIGINT         NOT NULL,
    supplier_bill_no  VARCHAR(60),
    bill_date         DATE,
    subtotal          NUMERIC(14, 2) NOT NULL,
    discount          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    freight           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    tax               NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total             NUMERIC(14, 2) NOT NULL CHECK (total >= 0),
    returned          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid              NUMERIC(14, 2) NOT NULL DEFAULT 0,
    due               NUMERIC(14, 2) GENERATED ALWAYS AS (total - returned - paid) STORED,
    due_on            DATE,
    note              VARCHAR(500),
    created_by        BIGINT,
    actor_name        VARCHAR(200),
    business_date     DATE           NOT NULL,
    at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT grn_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT grn_supplier_fk FOREIGN KEY (shop_id, supplier_id) REFERENCES supplier (shop_id, id),
    CONSTRAINT grn_po_fk FOREIGN KEY (shop_id, po_id) REFERENCES purchase_order (shop_id, id),
    CONSTRAINT grn_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT grn_due_not_negative CHECK (total - returned - paid >= 0)
);
CREATE UNIQUE INDEX grn_number_uq ON grn (shop_id, number);
CREATE INDEX grn_supplier_idx ON grn (shop_id, supplier_id, business_date);

CREATE TABLE grn_line (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    grn_id           BIGINT         NOT NULL,
    po_line_id       BIGINT,
    product_id       BIGINT         NOT NULL,
    unit_id          BIGINT         NOT NULL,
    unit_factor      NUMERIC(14, 3) NOT NULL DEFAULT 1 CHECK (unit_factor > 0),
    qty              NUMERIC(14, 3) NOT NULL CHECK (qty >= 0),
    bonus_qty        NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK (bonus_qty >= 0),
    unit_cost        NUMERIC(14, 2) NOT NULL CHECK (unit_cost >= 0),
    discount         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    net              NUMERIC(14, 2) NOT NULL,
    base_qty         NUMERIC(14, 3) NOT NULL CHECK (base_qty > 0),
    base_unit_cost   NUMERIC(14, 2) NOT NULL,
    batch_id         BIGINT,
    qty_returned     NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK (qty_returned >= 0 AND qty_returned <= base_qty),
    CONSTRAINT grn_line_doc_fk FOREIGN KEY (shop_id, grn_id) REFERENCES grn (shop_id, id),
    CONSTRAINT grn_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT grn_line_shop_id_uq UNIQUE (shop_id, id)
);
CREATE INDEX grn_line_doc_idx ON grn_line (shop_id, grn_id);
CREATE INDEX grn_line_product_idx ON grn_line (shop_id, product_id);

CREATE TABLE purchase_return (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT         NOT NULL REFERENCES shop (id),
    number       VARCHAR(40)    NOT NULL,
    supplier_id  BIGINT         NOT NULL,
    grn_id       BIGINT,
    branch_id    BIGINT         NOT NULL,
    reason       VARCHAR(20)    NOT NULL CHECK (reason IN ('DAMAGED', 'EXPIRED', 'WRONG', 'EXCESS', 'OTHER')),
    note         VARCHAR(500),
    total        NUMERIC(14, 2) NOT NULL,
    created_by   BIGINT,
    actor_name   VARCHAR(200),
    business_date DATE          NOT NULL,
    at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT purchase_return_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT purchase_return_supplier_fk FOREIGN KEY (shop_id, supplier_id) REFERENCES supplier (shop_id, id),
    CONSTRAINT purchase_return_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX purchase_return_number_uq ON purchase_return (shop_id, number);

CREATE TABLE purchase_return_line (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT         NOT NULL REFERENCES shop (id),
    return_id    BIGINT         NOT NULL,
    grn_line_id  BIGINT,
    product_id   BIGINT         NOT NULL,
    batch_id     BIGINT,
    serial_id    BIGINT,
    qty          NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    unit_cost    NUMERIC(14, 2) NOT NULL,
    value        NUMERIC(14, 2) NOT NULL,
    CONSTRAINT purchase_return_line_doc_fk FOREIGN KEY (shop_id, return_id) REFERENCES purchase_return (shop_id, id),
    CONSTRAINT purchase_return_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);

CREATE TABLE supplier_payment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT         NOT NULL REFERENCES shop (id),
    number       VARCHAR(40)    NOT NULL,
    supplier_id  BIGINT         NOT NULL,
    branch_id    BIGINT,
    amount       NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    method_key   VARCHAR(20)    NOT NULL,
    reference    VARCHAR(100),
    note         VARCHAR(500),
    unallocated  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_by   BIGINT,
    actor_name   VARCHAR(200),
    business_date DATE          NOT NULL,
    at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT supplier_payment_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT supplier_payment_supplier_fk FOREIGN KEY (shop_id, supplier_id) REFERENCES supplier (shop_id, id)
);
CREATE UNIQUE INDEX supplier_payment_number_uq ON supplier_payment (shop_id, number);

CREATE TABLE supplier_payment_allocation (
    shop_id     BIGINT         NOT NULL REFERENCES shop (id),
    payment_id  BIGINT         NOT NULL,
    grn_id      BIGINT         NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (shop_id, payment_id, grn_id),
    CONSTRAINT supplier_payment_allocation_payment_fk FOREIGN KEY (shop_id, payment_id) REFERENCES supplier_payment (shop_id, id),
    CONSTRAINT supplier_payment_allocation_grn_fk FOREIGN KEY (shop_id, grn_id) REFERENCES grn (shop_id, id)
);

-- ---------------------------------------------------------------- sales
CREATE TABLE till_session (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT         NOT NULL REFERENCES shop (id),
    till_id        BIGINT         NOT NULL,
    branch_id      BIGINT         NOT NULL,
    user_id        BIGINT,
    actor_name     VARCHAR(200),
    opened_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    opening_float  NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (opening_float >= 0),
    business_date  DATE           NOT NULL,
    closed_at      TIMESTAMPTZ,
    closed_by      BIGINT,
    counted_cash   NUMERIC(14, 2),
    expected_cash  NUMERIC(14, 2),
    difference     NUMERIC(14, 2),
    counted_other  JSONB,
    note           VARCHAR(500),
    CONSTRAINT till_session_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT till_session_till_fk FOREIGN KEY (shop_id, till_id) REFERENCES till (shop_id, id),
    CONSTRAINT till_session_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX till_session_one_open_uq ON till_session (shop_id, till_id) WHERE closed_at IS NULL;

CREATE TABLE cash_movement (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    till_session_id  BIGINT         NOT NULL,
    kind             VARCHAR(4)     NOT NULL CHECK (kind IN ('IN', 'OUT')),
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    reason           VARCHAR(300)   NOT NULL,
    user_id          BIGINT,
    actor_name       VARCHAR(200),
    at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT cash_movement_session_fk FOREIGN KEY (shop_id, till_session_id) REFERENCES till_session (shop_id, id)
);

CREATE TABLE sale (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    number           VARCHAR(40)    NOT NULL,
    branch_id        BIGINT         NOT NULL,
    till_session_id  BIGINT,
    customer_id      BIGINT,
    customer_name    VARCHAR(200),
    customer_phone   VARCHAR(30),
    salesman_id      BIGINT,
    user_id          BIGINT,
    actor_name       VARCHAR(200),
    status           VARCHAR(12)    NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'CANCELLED')),
    subtotal         NUMERIC(14, 2) NOT NULL,
    line_discount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    bill_discount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    tax              NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total            NUMERIC(14, 2) NOT NULL CHECK (total >= 0),
    returned         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (returned >= 0),
    paid             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    due              NUMERIC(14, 2) GENERATED ALWAYS AS (total - returned - paid) STORED,
    change_given     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cost_total       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    approved_by      BIGINT,
    approval_note    VARCHAR(300),
    quotation_id     BIGINT,
    exchange_of_return_id BIGINT,
    note             VARCHAR(500),
    business_date    DATE           NOT NULL,
    at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    cancelled_at     TIMESTAMPTZ,
    cancelled_by     BIGINT,
    cancel_reason    VARCHAR(300),
    CONSTRAINT sale_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT sale_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT sale_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id),
    CONSTRAINT sale_session_fk FOREIGN KEY (shop_id, till_session_id) REFERENCES till_session (shop_id, id),
    CONSTRAINT sale_due_not_negative CHECK (total - returned - paid >= 0)
);
CREATE UNIQUE INDEX sale_number_uq ON sale (shop_id, number);
CREATE INDEX sale_date_idx ON sale (shop_id, business_date, branch_id);
CREATE INDEX sale_customer_idx ON sale (shop_id, customer_id, business_date) WHERE customer_id IS NOT NULL;

-- net = qty × price − discount − bill_share (before exclusive tax); total = net + tax when tax is added on top.
CREATE TABLE sale_line (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    sale_id       BIGINT         NOT NULL,
    line_no       INT            NOT NULL,
    product_id    BIGINT         NOT NULL,
    product_name  VARCHAR(200)   NOT NULL,
    unit_id       BIGINT,
    unit_name     VARCHAR(30),
    unit_factor   NUMERIC(14, 3) NOT NULL DEFAULT 1 CHECK (unit_factor > 0),
    batch_id      BIGINT,
    serial_id     BIGINT,
    qty           NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    base_qty      NUMERIC(14, 3) NOT NULL,
    price         NUMERIC(14, 2) NOT NULL CHECK (price >= 0),
    list_price    NUMERIC(14, 2) NOT NULL,
    discount      NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    bill_share    NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (bill_share >= 0),
    net           NUMERIC(14, 2) NOT NULL CHECK (net >= 0),
    tax_rate      NUMERIC(7, 4)  NOT NULL DEFAULT 0,
    tax           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total         NUMERIC(14, 2) NOT NULL CHECK (total >= 0),
    unit_cost     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cost_total    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    qty_returned  NUMERIC(14, 3) NOT NULL DEFAULT 0,
    CONSTRAINT sale_line_doc_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id),
    CONSTRAINT sale_line_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT sale_line_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT sale_line_returned_le_sold CHECK (qty_returned >= 0 AND qty_returned <= qty)
);
CREATE INDEX sale_line_doc_idx ON sale_line (shop_id, sale_id);
CREATE INDEX sale_line_product_idx ON sale_line (shop_id, product_id);

-- Money in and out for a sale: payments at the till (SALE), later receipts (RECEIPT), refunds (negative, REFUND).
CREATE TABLE sale_payment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    sale_id          BIGINT         NOT NULL,
    kind             VARCHAR(10)    NOT NULL CHECK (kind IN ('SALE', 'RECEIPT', 'REFUND', 'CANCEL')),
    method_key       VARCHAR(20)    NOT NULL,
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount <> 0),
    tendered         NUMERIC(14, 2),
    change_given     NUMERIC(14, 2),
    reference        VARCHAR(100),
    till_session_id  BIGINT,
    branch_id        BIGINT         NOT NULL,
    doc_type         VARCHAR(30),
    doc_id           BIGINT,
    business_date    DATE           NOT NULL,
    at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT sale_payment_doc_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id)
);
CREATE INDEX sale_payment_doc_idx ON sale_payment (shop_id, sale_id);
CREATE INDEX sale_payment_date_idx ON sale_payment (shop_id, business_date, branch_id);

CREATE TABLE credit_note (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id           BIGINT         NOT NULL REFERENCES shop (id),
    number            VARCHAR(40)    NOT NULL,
    customer_id       BIGINT,
    amount            NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    balance           NUMERIC(14, 2) NOT NULL CHECK (balance >= 0 AND balance <= amount),
    expires_on        DATE,
    source_return_id  BIGINT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT credit_note_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX credit_note_number_uq ON credit_note (shop_id, upper(number));

CREATE TABLE sale_return (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    number           VARCHAR(40)    NOT NULL,
    sale_id          BIGINT         NOT NULL,
    customer_id      BIGINT,
    branch_id        BIGINT         NOT NULL,
    till_session_id  BIGINT,
    reason           VARCHAR(300)   NOT NULL,
    value            NUMERIC(14, 2) NOT NULL CHECK (value >= 0),
    applied_to_due   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    refund_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (refund_amount >= 0),
    refund_method    VARCHAR(20),
    return_charge    NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (return_charge >= 0),
    credit_note_id   BIGINT,
    cost_restocked   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cost_damaged     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    approved_by      BIGINT,
    exchange_sale_id BIGINT,
    user_id          BIGINT,
    actor_name       VARCHAR(200),
    business_date    DATE           NOT NULL,
    at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT sale_return_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT sale_return_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id),
    CONSTRAINT sale_return_value_split CHECK (value = applied_to_due + refund_amount + return_charge)
);
CREATE UNIQUE INDEX sale_return_number_uq ON sale_return (shop_id, number);
CREATE INDEX sale_return_date_idx ON sale_return (shop_id, business_date);

CREATE TABLE sale_return_line (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    return_id     BIGINT         NOT NULL,
    sale_line_id  BIGINT         NOT NULL,
    product_id    BIGINT         NOT NULL,
    batch_id      BIGINT,
    serial_id     BIGINT,
    qty           NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    base_qty      NUMERIC(14, 3) NOT NULL,
    restock       BOOLEAN        NOT NULL,
    value         NUMERIC(14, 2) NOT NULL,
    unit_cost     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    CONSTRAINT sale_return_line_doc_fk FOREIGN KEY (shop_id, return_id) REFERENCES sale_return (shop_id, id),
    CONSTRAINT sale_return_line_line_fk FOREIGN KEY (shop_id, sale_line_id) REFERENCES sale_line (shop_id, id)
);

CREATE TABLE held_sale (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT        NOT NULL REFERENCES shop (id),
    branch_id   BIGINT        NOT NULL,
    user_id     BIGINT,
    actor_name  VARCHAR(200),
    label       VARCHAR(100)  NOT NULL,
    cart        JSONB         NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT held_sale_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE INDEX held_sale_branch_idx ON held_sale (shop_id, branch_id, created_at);

CREATE TABLE quotation (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id            BIGINT         NOT NULL REFERENCES shop (id),
    number             VARCHAR(40)    NOT NULL,
    branch_id          BIGINT         NOT NULL,
    customer_id        BIGINT,
    customer_name      VARCHAR(200),
    customer_phone     VARCHAR(30),
    valid_until        DATE           NOT NULL,
    status             VARCHAR(12)    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CONVERTED', 'CANCELLED')),
    subtotal           NUMERIC(14, 2) NOT NULL,
    line_discount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    bill_discount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    tax                NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total              NUMERIC(14, 2) NOT NULL,
    note               VARCHAR(500),
    converted_sale_id  BIGINT,
    created_by         BIGINT,
    actor_name         VARCHAR(200),
    business_date      DATE           NOT NULL,
    at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT quotation_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT quotation_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id)
);
CREATE UNIQUE INDEX quotation_number_uq ON quotation (shop_id, number);

CREATE TABLE quotation_line (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id),
    quotation_id  BIGINT         NOT NULL,
    line_no       INT            NOT NULL,
    product_id    BIGINT         NOT NULL,
    product_name  VARCHAR(200)   NOT NULL,
    unit_id       BIGINT,
    unit_name     VARCHAR(30),
    unit_factor   NUMERIC(14, 3) NOT NULL DEFAULT 1,
    qty           NUMERIC(14, 3) NOT NULL CHECK (qty > 0),
    price         NUMERIC(14, 2) NOT NULL,
    discount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    bill_share    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    net           NUMERIC(14, 2) NOT NULL,
    tax_rate      NUMERIC(7, 4)  NOT NULL DEFAULT 0,
    tax           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total         NUMERIC(14, 2) NOT NULL,
    CONSTRAINT quotation_line_doc_fk FOREIGN KEY (shop_id, quotation_id) REFERENCES quotation (shop_id, id)
);

-- Used phones and other items bought from walk-in sellers (module used_buyback, MOB-04).
CREATE TABLE buyback (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id            BIGINT         NOT NULL REFERENCES shop (id),
    number             VARCHAR(40)    NOT NULL,
    branch_id          BIGINT         NOT NULL,
    till_session_id    BIGINT,
    product_id         BIGINT         NOT NULL,
    serial_unit_id     BIGINT,
    seller_name        VARCHAR(200)   NOT NULL,
    seller_cnic        VARCHAR(20)    NOT NULL,
    seller_phone       VARCHAR(30),
    seller_address     VARCHAR(300),
    cnic_file_id       BIGINT         NOT NULL,
    item_file_id       BIGINT         NOT NULL,
    price              NUMERIC(14, 2) NOT NULL CHECK (price > 0),
    method_key         VARCHAR(20)    NOT NULL,
    notes              VARCHAR(500),
    created_by         BIGINT,
    actor_name         VARCHAR(200),
    business_date      DATE           NOT NULL,
    at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT buyback_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT buyback_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id),
    CONSTRAINT buyback_cnic_file_fk FOREIGN KEY (shop_id, cnic_file_id) REFERENCES stored_file (shop_id, id),
    CONSTRAINT buyback_item_file_fk FOREIGN KEY (shop_id, item_file_id) REFERENCES stored_file (shop_id, id)
);
CREATE UNIQUE INDEX buyback_number_uq ON buyback (shop_id, number);

-- Shop warranty claims (module warranty, MOB-06).
CREATE TABLE warranty_claim (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id         BIGINT        NOT NULL REFERENCES shop (id),
    number          VARCHAR(40)   NOT NULL,
    serial_unit_id  BIGINT,
    sale_id         BIGINT,
    product_id      BIGINT        NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(200),
    customer_phone  VARCHAR(30),
    issue           VARCHAR(500)  NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN', 'SENT', 'REPAIRED', 'REPLACED', 'REJECTED', 'CLOSED')),
    resolution      VARCHAR(500),
    in_warranty     BOOLEAN       NOT NULL,
    created_by      BIGINT,
    actor_name      VARCHAR(200),
    at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT warranty_claim_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT warranty_claim_product_fk FOREIGN KEY (shop_id, product_id) REFERENCES product (shop_id, id)
);
CREATE UNIQUE INDEX warranty_claim_number_uq ON warranty_claim (shop_id, number);

-- Customer payments: cleared against the oldest open invoices first (CUS-03).
CREATE TABLE customer_payment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    number           VARCHAR(40)    NOT NULL,
    customer_id      BIGINT         NOT NULL,
    branch_id        BIGINT         NOT NULL,
    till_session_id  BIGINT,
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    method_key       VARCHAR(20)    NOT NULL,
    reference        VARCHAR(100),
    note             VARCHAR(500),
    unallocated      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_by       BIGINT,
    actor_name       VARCHAR(200),
    business_date    DATE           NOT NULL,
    at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT customer_payment_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT customer_payment_customer_fk FOREIGN KEY (shop_id, customer_id) REFERENCES customer (shop_id, id)
);
CREATE UNIQUE INDEX customer_payment_number_uq ON customer_payment (shop_id, number);

CREATE TABLE customer_payment_allocation (
    shop_id     BIGINT         NOT NULL REFERENCES shop (id),
    payment_id  BIGINT         NOT NULL,
    sale_id     BIGINT         NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (shop_id, payment_id, sale_id),
    CONSTRAINT customer_payment_allocation_payment_fk FOREIGN KEY (shop_id, payment_id) REFERENCES customer_payment (shop_id, id),
    CONSTRAINT customer_payment_allocation_sale_fk FOREIGN KEY (shop_id, sale_id) REFERENCES sale (shop_id, id)
);

-- One key per request that creates money documents (POS-09): a retried request gets the first result.
CREATE TABLE idempotency_key (
    shop_id     BIGINT       NOT NULL REFERENCES shop (id),
    scope       VARCHAR(30)  NOT NULL,
    key         VARCHAR(80)  NOT NULL,
    doc_id      BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_id, scope, key)
);

-- ---------------------------------------------------------------- cash
CREATE TABLE expense_category (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT        NOT NULL REFERENCES shop (id),
    name        VARCHAR(60)   NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT expense_category_shop_id_uq UNIQUE (shop_id, id)
);
CREATE UNIQUE INDEX expense_category_name_uq ON expense_category (shop_id, lower(name));

CREATE TABLE expense (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id             BIGINT         NOT NULL REFERENCES shop (id),
    number              VARCHAR(40)    NOT NULL,
    branch_id           BIGINT         NOT NULL,
    category_id         BIGINT         NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    method_key          VARCHAR(20)    NOT NULL,
    till_session_id     BIGINT,
    paid_to             VARCHAR(200),
    note                VARCHAR(500),
    attachment_file_id  BIGINT,
    status              VARCHAR(10)    NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'VOID')),
    void_reason         VARCHAR(300),
    created_by          BIGINT,
    actor_name          VARCHAR(200),
    business_date       DATE           NOT NULL,
    at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT expense_shop_id_uq UNIQUE (shop_id, id),
    CONSTRAINT expense_category_fk FOREIGN KEY (shop_id, category_id) REFERENCES expense_category (shop_id, id),
    CONSTRAINT expense_branch_fk FOREIGN KEY (shop_id, branch_id) REFERENCES branch (shop_id, id),
    CONSTRAINT expense_file_fk FOREIGN KEY (shop_id, attachment_file_id) REFERENCES stored_file (shop_id, id)
);
CREATE UNIQUE INDEX expense_number_uq ON expense (shop_id, number);
CREATE INDEX expense_date_idx ON expense (shop_id, business_date, branch_id);

-- ---------------------------------------------------------------- notifications, exports, onboarding
-- Outbox: email, SMS (Android gateway) and in-app messages. Sent after commit by the dispatcher with retries.
CREATE TABLE notification (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id          BIGINT         NOT NULL REFERENCES shop (id),
    channel          VARCHAR(10)    NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'IN_APP')),
    recipient        VARCHAR(254),
    user_id          BIGINT,
    topic            VARCHAR(40)    NOT NULL,
    subject          VARCHAR(200),
    body             TEXT           NOT NULL,
    link             VARCHAR(300),
    status           VARCHAR(10)    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'READ')),
    attempts         INT            NOT NULL DEFAULT 0,
    last_error       VARCHAR(500),
    next_attempt_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    read_at          TIMESTAMPTZ,
    dedupe_key       VARCHAR(120),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX notification_pending_idx ON notification (shop_id, status, next_attempt_at);
CREATE INDEX notification_user_idx ON notification (shop_id, user_id, created_at DESC) WHERE channel = 'IN_APP';
CREATE UNIQUE INDEX notification_dedupe_uq ON notification (shop_id, dedupe_key) WHERE dedupe_key IS NOT NULL;

CREATE TABLE export_job (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT        NOT NULL REFERENCES shop (id),
    kind          VARCHAR(60)   NOT NULL,
    format        VARCHAR(6)    NOT NULL CHECK (format IN ('XLSX', 'PDF')),
    params        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status        VARCHAR(10)   NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    file_id       BIGINT,
    row_count     INT,
    error         VARCHAR(500),
    requested_by  BIGINT,
    actor_name    VARCHAR(200),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    CONSTRAINT export_job_file_fk FOREIGN KEY (shop_id, file_id) REFERENCES stored_file (shop_id, id)
);
CREATE INDEX export_job_shop_idx ON export_job (shop_id, created_at DESC);

ALTER TABLE shop_settings ADD COLUMN printer_tested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop_settings ADD COLUMN credit_note_days INT NOT NULL DEFAULT 90 CHECK (credit_note_days >= 0);
ALTER TABLE shop_settings ADD COLUMN exchange_credit_note_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop_settings ADD COLUMN near_expiry_days INT NOT NULL DEFAULT 90 CHECK (near_expiry_days BETWEEN 1 AND 365);
ALTER TABLE shop_settings ADD COLUMN dead_stock_days INT NOT NULL DEFAULT 90 CHECK (dead_stock_days BETWEEN 7 AND 730);
ALTER TABLE shop_settings ADD COLUMN return_limit NUMERIC(14, 2) NOT NULL DEFAULT 5000 CHECK (return_limit >= 0);

-- ---------------------------------------------------------------- platform billing (no RLS: platform tables)
CREATE TABLE subscription_invoice (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    number        VARCHAR(40)    NOT NULL UNIQUE,
    kind          VARCHAR(12)    NOT NULL DEFAULT 'RENEWAL' CHECK (kind IN ('RENEWAL', 'UPGRADE', 'SETUP', 'MANUAL')),
    plan_id       BIGINT         NOT NULL REFERENCES plan (id),
    cycle         VARCHAR(10)    NOT NULL,
    period_from   DATE           NOT NULL,
    period_to     DATE           NOT NULL,
    amount        NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    discount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total         NUMERIC(14, 2) NOT NULL CHECK (total >= 0),
    paid          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    due_on        DATE           NOT NULL,
    status        VARCHAR(8)     NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'VOID')),
    note          VARCHAR(300),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    paid_at       TIMESTAMPTZ
);
CREATE INDEX subscription_invoice_shop_idx ON subscription_invoice (shop_id, period_from);
CREATE UNIQUE INDEX subscription_invoice_renewal_uq ON subscription_invoice (shop_id, period_from)
    WHERE kind = 'RENEWAL' AND status <> 'VOID';

CREATE TABLE subscription_payment (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT         NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    invoice_id    BIGINT         NOT NULL REFERENCES subscription_invoice (id),
    amount        NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    method        VARCHAR(12)    NOT NULL CHECK (method IN ('CASH', 'BANK', 'JAZZCASH', 'EASYPAISA', 'CHEQUE')),
    reference     VARCHAR(100),
    received_on   DATE           NOT NULL,
    recorded_by   BIGINT         REFERENCES platform_user (id),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE subscription_reminder (
    shop_id     BIGINT       NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    invoice_id  BIGINT       NOT NULL REFERENCES subscription_invoice (id),
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('D7', 'D3', 'D1', 'OVERDUE')),
    sent_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (invoice_id, kind)
);

ALTER TABLE subscription ADD COLUMN pending_plan_id BIGINT REFERENCES plan (id);
ALTER TABLE subscription ADD COLUMN pending_cycle VARCHAR(10) CHECK (pending_cycle IN ('MONTH', 'YEAR'));
ALTER TABLE shop ADD COLUMN status_changed_at TIMESTAMPTZ;
ALTER TABLE shop ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop ADD COLUMN purged_at TIMESTAMPTZ;

INSERT INTO platform_setting (setting_key, value) VALUES
    ('sms_gateway_url', ''), ('sms_gateway_token', ''), ('invoice_days_before', '7')
ON CONFLICT (setting_key) DO NOTHING;

-- ---------------------------------------------------------------- numbering for the new documents
INSERT INTO numbering (shop_id, doc_type, prefix, next_no, digits)
SELECT s.id, t.doc_type, t.prefix, 1, 6
FROM shop s CROSS JOIN (VALUES ('CREDIT_NOTE', 'CN'), ('BUYBACK', 'BUY'), ('WARRANTY_CLAIM', 'WCL'),
                               ('CUSTOMER', 'C'), ('SUPPLIER', 'S')) AS t(doc_type, prefix)
WHERE NOT EXISTS (SELECT 1 FROM numbering n WHERE n.shop_id = s.id AND n.branch_id IS NULL AND n.doc_type = t.doc_type);

INSERT INTO expense_category (shop_id, name)
SELECT s.id, c.name FROM shop s
CROSS JOIN (VALUES ('Rent'), ('Salaries'), ('Electricity'), ('Gas and water'), ('Internet and phone'), ('Tea and food'),
                   ('Transport'), ('Repairs and maintenance'), ('Stationery'), ('Other')) AS c(name);

-- ---------------------------------------------------------------- D7: posted documents keep their money and quantities
CREATE FUNCTION block_posted_changes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'sale' THEN
        IF NEW.subtotal <> OLD.subtotal OR NEW.total <> OLD.total OR NEW.tax <> OLD.tax
           OR NEW.line_discount <> OLD.line_discount OR NEW.bill_discount <> OLD.bill_discount
           OR NEW.cost_total <> OLD.cost_total OR NEW.number <> OLD.number OR NEW.business_date <> OLD.business_date THEN
            RAISE EXCEPTION 'posted sale % cannot change its amounts', OLD.number;
        END IF;
    ELSIF TG_TABLE_NAME = 'sale_line' THEN
        IF NEW.qty <> OLD.qty OR NEW.price <> OLD.price OR NEW.net <> OLD.net OR NEW.tax <> OLD.tax
           OR NEW.total <> OLD.total OR NEW.unit_cost <> OLD.unit_cost OR NEW.product_id <> OLD.product_id THEN
            RAISE EXCEPTION 'posted sale line cannot change';
        END IF;
    ELSIF TG_TABLE_NAME = 'grn' THEN
        IF NEW.subtotal <> OLD.subtotal OR NEW.total <> OLD.total OR NEW.discount <> OLD.discount
           OR NEW.freight <> OLD.freight OR NEW.number <> OLD.number THEN
            RAISE EXCEPTION 'posted GRN % cannot change its amounts', OLD.number;
        END IF;
    ELSIF TG_TABLE_NAME = 'grn_line' THEN
        IF NEW.qty <> OLD.qty OR NEW.bonus_qty <> OLD.bonus_qty OR NEW.unit_cost <> OLD.unit_cost
           OR NEW.net <> OLD.net OR NEW.base_qty <> OLD.base_qty THEN
            RAISE EXCEPTION 'posted GRN line cannot change';
        END IF;
    ELSE
        RAISE EXCEPTION '% rows are never changed once posted', TG_TABLE_NAME;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER sale_posted BEFORE UPDATE ON sale FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER sale_line_posted BEFORE UPDATE ON sale_line FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER grn_posted BEFORE UPDATE ON grn FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER grn_line_posted BEFORE UPDATE ON grn_line FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER adjustment_posted BEFORE UPDATE ON adjustment FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER adjustment_line_posted BEFORE UPDATE ON adjustment_line FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER stock_movement_posted BEFORE UPDATE ON stock_movement FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER customer_ledger_posted BEFORE UPDATE ON customer_ledger FOR EACH ROW EXECUTE FUNCTION block_posted_changes();
CREATE TRIGGER supplier_ledger_posted BEFORE UPDATE ON supplier_ledger FOR EACH ROW EXECUTE FUNCTION block_posted_changes();

-- ---------------------------------------------------------------- retention (ONB-06, OPS-15)
-- Deletes every shop row of a closed shop after its 90 days. The shop record, its subscription billing and the
-- platform audit log are kept. Owned by the migration role so it can delete what the app role may not.
CREATE FUNCTION purge_shop(p_shop_id BIGINT) RETURNS INT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    t       TEXT;
    pass    INT := 0;
    left_   INT;
    deleted INT := 0;
    n       INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM shop WHERE id = p_shop_id AND status = 'CLOSED') THEN
        RAISE EXCEPTION 'shop % is not closed', p_shop_id;
    END IF;
    LOOP
        pass := pass + 1;
        left_ := 0;
        FOR t IN SELECT c.relname FROM pg_class c
                 JOIN pg_namespace ns ON ns.oid = c.relnamespace AND ns.nspname = 'public'
                 JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'shop_id' AND NOT a.attisdropped
                 WHERE c.relkind = 'r' AND c.relname NOT IN ('shop', 'subscription', 'subscription_invoice',
                       'subscription_payment', 'subscription_reminder', 'platform_audit_log')
        LOOP
            BEGIN
                EXECUTE format('DELETE FROM %I WHERE shop_id = $1', t) USING p_shop_id;
                GET DIAGNOSTICS n = ROW_COUNT;
                deleted := deleted + n;
            EXCEPTION WHEN foreign_key_violation THEN
                left_ := left_ + 1;
            END;
        END LOOP;
        EXIT WHEN left_ = 0 OR pass > 30;
    END LOOP;
    IF left_ > 0 THEN
        RAISE EXCEPTION 'shop % could not be purged', p_shop_id;
    END IF;
    UPDATE shop SET purged_at = now() WHERE id = p_shop_id;
    RETURN deleted;
END
$$;

-- Activity history is kept 2 years (OPS-15).
CREATE FUNCTION purge_old_audit(p_before TIMESTAMPTZ) RETURNS INT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE n INT;
BEGIN
    DELETE FROM audit_log WHERE at < p_before;
    GET DIAGNOSTICS n = ROW_COUNT;
    DELETE FROM login_event WHERE at < p_before;
    RETURN n;
END
$$;

-- Demo shops (OPS-01) are rebuilt every night: close and purge in one step.
CREATE FUNCTION purge_demo_shop(p_shop_id BIGINT) RETURNS INT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE n INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM shop WHERE id = p_shop_id AND is_demo) THEN
        RAISE EXCEPTION 'shop % is not a demo shop', p_shop_id;
    END IF;
    UPDATE shop SET status = 'CLOSED' WHERE id = p_shop_id;
    n := purge_shop(p_shop_id);
    DELETE FROM shop WHERE id = p_shop_id;
    RETURN n;
END
$$;

REVOKE ALL ON FUNCTION purge_shop(BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION purge_old_audit(TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION purge_demo_shop(BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_shop(BIGINT), purge_old_audit(TIMESTAMPTZ), purge_demo_shop(BIGINT) TO pos_app;

-- ---------------------------------------------------------------- RLS and grants
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['product_variant', 'size_set', 'bundle_item', 'batch', 'product_stock', 'serial_unit',
                             'stock_movement', 'adjustment', 'adjustment_line', 'stock_count', 'stock_count_line',
                             'transfer', 'transfer_line', 'stock_alert', 'customer', 'customer_ledger', 'supplier',
                             'supplier_ledger', 'purchase_order', 'po_line', 'grn', 'grn_line', 'purchase_return',
                             'purchase_return_line', 'supplier_payment', 'supplier_payment_allocation', 'till_session',
                             'cash_movement', 'sale', 'sale_line', 'sale_payment', 'credit_note', 'sale_return',
                             'sale_return_line', 'held_sale', 'quotation', 'quotation_line', 'buyback', 'warranty_claim',
                             'customer_payment', 'customer_payment_allocation', 'idempotency_key', 'expense_category',
                             'expense', 'notification', 'export_job']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (shop_id = app_current_shop() OR app_all_shops())'
                       ' WITH CHECK (shop_id = app_current_shop() OR app_all_shops())', t);
    END LOOP;
END
$$;

-- Insert-only history: movements, ledgers, allocations, payments, return lines.
GRANT SELECT, INSERT ON stock_movement, adjustment, adjustment_line, customer_ledger, supplier_ledger,
    supplier_payment_allocation, customer_payment_allocation, sale_payment, sale_return_line,
    purchase_return, purchase_return_line, cash_movement, buyback TO pos_app;
GRANT SELECT, INSERT, UPDATE ON product_variant, size_set, batch, product_stock, serial_unit, stock_count,
    stock_count_line, transfer, transfer_line, stock_alert, customer, supplier, purchase_order, po_line, grn, grn_line,
    supplier_payment, till_session, sale, sale_line, credit_note, sale_return, quotation, quotation_line,
    warranty_claim, customer_payment, idempotency_key, expense_category, expense, notification, export_job TO pos_app;
GRANT DELETE ON bundle_item, held_sale, stock_count_line, po_line, size_set TO pos_app;
GRANT SELECT, INSERT ON bundle_item, held_sale TO pos_app;
GRANT SELECT, INSERT, UPDATE ON subscription_invoice TO pos_app;
GRANT SELECT, INSERT ON subscription_payment, subscription_reminder TO pos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pos_app;
