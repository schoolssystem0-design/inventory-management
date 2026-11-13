package com.selectcode.pos.cash.api;

import com.selectcode.pos.cash.app.CashService;
import com.selectcode.pos.cash.app.CashService.DailyCash;
import com.selectcode.pos.cash.app.CashService.ExpenseCategoryView;
import com.selectcode.pos.cash.app.CashService.ExpenseInput;
import com.selectcode.pos.cash.app.CashService.ExpenseView;
import com.selectcode.pos.cash.app.CashService.ProfitLoss;
import com.selectcode.pos.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Expenses, the daily cash summary and profit and loss (CSH-01, CSH-03, CSH-04). */
@RestController
@RequestMapping("/api/v1")
class CashController {

	static final String VIEW = "hasAnyAuthority('cash.view', 'expenses.manage')";

	static final String MANAGE = "hasAuthority('expenses.manage')";

	record ExpenseCategoryInput(String name, Boolean active) {
	}

	record VoidInput(String reason) {
	}

	private final CashService cash;

	CashController(CashService cash) {
		this.cash = cash;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/expense-categories")
	List<ExpenseCategoryView> categories() {
		return cash.categories();
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/expense-categories")
	@ResponseStatus(HttpStatus.CREATED)
	ExpenseCategoryView createCategory(@RequestBody ExpenseCategoryInput in) {
		return cash.createCategory(in.name());
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/expense-categories/{id}")
	ExpenseCategoryView updateCategory(@PathVariable long id, @RequestBody ExpenseCategoryInput in) {
		return cash.updateCategory(id, in.name(), in.active());
	}

	@PreAuthorize(VIEW)
	@GetMapping("/expenses")
	PageResponse<ExpenseView> expenses(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) Long categoryId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return cash.expenses(from, to, branchId, categoryId, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/expenses/{id}")
	ExpenseView expense(@PathVariable long id) {
		return cash.expense(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/expenses")
	@ResponseStatus(HttpStatus.CREATED)
	ExpenseView createExpense(@RequestBody ExpenseInput in) {
		return cash.createExpense(in);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/expenses/{id}/void")
	ExpenseView voidExpense(@PathVariable long id, @RequestBody(required = false) VoidInput in) {
		return cash.voidExpense(id, in == null ? null : in.reason());
	}

	@PreAuthorize("hasAuthority('cash.view')")
	@GetMapping("/cash/daily")
	DailyCash daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) Long branchId) {
		return cash.daily(date, branchId);
	}

	@PreAuthorize("hasAuthority('reports.view') and hasAuthority('cost.view')")
	@GetMapping("/cash/profit-loss")
	ProfitLoss profitLoss(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long branchId) {
		return cash.profitLoss(from, to, branchId);
	}
}
