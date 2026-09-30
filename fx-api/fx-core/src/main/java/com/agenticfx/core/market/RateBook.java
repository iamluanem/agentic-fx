package com.agenticfx.core.market;

import com.agenticfx.core.money.CurrencyPair;

import java.util.List;
import java.util.Optional;

/** Read side of market data. Any listed pair can also be observed in the inverse direction. */
public interface RateBook {

    Optional<RateObservation> observe(CurrencyPair pair);

    List<RateObservation> snapshot();
}
