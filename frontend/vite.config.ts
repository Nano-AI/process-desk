import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Overridable so a second backend can be run alongside one that is already up,
      // which is the only way to look at two providers without stopping either.
      "/api": process.env.API_URL ?? "http://localhost:4000",
    },
  },
});
