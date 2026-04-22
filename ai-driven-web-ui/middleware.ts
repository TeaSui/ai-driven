import { NextResponse, type NextRequest } from 'next/server'

export function middleware(request: NextRequest) {
  // Protect all /dashboard routes — require api_key cookie
  const apiKey = request.cookies.get('api_key')?.value

  if (!apiKey) {
    return NextResponse.redirect(new URL('/login', request.url))
  }

  // Forward team identity via headers for server components (future use)
  const response = NextResponse.next()
  response.headers.set('x-api-key', apiKey)
  return response
}

export const config = {
  matcher: ['/dashboard/:path*'],
}
