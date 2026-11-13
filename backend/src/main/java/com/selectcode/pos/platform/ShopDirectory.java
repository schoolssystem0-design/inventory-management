package com.selectcode.pos.platform;

import com.selectcode.pos.shared.tenant.ShopLookup;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The platform's shop registry, exposed to other modules through {@link ShopLookup}. */
@Service
public class ShopDirectory implements ShopLookup {

	private static final String SELECT =
			"select s.id, s.code, s.name, s.status, t.type_key from shop s join shop_type t on t.id = s.shop_type_id ";

	private final JdbcTemplate jdbc;

	ShopDirectory(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ShopRef> findByCode(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		return first(jdbc.query(SELECT + "where s.code = ?", (rs, i) -> ref(rs), code.trim().toLowerCase(Locale.ROOT)));
	}

	@Override
	public Optional<ShopRef> findById(long id) {
		return first(jdbc.query(SELECT + "where s.id = ?", (rs, i) -> ref(rs), id));
	}

	private static ShopRef ref(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ShopRef(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("status"),
				rs.getString("type_key"));
	}

	private static Optional<ShopRef> first(List<ShopRef> rows) {
		return rows.stream().findFirst();
	}
}
