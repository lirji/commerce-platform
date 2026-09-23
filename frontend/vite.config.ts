import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: {
    port: 8601,
    strictPort: true,
    proxy: {
      "/v1": "http://127.0.0.1:8600",
      "/actuator": "http://127.0.0.1:8600",
    },
  },
  build: { outDir: "dist", chunkSizeWarningLimit: 900 },
});
