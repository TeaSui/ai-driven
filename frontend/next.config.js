/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: 'http://localhost:8888/api/:path*',
      },
      {
        source: '/health',
        destination: 'http://localhost:8888/health',
      },
    ]
  },
}

module.exports = nextConfig
