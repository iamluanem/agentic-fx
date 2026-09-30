package com.agenticfx.app.web;

import com.agenticfx.app.market.MarketTicker;
import com.agenticfx.app.web.dto.Requests;
import com.agenticfx.app.web.dto.Views;
import com.agenticfx.core.fx.MockFxProvider;
import com.agenticfx.core.money.CurrencyPair;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo controls. The bean only exists with the {@code dev} profile, so these routes are absent (404) elsewhere.
 * Setting a rate goes through the same path as a market tick (snapshot + FxRateUpdated via the outbox).
 */
@RestController
@Profile("dev")
@RequestMapping("/dev")
public class DevController {

    private final MarketTicker ticker;
    private final MockFxProvider mock;

    public DevController(MarketTicker ticker, MockFxProvider mock) {
        this.ticker = ticker;
        this.mock = mock;
    }

    @PostMapping("/fx/rates")
    public Views.RateView setRate(@Valid @RequestBody Requests.DevRate request) {
        return Views.RateView.of(ticker.set(CurrencyPair.parse(request.pair()), request.rate()));
    }

    @PostMapping("/provider/fail-next")
    public ResponseEntity<Void> failNext() {
        mock.failNextExecution();
        return ResponseEntity.accepted().build();
    }
}
