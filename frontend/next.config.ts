import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Produces a self-contained server bundle under .next/standalone (used by the Dockerfile).
  output: "standalone",
};

export default nextConfig;
