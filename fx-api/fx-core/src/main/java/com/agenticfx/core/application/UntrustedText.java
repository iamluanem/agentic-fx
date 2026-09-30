package com.agenticfx.core.application;

/** Sanitises free text produced by the LLM before it is stored or shown. It is never interpreted. */
final class UntrustedText {

    private UntrustedText() {
    }

    static String clean(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String stripped = text.replaceAll("[\\p{Cntrl}&&[^\n]]", " ").strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength - 1) + "…";
    }
}
