import nextVitals from "eslint-config-next/core-web-vitals";

const config = [
  ...nextVitals,
  { ignores: [".next/**", "out/**", "public/mockServiceWorker.js"] },
  {
    // Old UI predating the React 19 hook rules; deleted with the old page in U7.
    files: ["app/page.tsx", "lib/useJobPolling.ts", "lib/workflows/**"],
    rules: { "react-hooks/refs": "off", "react-hooks/set-state-in-effect": "off" }
  }
];

export default config;
