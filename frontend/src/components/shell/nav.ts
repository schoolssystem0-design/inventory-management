import {
  AlertTriangle,
  ArrowLeftRight,
  Banknote,
  Barcode,
  BarChart3,
  Boxes,
  Building2,
  ClipboardCheck,
  ClipboardList,
  CalendarClock,
  CreditCard,
  FileSpreadsheet,
  FileText,
  FileUp,
  Gift,
  History,
  Home,
  type LucideIcon,
  PackagePlus,
  PackageCheck,
  Percent,
  Pill,
  Receipt,
  RefreshCw,
  RotateCcw,
  Settings,
  ShieldCheck,
  ShoppingCart,
  Smartphone,
  Tags,
  TrendingUp,
  Truck,
  UserCog,
  UserRound,
  Users,
  Wallet,
  Warehouse,
  Wrench,
} from "lucide-react";

export type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  /** Shown when the user has any of these permissions (empty = everyone). */
  anyOf?: string[];
  /** Shown only when this module is switched on for the shop. */
  module?: string;
  /** Words the command palette also matches. */
  keywords?: string;
};

export type NavGroup = { label: string; items: NavItem[] };

/** The shop app's navigation. Items appear only when the user may use them; the API checks every call anyway. */
export const SHOP_NAV: NavGroup[] = [
  { label: "", items: [{ href: "/", label: "Home", icon: Home, keywords: "dashboard start today" }] },
  {
    label: "Selling",
    items: [
      { href: "/pos", label: "Point of sale", icon: ShoppingCart, anyOf: ["pos.sell"], keywords: "till sell bill counter checkout" },
      { href: "/sales", label: "Sales and returns", icon: Receipt, anyOf: ["sales.view", "sales.return"], keywords: "invoices bills return exchange cancel" },
      { href: "/quotations", label: "Quotations", icon: FileText, anyOf: ["quotations.manage", "sales.view"], module: "quotations", keywords: "quote estimate" },
      { href: "/customers", label: "Customers", icon: Users, anyOf: ["customers.view"], keywords: "ledger credit account statement" },
      { href: "/till", label: "Till and cash", icon: Banknote, anyOf: ["till.manage", "cash.view"], keywords: "open close z report shift drawer" },
      { href: "/repairs", label: "Repair jobs", icon: Wrench, anyOf: ["repairs.manage", "pos.sell"], module: "repairs", keywords: "repair job card fix technician" },
      { href: "/installments", label: "Installments", icon: CalendarClock, anyOf: ["installments.manage", "customers.payments", "customers.view"], module: "installments", keywords: "qist installment plan guarantor due" },
      { href: "/deliveries", label: "Deliveries", icon: Truck, anyOf: ["delivery.manage", "sales.view"], module: "delivery", keywords: "delivery driver installation" },
      { href: "/challans", label: "Delivery challans", icon: PackageCheck, anyOf: ["delivery.manage", "sales.view"], module: "delivery", keywords: "challan dc goods sent" },
      { href: "/promotions", label: "Offers and coupons", icon: Gift, anyOf: ["promotions.manage", "reports.view"], module: "promotions", keywords: "offer discount coupon deal happy hour" },
      { href: "/prescriptions", label: "Prescriptions", icon: Pill, anyOf: ["prescriptions.approve", "sales.view"], module: "prescriptions", keywords: "prescription doctor patient rx controlled register" },
    ],
  },
  {
    label: "Stock",
    items: [
      { href: "/stock", label: "Stock", icon: Warehouse, anyOf: ["stock.view"], keywords: "on hand inventory quantity value" },
      { href: "/stock/adjustments", label: "Adjustments", icon: ClipboardList, anyOf: ["stock.view", "stock.adjust"], keywords: "write off damaged lost found opening" },
      { href: "/stock/counts", label: "Stock counts", icon: ClipboardCheck, anyOf: ["stock.count"], keywords: "stock take audit count" },
      { href: "/stock/transfers", label: "Transfers", icon: ArrowLeftRight, anyOf: ["stock.view", "stock.transfer"], keywords: "branch godown send receive" },
      { href: "/stock/alerts", label: "Alerts", icon: AlertTriangle, anyOf: ["stock.view"], keywords: "low out expiry dead" },
      { href: "/imei", label: "IMEI search", icon: Smartphone, anyOf: ["stock.view", "serials.manage", "pos.sell"], module: "serials", keywords: "serial phone imei history" },
      { href: "/buybacks", label: "Used phones bought", icon: RefreshCw, anyOf: ["buyback.manage"], module: "used_buyback", keywords: "buy used cnic seller" },
      { href: "/warranty", label: "Warranty claims", icon: Wrench, anyOf: ["warranty.manage", "pos.sell"], module: "warranty", keywords: "claim repair warranty" },
    ],
  },
  {
    label: "Purchasing",
    items: [
      { href: "/suppliers", label: "Suppliers", icon: Truck, anyOf: ["purchasing.view"], keywords: "distributor vendor payable" },
      { href: "/purchases", label: "Purchase orders", icon: ClipboardList, anyOf: ["purchasing.view"], keywords: "po order" },
      { href: "/purchases/receive", label: "Receive goods", icon: PackagePlus, anyOf: ["purchasing.manage"], keywords: "grn receive purchase bill" },
      { href: "/purchases/returns", label: "Purchase returns", icon: RotateCcw, anyOf: ["purchasing.view"], keywords: "return to supplier" },
      { href: "/purchases/reorder", label: "Reorder", icon: TrendingUp, anyOf: ["purchasing.view"], keywords: "low stock suggest order" },
    ],
  },
  {
    label: "Products",
    items: [
      { href: "/products", label: "Products", icon: Boxes, anyOf: ["catalog.view"], keywords: "items catalogue" },
      { href: "/products/import", label: "Import from Excel", icon: FileUp, anyOf: ["catalog.import"], keywords: "excel upload csv" },
      { href: "/products/bulk-price", label: "Change prices", icon: Percent, anyOf: ["catalog.prices"], keywords: "bulk price increase" },
      { href: "/products/labels", label: "Barcode labels", icon: Barcode, anyOf: ["labels.print"], keywords: "print sticker" },
      { href: "/catalogue", label: "Categories, brands, units", icon: Tags, anyOf: ["catalog.view"], keywords: "category brand unit size set" },
    ],
  },
  {
    label: "Money and reports",
    items: [
      { href: "/expenses", label: "Expenses", icon: Wallet, anyOf: ["cash.view", "expenses.manage"], keywords: "rent electricity bill" },
      { href: "/cash", label: "Daily cash", icon: Banknote, anyOf: ["cash.view"], keywords: "cash summary closing" },
      { href: "/profit", label: "Profit and loss", icon: TrendingUp, anyOf: ["cost.view"], keywords: "p&l profit loss month" },
      { href: "/reports", label: "Reports", icon: BarChart3, anyOf: ["reports.view"], keywords: "report export excel pdf" },
    ],
  },
  {
    label: "Shop",
    items: [
      { href: "/settings", label: "Settings", icon: Settings, anyOf: ["settings.view"], keywords: "tax receipt numbering payment" },
      { href: "/branches", label: "Branches and tills", icon: Building2, anyOf: ["settings.view", "branches.manage"], keywords: "godown counter" },
      { href: "/users", label: "Users", icon: Users, anyOf: ["users.view"], keywords: "staff cashier login" },
      { href: "/roles", label: "Roles", icon: UserCog, anyOf: ["users.view"], keywords: "permissions access" },
      { href: "/security", label: "Activity and logins", icon: History, anyOf: ["audit.view", "login_history.view"], keywords: "audit history log" },
      { href: "/subscription", label: "Subscription", icon: CreditCard, anyOf: ["subscription.view"], keywords: "plan invoice payment due" },
      { href: "/exports", label: "Exports and backup", icon: FileSpreadsheet, anyOf: ["reports.view", "data.export"], keywords: "download backup excel" },
    ],
  },
  {
    label: "You",
    items: [{ href: "/account", label: "My account", icon: UserRound, keywords: "password pin authenticator" }],
  },
];

export const ACCOUNT_ICON = ShieldCheck;

export function visibleNav(can: (p: string) => boolean, feature: (key: string) => boolean = () => true): NavGroup[] {
  return SHOP_NAV.map((group) => ({
    ...group,
    items: group.items.filter((item) => (!item.anyOf || item.anyOf.some(can)) && (!item.module || feature(item.module))),
  })).filter((group) => group.items.length > 0);
}
