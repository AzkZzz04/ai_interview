/** @type {import('next').NextConfig} */
const nextConfig = {
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  reactStrictMode: true,
  experimental: {
    typedRoutes: true
  }
};

export default nextConfig;
