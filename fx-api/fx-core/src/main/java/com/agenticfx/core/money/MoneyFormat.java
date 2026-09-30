package com.agenticfx.core.money;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Human-readable money for explanations, e.g. {@code R$ 2,000.00}. Not for persistence or APIs. */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    public static String display(Money money) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return money.currency().symbol() + " " + format.format(money.amount());
    }
}
