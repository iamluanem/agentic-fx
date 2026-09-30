package com.agenticfx.app.provider;

import com.agenticfx.core.fx.ConversionRequest;
import com.agenticfx.core.fx.ConversionResult;
import com.agenticfx.core.fx.FxProvider;
import com.agenticfx.core.fx.FxQuote;
import com.agenticfx.core.money.Currency;

import java.math.BigDecimal;

/**
 * Adapter shape for Wise (https://docs.wise.com/api-docs). Selected with {@code agentic-fx.provider.name=wise}.
 * <p>
 * Mapping, for the integration work:
 * <ul>
 *   <li>getQuote → {@code POST /v3/profiles/{profileId}/quotes} (sourceCurrency, targetCurrency, sourceAmount);
 *       rate, fee and {@code expirationTime} map directly onto {@link FxQuote}.</li>
 *   <li>execute → balance conversion {@code POST /v2/profiles/{profileId}/balance-movements} with the quote id,
 *       sending {@code X-idempotence-uuid = request.idempotencyKey()} so retries are safe.</li>
 * </ul>
 * Until implemented, this adapter refuses to start without credentials and refuses to move money with them,
 * so it can never be half-enabled by accident.
 */
public final class WiseFxProvider implements FxProvider {

    private final String token;
    private final String profileId;

    public WiseFxProvider(String token, String profileId) {
        if (token == null || token.isBlank() || profileId == null || profileId.isBlank()) {
            throw new IllegalStateException("WISE_API_TOKEN and WISE_PROFILE_ID are required for provider=wise");
        }
        this.token = token;
        this.profileId = profileId;
    }

    @Override
    public String name() {
        return "wise";
    }

    @Override
    public FxQuote getQuote(Currency source, Currency target, BigDecimal amount) {
        throw notImplemented("quotes");
    }

    @Override
    public ConversionResult execute(ConversionRequest request) {
        throw notImplemented("conversions");
    }

    private UnsupportedOperationException notImplemented(String what) {
        return new UnsupportedOperationException("Wise " + what + " are not enabled in this MVP (profile "
                + profileId.substring(0, Math.min(4, profileId.length())) + "…, token set: " + !token.isEmpty() + ")");
    }
}
