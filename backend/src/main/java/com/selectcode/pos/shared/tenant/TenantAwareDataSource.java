package com.selectcode.pos.shared.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets the PostgreSQL session variables read by the row-level security policies every time a connection is taken
 * from the pool. This protects native SQL, reports and anything Hibernate's tenant filter does not cover.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

	private static final String APPLY_SQL =
			"select set_config('app.shop_id', ?, false), set_config('app.all_shops', ?, false)";

	public TenantAwareDataSource(DataSource target) {
		super(target);
	}

	@Override
	public Connection getConnection() throws SQLException {
		return apply(super.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return apply(super.getConnection(username, password));
	}

	private static Connection apply(Connection connection) throws SQLException {
		String shopId = TenantContext.shopId().map(String::valueOf).orElse("");
		String allShops = TenantContext.isAllShops() ? "on" : "off";
		try (PreparedStatement statement = connection.prepareStatement(APPLY_SQL)) {
			statement.setString(1, shopId);
			statement.setString(2, allShops);
			statement.execute();
		}
		catch (SQLException ex) {
			connection.close();
			throw ex;
		}
		return connection;
	}
}
