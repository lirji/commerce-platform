import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: {
    port: 8601,
    strictPort: true,
    proxy: {
      "/v1": process.env.COMMERCE_API_URL ?? "http://127.0.0.1:8600",
      "/actuator": process.env.COMMERCE_API_URL ?? "http://127.0.0.1:8600",
    },
  },
  build: { outDir: "dist", chunkSizeWarningLimit: 900 },
});
