import type { Config } from "tailwindcss";

/**
 * Two voices, by design:
 *  - agent (indigo): what the AI observes, reasons and proposes — advisory only
 *  - authority: allow (teal), approval (amber), deny (red) — what the deterministic platform or the user decided
 */
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: { DEFAULT: "#14213D", soft: "#44506B", faint: "#7A849A" },
        canvas: "#EDF0F5",
        paper: "#FFFFFF",
        rule: "#D9DEE8",
        agent: { DEFAULT: "#4338CA", soft: "#EEF0FF", line: "#C7CBF5" },
        allow: { DEFAULT: "#0F766E", soft: "#E6F4F2" },
        approval: { DEFAULT: "#B45309", soft: "#FDF3E4" },
        deny: { DEFAULT: "#B91C1C", soft: "#FCEBEB" },
      },
      fontFamily: {
        sans: ['"Public Sans"', "system-ui", "-apple-system", "Segoe UI", "sans-serif"],
      },
      borderRadius: { panel: "10px", stamp: "4px" },
      boxShadow: { panel: "0 1px 0 rgba(20,33,61,0.06)" },
    },
  },
  plugins: [],
};

export default config;
