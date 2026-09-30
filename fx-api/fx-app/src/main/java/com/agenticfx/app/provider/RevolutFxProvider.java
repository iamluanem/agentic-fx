package com.agenticfx.app.provider;

import com.agenticfx.core.fx.ConversionRequest;
import com.agenticfx.core.fx.ConversionResult;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.money.Currency;

import java.math.BigDecimal;

/**
 * Adapter shape for Revolut Business (https://developer.revolut.com/docs/business). Selected with
 * {@code agentic-fx.provider.name=revolut}.
 * <ul>
 *   <li>getQuote → {@code GET /api/1.0/rate?from=&to=&amount=}; Revolut rates are indicative, so the adapter must
 *       apply its own short expiry when mapping to {@link FxQuote}.</li>
 *   <li>execute → {@code POST /api/1.0/exchange} with {@code request_id = request.idempotencyKey()}, which Revolut
 *       uses for deduplication.</li>
 * </ul>
 * Refuses to start without credentials and to move money until implemented.
 */
public final class RevolutFxProvider implements FxProvider {

    public RevolutFxProvider(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("REVOLUT_API_TOKEN is required for provider=revolut");
        }
    }

    @Override
    public String name() {
        return "revolut";
    }

    @Override
    public FxQuote getQuote(Currency source, Currency target, BigDecimal amount) {
        throw new UnsupportedOperationException("Revolut quotes are not enabled in this MVP");
    }

    @Override
    public ConversionResult execute(ConversionRequest request) {
        throw new UnsupportedOperationException("Revolut conversions are not enabled in this MVP");
    }
}
