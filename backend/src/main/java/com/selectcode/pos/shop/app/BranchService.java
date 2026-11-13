package com.selectcode.pos.shop.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.PlanLimits;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shop.domain.Branch;
import com.selectcode.pos.shop.domain.Till;
import com.selectcode.pos.shop.infra.BranchRepository;
import com.selectcode.pos.shop.infra.TillRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Branches, godowns and tills (SET-02). Used branches and tills are deactivated, never deleted. */
@Service
public class BranchService {

	public record BranchView(long id, String name, String code, String kind, String address, String phone,
			boolean isDefault, boolean active, long version) {

		static BranchView of(Branch b) {
			return new BranchView(b.getId(), b.getName(), b.getCode(), b.getKind().name(), b.getAddress(), b.getPhone(),
					b.isDefault(), b.isActive(), b.getVersion());
		}
	}

	public record BranchInput(String name, String code, String kind, String address, String phone) {
	}

	public record TillView(long id, long branchId, String name, boolean active, long version) {

		static TillView of(Till t) {
			return new TillView(t.getId(), t.getBranchId(), t.getName(), t.isActive(), t.getVersion());
		}
	}

	private final BranchRepository branches;

	private final TillRepository tills;

	private final PlanLimits limits;

	private final AuditService audit;

	private final Clock clock;

	BranchService(BranchRepository branches, TillRepository tills, PlanLimits limits, AuditService audit, Clock clock) {
		this.branches = branches;
		this.tills = tills;
		this.limits = limits;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<BranchView> list() {
		return branches.findAllOrdered().stream().map(BranchView::of).toList();
	}

	@Transactional
	public BranchView create(BranchInput input) {
		limits.check(PlanLimits.Limit.BRANCHES, branches.countByActiveTrue(), 1);
		String name = name(input.name());
		String code = code(input.code(), branches.count() + 1);
		ensureUnique(name, code, -1);
		Branch branch = new Branch(name, code, kind(input.kind()), false);
		branch.update(name, code, kind(input.kind()), trim(input.address(), 500), trim(input.phone(), 30), clock.instant());
		branch = branches.save(branch);
		if (branch.getKind() == Branch.Kind.SHOP) {
			limitTills(1);
			tills.save(new Till(branch.getId(), "Till 1"));
		}
		audit.record("BRANCH_CREATED", "branch", branch.getId(), null, Map.of("name", name, "code", code));
		return BranchView.of(branch);
	}

	@Transactional
	public BranchView update(long id, BranchInput input) {
		Branch branch = find(id);
		String name = input.name() == null ? branch.getName() : name(input.name());
		String code = input.code() == null ? branch.getCode() : code(input.code(), 0);
		ensureUnique(name, code, id);
		Map<String, Object> before = Map.of("name", branch.getName(), "code", branch.getCode());
		branch.update(name, code, input.kind() == null ? branch.getKind() : kind(input.kind()),
				input.address() == null ? branch.getAddress() : trim(input.address(), 500),
				input.phone() == null ? branch.getPhone() : trim(input.phone(), 30), clock.instant());
		audit.record("BRANCH_UPDATED", "branch", id, before, Map.of("name", name, "code", code));
		return BranchView.of(branch);
	}

	@Transactional
	public BranchView setActive(long id, boolean active) {
		Branch branch = find(id);
		if (!active && branch.isDefault()) {
			throw ApiException.rule("default_branch", "Make another branch the main branch first");
		}
		if (active && !branch.isActive()) {
			limits.check(PlanLimits.Limit.BRANCHES, branches.countByActiveTrue(), 1);
		}
		branch.setActive(active, clock.instant());
		audit.record(active ? "BRANCH_ACTIVATED" : "BRANCH_DEACTIVATED", "branch", id, null, null);
		return BranchView.of(branch);
	}

	@Transactional
	public BranchView makeDefault(long id) {
		Branch branch = find(id);
		if (!branch.isActive()) {
			throw ApiException.rule("branch_inactive", "Activate the branch first");
		}
		branches.findByIsDefaultTrue().ifPresent(current -> {
			current.setDefault(false);
			branches.saveAndFlush(current);
		});
		branch.setDefault(true);
		audit.record("BRANCH_MADE_DEFAULT", "branch", id, null, null);
		return BranchView.of(branch);
	}

	@Transactional(readOnly = true)
	public List<TillView> tills() {
		return tills.findAllOrdered().stream().map(TillView::of).toList();
	}

	@Transactional
	public TillView createTill(long branchId, String name) {
		find(branchId);
		limitTills(1);
		String clean = name(name);
		if (tills.countSameName(branchId, clean, -1) > 0) {
			throw ApiException.conflict("duplicate_name", "This branch already has a till with that name");
		}
		Till till = tills.save(new Till(branchId, clean));
		audit.record("TILL_CREATED", "till", till.getId(), null, Map.of("name", clean, "branchId", branchId));
		return TillView.of(till);
	}

	@Transactional
	public TillView updateTill(long id, String name, Boolean active) {
		Till till = tills.findById(id).orElseThrow(() -> ApiException.notFound("Till"));
		if (name != null) {
			String clean = name(name);
			if (tills.countSameName(till.getBranchId(), clean, id) > 0) {
				throw ApiException.conflict("duplicate_name", "This branch already has a till with that name");
			}
			till.rename(clean);
		}
		if (active != null && active != till.isActive()) {
			if (active) {
				limitTills(1);
			}
			till.setActive(active);
		}
		audit.record("TILL_UPDATED", "till", id, null, Map.of("name", till.getName(), "active", till.isActive()));
		return TillView.of(till);
	}

	private void limitTills(int adding) {
		limits.check(PlanLimits.Limit.TILLS, tills.countByActiveTrue(), adding);
	}

	private Branch find(long id) {
		return branches.findById(id).orElseThrow(() -> ApiException.notFound("Branch"));
	}

	private void ensureUnique(String name, String code, long exceptId) {
		branches.findByNameIgnoreCase(name).filter(b -> b.getId() != exceptId).ifPresent(b -> {
			throw ApiException.conflict("duplicate_name", "A branch with this name already exists");
		});
		branches.findByCodeIgnoreCase(code).filter(b -> b.getId() != exceptId).ifPresent(b -> {
			throw ApiException.conflict("duplicate_code", "A branch with this code already exists");
		});
	}

	private static String name(String value) {
		String s = value == null ? "" : value.trim();
		if (s.isEmpty()) {
			throw ApiException.field("name", "required", "Enter a name");
		}
		if (s.length() > 100) {
			throw ApiException.field("name", "too_long", "Use at most 100 characters");
		}
		return s;
	}

	private static String code(String value, long fallbackNumber) {
		String s = value == null || value.isBlank() ? "B" + fallbackNumber : value.trim().toUpperCase();
		if (!s.matches("[A-Z0-9-]{1,10}")) {
			throw ApiException.field("code", "invalid", "Use up to 10 letters, numbers or hyphens");
		}
		return s;
	}

	private static Branch.Kind kind(String value) {
		if (value == null || value.isBlank()) {
			return Branch.Kind.SHOP;
		}
		try {
			return Branch.Kind.valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw ApiException.field("kind", "invalid", "Choose SHOP or GODOWN");
		}
	}

	private static String trim(String v, int max) {
		if (v == null) {
			return null;
		}
		String s = v.trim();
		return s.length() > max ? s.substring(0, max) : (s.isEmpty() ? null : s);
	}
}
