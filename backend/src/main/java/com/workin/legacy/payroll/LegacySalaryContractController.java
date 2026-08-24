package com.workin.legacy.payroll;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/apis/api/salary_contracts")
public class LegacySalaryContractController {

    private final LegacySalaryContractService service;
    private final LegacyRequestGuard guard;
    private final LegacyMessages messages;

    public LegacySalaryContractController(LegacySalaryContractService service,
            LegacyRequestGuard guard, LegacyMessages messages) {
        this.service = service;
        this.guard = guard;
        this.messages = messages;
    }

    @RequestMapping("/list.php")
    public LegacyApiResponse list(HttpServletRequest request) {
        requireMethod(request, "GET");
        LegacyRequestContext context = readRole();
        LegacySalaryContractService.Page page = service.list(context.companyId(),
                LegacyQueryParameters.parse(request.getQueryString()));
        return LegacyApiResponse.ok(message(request, "salary_history"), page.rows(), page.meta());
    }

    @RequestMapping("/one.php")
    public LegacyApiResponse one(HttpServletRequest request) {
        requireMethod(request, "GET");
        LegacyRequestContext context = readRole();
        return LegacyApiResponse.ok(message(request, "ok"),
                service.one(context.companyId(), requiredId(request)));
    }

    @RequestMapping("/create.php")
    public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
        requireMethod(request, "POST");
        LegacyRequestContext context = writeRole();
        Map<String, Object> row = service.create(context.companyId(), LegacyJsonBody.read(request));
        return ResponseEntity.status(201)
                .body(LegacyApiResponse.ok(message(request, "salary_contract_saved"), row));
    }

    @RequestMapping("/update.php")
    public LegacyApiResponse update(HttpServletRequest request) {
        requireMethod(request, "PUT");
        LegacyRequestContext context = writeRole();
        return LegacyApiResponse.ok(message(request, "salary_updated"),
                service.update(context.companyId(), requiredId(request), LegacyJsonBody.read(request)));
    }

    @RequestMapping("/delete.php")
    public LegacyApiResponse delete(HttpServletRequest request) {
        requireMethod(request, "DELETE");
        LegacyRequestContext context = writeRole();
        service.delete(context.companyId(), requiredId(request));
        return LegacyApiResponse.ok(message(request, "ok"), null);
    }

    private LegacyRequestContext readRole() {
        LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN,
                LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
        guard.requireCompanyActive(context.companyId());
        return context;
    }

    private LegacyRequestContext writeRole() {
        LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
        guard.requireCompanyActive(context.companyId());
        return context;
    }

    private static long requiredId(HttpServletRequest request) {
        Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
        if (id == null || "".equals(id)) {
            throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
        }
        return LegacyValues.toPhpLong(id);
    }

    private static void requireMethod(HttpServletRequest request, String expected) {
        if (!expected.equals(request.getMethod())) throw new LegacyApiException(405, "invalid_method");
    }

    private String message(HttpServletRequest request, String key) {
        return messages.translate(messages.resolveLocale(request), key, null);
    }
}
