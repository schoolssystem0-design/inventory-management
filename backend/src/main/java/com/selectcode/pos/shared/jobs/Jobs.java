package com.selectcode.pos.shared.jobs;

import com.selectcode.pos.shared.tenant.TenantContext;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background jobs (docs/architecture/ARCHITECTURE.md §10): a Postgres advisory lock makes sure only one API instance runs a job,
 * and each shop is processed in its own transaction with the shop in context, so one shop's failure does not stop the
 * others. Scheduled methods call {@link #enabled()} first; tests switch scheduling off and call the jobs directly.
 */
@Component
public class Jobs {

	private static final Logger log = LoggerFactory.getLogger(Jobs.class);

	private final JdbcTemplate jdbc;

	private final TransactionTemplate outer;

	private final TransactionTemplate perShop;

	private final boolean enabled;

	Jobs(JdbcTemplate jdbc, PlatformTransactionManager transactions, @Value("${app.jobs.enabled:true}") boolean enabled) {
		this.jdbc = jdbc;
		this.outer = new TransactionTemplate(transactions);
		this.perShop = new TransactionTemplate(transactions);
		this.perShop.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.enabled = enabled;
	}

	/** Scheduled runs happen only when {@code app.jobs.enabled} is true (it is false in tests). */
	public boolean enabled() {
		return enabled;
	}

	/** Runs the job body while holding the job's advisory lock; returns false if another instance holds it. */
	public boolean exclusive(String job, Runnable body) {
		Boolean ran = outer.execute(status -> {
			Boolean got = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, job);
			if (!Boolean.TRUE.equals(got)) {
				log.info("job {} is already running elsewhere", job);
				return false;
			}
			body.run();
			return true;
		});
		return Boolean.TRUE.equals(ran);
	}

	/** Ids of shops in the given statuses (all statuses when none are given). */
	public List<Long> shops(String... statuses) {
		if (statuses.length == 0) {
			return jdbc.queryForList("select id from shop where purged_at is null order by id", Long.class);
		}
		return jdbc.queryForList("select id from shop where purged_at is null and status = any(?) order by id",
				Long.class, (Object) statuses);
	}

	/** Runs {@code work} once per shop, each in its own transaction as that shop. Returns the number that failed. */
	public int forEachShop(String job, List<Long> shopIds, Consumer<Long> work) {
		int failed = 0;
		for (Long shopId : shopIds) {
			try {
				TenantContext.runAsShop(shopId, () -> perShop.executeWithoutResult(s -> work.accept(shopId)));
			}
			catch (RuntimeException e) {
				failed++;
				log.warn("job {} failed for shop {}: {}", job, shopId, e.getMessage(), e);
			}
		}
		return failed;
	}
}
