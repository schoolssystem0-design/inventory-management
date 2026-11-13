package com.selectcode.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.selectcode.pos.catalog.app.BarcodeFormats;
import com.selectcode.pos.iam.domain.PasswordPolicy;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.security.Totp;
import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** Pure rules: money and quantity parsing, barcodes, passwords, TOTP, module and permission catalogues. */
class UnitRulesTest {

	@Test
	void moneyIsParsedStrictlyAndAlwaysHasTwoDecimals() {
		assertThat(Amounts.parseMoney("3,500", "x")).isEqualByComparingTo("3500").hasScaleOf(2);
		assertThat(Amounts.text(new BigDecimal("1250.5"))).isEqualTo("1250.50");
		assertThat(Amounts.parseMoney(" ", "x")).isNull();
		assertThatThrownBy(() -> Amounts.parseMoney("12.345", "x")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Amounts.parseMoney("-1", "x")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Amounts.parseMoney("Rs 10", "x")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Amounts.parseMoney("1000000000000", "x")).isInstanceOf(ApiException.class);
		assertThat(Amounts.money(new BigDecimal("2.005"))).isEqualByComparingTo("2.01");
	}

	@Test
	void quantitiesKeepThreeDecimals() {
		assertThat(Amounts.qtyText(Amounts.parseQty("1.25", "q"))).isEqualTo("1.250");
		assertThatThrownBy(() -> Amounts.parseQty("1.2345", "q")).isInstanceOf(ApiException.class);
		assertThat(Amounts.parsePercent("17.5", "t")).isEqualByComparingTo("17.5");
		assertThatThrownBy(() -> Amounts.parsePercent("101", "t")).isInstanceOf(ApiException.class);
	}

	@Test
	void barcodeFormatsAndCheckDigits() {
		assertThat(BarcodeFormats.formatOf("4006381333931")).isEqualTo("EAN13");
		assertThat(BarcodeFormats.formatOf("4006381333932")).isEqualTo("CODE128");
		assertThat(BarcodeFormats.formatOf("036000291452")).isEqualTo("UPC");
		assertThat(BarcodeFormats.formatOf("96385074")).isEqualTo("EAN8");
		assertThat(BarcodeFormats.formatOf("MED-1")).isEqualTo("CODE128");
		String generated = BarcodeFormats.inStoreEan13(42);
		assertThat(generated).isEqualTo("2000000000428");
		assertThat(BarcodeFormats.validCheckDigit(generated)).isTrue();
		assertThat(BarcodeFormats.acceptable("ab")).isFalse();
		assertThat(BarcodeFormats.acceptable("has space")).isFalse();
	}

	@Test
	void passwordPolicy() {
		PasswordPolicy.check("Correct-Horse-42", "owner");
		assertThatThrownBy(() -> PasswordPolicy.check("short", "u")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> PasswordPolicy.check("password123", "u")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> PasswordPolicy.check("cashier-2026-pk", "cashier")).isInstanceOf(ApiException.class);
		assertThat(PasswordPolicy.temporary()).hasSize(12);
	}

	@Test
	void totpCodesVerifyWithinOneStep() {
		String secret = Totp.newSecret();
		Instant now = Instant.parse("2026-11-08T10:00:00Z");
		assertThat(Totp.verify(secret, Totp.codeAt(secret, now), now)).isTrue();
		assertThat(Totp.verify(secret, Totp.codeAt(secret, now.minusSeconds(30)), now)).isTrue();
		assertThat(Totp.verify(secret, Totp.codeAt(secret, now.minusSeconds(120)), now)).isFalse();
		assertThat(Totp.verify(secret, "12345", now)).isFalse();
	}

	@Test
	void moduleAndPermissionCataloguesAreUnique() {
		assertThat(new HashSet<>(Arrays.stream(ModuleKey.values()).map(ModuleKey::key).toList()))
			.hasSize(ModuleKey.values().length);
		assertThat(ModuleKey.of("formula")).contains(ModuleKey.FORMULA);
		assertThat(ModuleKey.of("nope")).isEmpty();
		assertThat(Permissions.all()).doesNotHaveDuplicates().contains(Permissions.COST_VIEW, Permissions.POS_SELL);
	}
}
