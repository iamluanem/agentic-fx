package com.agenticfx.app.web;

import com.agenticfx.app.security.CurrentUser;
import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.market.RateBook;
import com.agenticfx.core.money.Currency;
import com.agenticfx.core.port.Repositories;
import com.agenticfx.core.port.Transactions;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@Validated
public class MarketAndAccountController {

    private final RateBook rates;
    private final FxProvider provider;
    private final Repositories.Bundle repos;
    private final Transactions tx;
    private final ViewAssembler views;

    public MarketAndAccountController(RateBook rates, FxProvider provider, Repositories.Bundle repos, Transactions tx,
                                      ViewAssembler views) {
        this.rates = rates;
        this.provider = provider;
        this.repos = repos;
        this.tx = tx;
        this.views = views;
    }

    @GetMapping("/fx/rates")
    public List<Views.RateView> rates() {
        return rates.snapshot().stream().map(Views.RateView::of).toList();
    }

    /** Indicative quote. Not persisted and not executable: execution always obtains its own fresh quote. */
    @GetMapping("/fx/quotes")
    public Views.QuoteView quote(@RequestParam String source, @RequestParam String target,
                                 @RequestParam @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
        return Views.QuoteView.of(provider.getQuote(Currency.of(source), Currency.of(target), amount));
    }

    @GetMapping("/accounts/me/balances")
    public List<Views.BalanceView> balances(@AuthenticationPrincipal Jwt jwt) {
        return views.balances(CurrentUser.id(jwt));
    }

    @GetMapping("/activity")
    public List<Views.AuditView> activity(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return tx.inTransaction(() -> repos.audit().findByUser(CurrentUser.id(jwt), bounded)).stream()
                .map(Views.AuditView::of).toList();
    }

}
