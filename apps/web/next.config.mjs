/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  reactStrictMode: true,
  typedRoutes: true
};

export default nextConfig;
